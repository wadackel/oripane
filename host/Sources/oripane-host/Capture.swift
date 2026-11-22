import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

struct CapturedFrame {
    let pixelBuffer: CVPixelBuffer
    let captureUs: UInt64
    let pts: CMTime
}

protocol FrameSource: AnyObject {
    var onFrame: ((CapturedFrame) -> Void)? { get set }
    /// The source stopped for good, with a short reason for the log.
    var onFailure: ((String) -> Void)? { get set }
    /// The newest frame, kept so a keyframe can be produced while the screen is static and the
    /// capture delivers nothing new.
    var latest: CapturedFrame? { get }
    func start() async throws
    func stop() async
}

/// Captures one display with ScreenCaptureKit, straight into the encoder's input format.
final class ScreenSource: NSObject, FrameSource, SCStreamOutput, SCStreamDelegate {
    var onFrame: ((CapturedFrame) -> Void)?
    var onFailure: ((String) -> Void)?
    private(set) var latest: CapturedFrame?
    private let displayID: CGDirectDisplayID
    private let width: Int
    private let height: Int
    private let fps: Int
    private var stream: SCStream?
    private let queue = DispatchQueue(label: "oripane.capture", qos: .userInteractive)

    init(displayID: CGDirectDisplayID, width: Int, height: Int, fps: Int) {
        self.displayID = displayID
        self.width = width
        self.height = height
        self.fps = fps
    }

    func start() async throws {
        var target: SCDisplay?
        // A just-created virtual display can take a moment to appear in the shareable content.
        for _ in 0..<40 {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            target = content.displays.first { $0.displayID == displayID }
            if target != nil { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        guard let target else { throw HostError.displayUnavailable(displayID) }
        let config = SCStreamConfiguration()
        config.width = width
        config.height = height
        config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        config.queueDepth = 3
        config.showsCursor = true
        config.scalesToFit = true
        let s = SCStream(filter: SCContentFilter(display: target, excludingWindows: []), configuration: config, delegate: self)
        try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        try await s.startCapture()
        stream = s
        log("capture started display=\(displayID) size=\(width)x\(height) fps=\(fps)")
    }

    func stop() async {
        guard let s = stream else { return }
        stream = nil
        try? await s.stopCapture()
        log("capture stopped display=\(displayID)")
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: raw) == .complete,
              let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sb)
        // SCK stamps frames on the host clock, the same mach clock `monotonicMicros` reads, but the
        // stamp can lie slightly in the future (the display's presentation time), so it is capped
        // at the moment the frame was handed over.
        let arrived = monotonicMicros()
        let stamped = pts.isValid ? UInt64(max(0, pts.seconds * 1_000_000)) : arrived
        let captureUs = min(stamped, arrived)
        let frame = CapturedFrame(pixelBuffer: pb, captureUs: captureUs, pts: pts)
        latest = frame
        onFrame?(frame)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        log("capture stopped_with_error error=\(error.localizedDescription)")
        onFailure?("capture_stopped")
    }
}

/// Synthetic frames for runs without a screen worth capturing: moving bars, a frame counter
/// rendered as blocks, and a grid, so motion, tearing and scaling are visible in a screenshot.
final class TestPatternSource: FrameSource {
    var onFrame: ((CapturedFrame) -> Void)?
    var onFailure: ((String) -> Void)?
    private(set) var latest: CapturedFrame?
    private let width: Int
    private let height: Int
    private let fps: Int
    private var timer: DispatchSourceTimer?
    private var pool: CVPixelBufferPool?
    private var index: Int64 = 0

    init(width: Int, height: Int, fps: Int) {
        self.width = width
        self.height = height
        self.fps = fps
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey: width,
            kCVPixelBufferHeightKey: height,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &pool)
    }

    func start() async throws {
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "oripane.testpattern", qos: .userInteractive))
        t.schedule(deadline: .now(), repeating: .nanoseconds(1_000_000_000 / fps))
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
        log("capture started source=test size=\(width)x\(height) fps=\(fps)")
    }

    func stop() async {
        timer?.cancel()
        timer = nil
    }

    private func tick() {
        guard let pool else { return }
        var out: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out)
        guard let pb = out else { return }
        CVPixelBufferLockBaseAddress(pb, [])
        let base = CVPixelBufferGetBaseAddress(pb)!.assumingMemoryBound(to: UInt32.self)
        let stride = CVPixelBufferGetBytesPerRow(pb) / 4
        let n = Int(index)
        let barX = (n * 8) % width
        for y in 0..<height {
            let row = base + y * stride
            for x in 0..<width {
                var c: UInt32 = (x / 64 + y / 64) % 2 == 0 ? 0xFF20_2830 : 0xFF30_3840
                if abs(x - barX) < 24 { c = 0xFF00_C8FF }
                if y < 40 { c = UInt32(0xFF00_0000) | UInt32((x * 255) / max(1, width - 1)) << 8 }
                row[x] = c
            }
        }
        // Frame counter as 16 binary blocks, readable in a screenshot.
        for bit in 0..<16 {
            let on = (n >> (15 - bit)) & 1 == 1
            // A client may ask for a stream shorter than the counter.
            for y in 60..<max(60, min(100, height)) {
                for x in (20 + bit * 30)..<(44 + bit * 30) where x < width {
                    base[y * stride + x] = on ? 0xFFFF_FFFF : 0xFF40_0000
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(pb, [])
        index += 1
        let now = monotonicMicros()
        let frame = CapturedFrame(pixelBuffer: pb, captureUs: now, pts: CMTime(value: CMTimeValue(now), timescale: 1_000_000))
        latest = frame
        onFrame?(frame)
    }
}

enum HostError: Error, CustomStringConvertible {
    case displayUnavailable(CGDirectDisplayID)
    case virtualDisplayFailed
    case encoderFailed
    case fakeFailure

    /// For `key=value` log lines.
    var logName: String {
        switch self {
        case .displayUnavailable: return "display_unavailable"
        case .virtualDisplayFailed: return "virtual_display_failed"
        case .encoderFailed: return "encoder_failed"
        case .fakeFailure: return "fake"
        }
    }

    var description: String {
        switch self {
        case .fakeFailure: return "ORIPANE_FAKE_STREAM_FAILURE=0"
        case let .displayUnavailable(id): return "display \(id) is not available to ScreenCaptureKit"
        case .virtualDisplayFailed: return "could not create the virtual display"
        case .encoderFailed: return "could not create the video encoder"
        }
    }
}
