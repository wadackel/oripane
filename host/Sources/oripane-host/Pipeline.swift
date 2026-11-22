import CoreGraphics
import CoreMedia
import Foundation
import OripaneCore

/// Display → capture → encoder for one display configuration. Rebuilt whenever the client's
/// display changes (fold, unfold, rotation, mode switch).
final class Pipeline {
    let mode: String
    let fps: Int
    /// `"hevc"` or `"h264"`, as the `stream` message names it.
    let codec: String
    let streamWidth: Int
    let streamHeight: Int
    let injector: InputInjector
    /// The default for this stream size, before any client `limit`: the ceiling to climb back to.
    let unlimitedBitrate: Int
    private let source: FrameSource
    private let encoder: Encoder
    private let lock = NSLock()
    private var lastEncodedUs: UInt64 = 0
    private var encoderDropped = 0
    private var onFailure: ((String) -> Void)?

    var bitrate: Int { encoder.bitrate }

    private init(mode: String, fps: Int, codec: String, streamWidth: Int, streamHeight: Int, injector: InputInjector, unlimitedBitrate: Int, source: FrameSource, encoder: Encoder) {
        self.mode = mode
        self.fps = fps
        self.codec = codec
        self.unlimitedBitrate = unlimitedBitrate
        self.streamWidth = streamWidth
        self.streamHeight = streamHeight
        self.injector = injector
        self.source = source
        self.encoder = encoder
    }

    /// `virtualDisplay` is required for extend and match modes; it is owned by the session, not the
    /// pipeline, so that a fold or rotation resizes it instead of destroying it. It is set to
    /// `virtualPixels`, which differs from the client's panel when the display size setting
    /// gives the Mac more room; the stream is always the panel at `scale`.
    static func make(clientWidth: Int, clientHeight: Int, mode requested: String, virtualDisplay: VirtualDisplay?, virtualPixels: (width: Int, height: Int), scale: Double, fps: Int, codec: String, maxBitrate: Int, options: HostOptions, onFrame: @escaping (EncodedFrame) -> Void, onFailure: @escaping (String) -> Void) async throws -> Pipeline {
        let fake = ProcessInfo.processInfo.environment["ORIPANE_FAKE_STREAM_FAILURE"].flatMap(Double.init)
        if let fake, fake <= 0 { throw HostError.fakeFailure }
        var mode = ["mirror", "match"].contains(requested) ? requested : "extend"
        var displayID = CGMainDisplayID()
        var streamW: Int, streamH: Int

        if options.source == "test" {
            mode = "test"
            (streamW, streamH) = (even(Double(clientWidth) * scale), even(Double(clientHeight) * scale))
        } else if mode != "mirror" {
            // Match: the session sized the display and made the main display mirror it. Configuring
            // it again would switch modes on a display in a mirror set, which was never tried.
            guard let d = virtualDisplay,
                  mode == "match" ? d.waitUntilOnline() : d.configure(pixelWidth: virtualPixels.width, pixelHeight: virtualPixels.height, refreshRate: Double(fps), hiDPI: true)
            else { throw HostError.virtualDisplayFailed }
            displayID = d.displayID
            (streamW, streamH) = (even(Double(clientWidth) * scale), even(Double(clientHeight) * scale))
            log("display virtual id=\(d.displayID) points=\(Int(CGDisplayBounds(d.displayID).width))x\(Int(CGDisplayBounds(d.displayID).height)) pixels=\(d.pixelWidth)x\(d.pixelHeight)")
        } else {
            // Mirror: the main display's pixels, fitted inside the client's panel.
            let m = CGDisplayCopyDisplayMode(displayID)
            let srcW = Double(m?.pixelWidth ?? CGDisplayPixelsWide(displayID))
            let srcH = Double(m?.pixelHeight ?? CGDisplayPixelsHigh(displayID))
            let fit = min(Double(clientWidth) / srcW, Double(clientHeight) / srcH, 1) * scale
            (streamW, streamH) = (even(srcW * fit), even(srcH * fit))
            log("display mirror id=\(displayID) source=\(Int(srcW))x\(Int(srcH))")
        }

        let unlimited = defaultBitrate(width: streamW, height: streamH, fps: fps)
        let bitrate = options.bitrate ?? BitrateLimit.ceiling(unlimited: unlimited, limit: maxBitrate)
        let pipelineRef = PipelineRef()
        guard let encoder = Encoder(width: streamW, height: streamH, fps: fps, bitrate: bitrate, codec: codec, onFrame: { frame in
            pipelineRef.value?.noteEncoded(frame)
            onFrame(frame)
        }, onFailure: { reason in
            pipelineRef.value?.fail(reason)
        }) else { throw HostError.encoderFailed }

        let source: FrameSource = options.source == "test"
            ? TestPatternSource(width: streamW, height: streamH, fps: fps)
            : ScreenSource(displayID: displayID, width: streamW, height: streamH, fps: fps)
        let injector = InputInjector(displayID: displayID, dryRun: options.dryRunInput || options.source == "test")
        if options.source != "test" {
            injector.scrollScale = ScrollScale.pointsPerPixel(
                displayHeight: Double(CGDisplayBounds(displayID).height), clientWidth: clientWidth, clientHeight: clientHeight,
                streamWidth: streamW, streamHeight: streamH
            )
        }
        let p = Pipeline(mode: mode, fps: fps, codec: codec, streamWidth: streamW, streamHeight: streamH, injector: injector, unlimitedBitrate: unlimited, source: source, encoder: encoder)
        pipelineRef.value = p
        p.onFailure = onFailure
        source.onFrame = { [weak p] f in p?.encode(f) }
        source.onFailure = { [weak p] reason in p?.fail(reason) }
        try await source.start()
        if let fake {
            DispatchQueue.global().asyncAfter(deadline: .now() + fake) { [weak p] in p?.fail("fake") }
        }
        log("pipeline ready mode=\(mode) stream=\(streamW)x\(streamH) bitrate=\(bitrate)")
        return p
    }

    func stop() async {
        // A failure from a pipeline being replaced must not end the session its successor serves.
        lock.lock(); onFailure = nil; lock.unlock()
        await source.stop()
        encoder.invalidate()
        log("pipeline stopped mode=\(mode)")
    }

    private func encode(_ f: CapturedFrame) {
        if !encoder.encode(f.pixelBuffer, captureUs: f.captureUs, pts: f.pts) {
            lock.lock(); encoderDropped += 1; lock.unlock()
        }
    }

    /// Hands the first failure on and drops the rest: capture and encoder may both give up.
    private func fail(_ reason: String) {
        lock.lock(); let report = onFailure; onFailure = nil; lock.unlock()
        report?(reason)
    }

    private func noteEncoded(_ f: EncodedFrame) {
        lock.lock(); lastEncodedUs = monotonicMicros(); lock.unlock()
    }

    /// A static screen produces no new captures, so a requested keyframe is made from the newest
    /// frame if nothing arrives on its own shortly.
    func requestKeyframe() {
        encoder.requestKeyframe()
        let asked = monotonicMicros()
        DispatchQueue.global(qos: .userInteractive).asyncAfter(deadline: .now() + .milliseconds(60)) { [weak self] in
            guard let self else { return }
            self.lock.lock(); let fresh = self.lastEncodedUs >= asked; self.lock.unlock()
            guard !fresh, let latest = self.source.latest else { return }
            let now = monotonicMicros()
            self.encoder.encode(latest.pixelBuffer, captureUs: now, pts: CMTime(value: CMTimeValue(now), timescale: 1_000_000))
        }
    }

    func setBitrate(_ bps: Int) { encoder.setBitrate(bps) }

    /// Frames the encoder was still busy for since the last call.
    func takeEncoderDropped() -> Int {
        lock.lock(); defer { lock.unlock() }
        defer { encoderDropped = 0 }
        return encoderDropped
    }

    static func defaultBitrate(width: Int, height: Int, fps: Int) -> Int {
        // ~0.08 bit per pixel per frame keeps desktop text legible in H.264 at 60fps; HEVC gets the
        // same rate and spends the difference on quality.
        min(60_000_000, max(4_000_000, Int(Double(width * height * fps) * 0.08)))
    }

    private static func even(_ v: Double) -> Int { max(2, Int(v.rounded()) & ~1) }
}

private final class PipelineRef { weak var value: Pipeline? }
