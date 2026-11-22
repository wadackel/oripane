import CoreMedia
import OripaneCore
import Foundation
import VideoToolbox

struct EncodedFrame {
    let annexB: Data
    let keyframe: Bool
    let captureUs: UInt64
    let encodedUs: UInt64
}

/// H.264 or HEVC in VideoToolbox's low-latency mode: no frame reordering, one frame in, one frame
/// out, keyframes only on request. At most one frame is in flight; a frame arriving while the
/// encoder is busy is dropped rather than queued, because a queue here is latency that never drains.
final class Encoder {
    let width: Int
    let height: Int
    private let hevc: Bool
    private var session: VTCompressionSession?
    private let lock = NSLock()
    private var inFlight = 0
    private var forceKeyframe = true
    private let onFrame: (EncodedFrame) -> Void
    /// Called once when the encoder stops producing frames; until then a failure only logs.
    private let onFailure: (String) -> Void
    /// Failed encodes and outputs in a row, and when the frame in flight went in.
    private var failures = 0
    private var inFlightSince: UInt64 = 0
    private var failed = false
    private(set) var bitrate: Int

    /// `codec` is `"hevc"` or `"h264"`; anything else is H.264.
    init?(width: Int, height: Int, fps: Int, bitrate: Int, codec: String, onFrame: @escaping (EncodedFrame) -> Void, onFailure: @escaping (String) -> Void) {
        self.width = width
        self.height = height
        self.hevc = codec == "hevc"
        self.bitrate = bitrate
        self.onFrame = onFrame
        self.onFailure = onFailure

        let spec = [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true] as CFDictionary
        var s: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: nil, width: Int32(width), height: Int32(height), codecType: hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264,
            encoderSpecification: spec, imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &s
        )
        guard status == noErr, let s else {
            log("encoder create_failed status=\(status)")
            return nil
        }
        session = s
        let props: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: true,
            kVTCompressionPropertyKey_ProfileLevel: hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_ConstrainedHigh_AutoLevel,
            kVTCompressionPropertyKey_AllowFrameReordering: false,
            kVTCompressionPropertyKey_ExpectedFrameRate: fps,
            kVTCompressionPropertyKey_MaxKeyFrameInterval: Int32.max,
            kVTCompressionPropertyKey_AverageBitRate: bitrate,
            kVTCompressionPropertyKey_DataRateLimits: Self.rateLimits(bitrate),
        ]
        for (k, v) in props {
            let st = VTSessionSetProperty(s, key: k, value: v as CFTypeRef)
            if st != noErr { log("encoder property_failed key=\(k) status=\(st)") }
        }
        VTCompressionSessionPrepareToEncodeFrames(s)
        log("encoder ready size=\(width)x\(height) fps=\(fps) bitrate=\(bitrate) codec=\(hevc ? "hevc" : "h264")")
    }

    deinit { invalidate() }

    func invalidate() {
        lock.lock(); let s = session; session = nil; lock.unlock()
        if let s {
            VTCompressionSessionCompleteFrames(s, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(s)
        }
    }

    func requestKeyframe() {
        lock.lock(); forceKeyframe = true; lock.unlock()
    }

    func setBitrate(_ bps: Int) {
        lock.lock(); let s = session; bitrate = bps; lock.unlock()
        guard let s else { return }
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AverageBitRate, value: bps as CFTypeRef)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_DataRateLimits, value: Self.rateLimits(bps))
    }

    /// A ceiling over each second at 1.5 times the average: the average alone lets a run of large
    /// frames overshoot for as long as the encoder likes.
    private static func rateLimits(_ bps: Int) -> CFArray {
        [bps / 8 * 3 / 2, 1] as CFArray
    }

    /// Returns false when the frame was dropped because the previous one is still encoding.
    @discardableResult
    func encode(_ pixelBuffer: CVPixelBuffer, captureUs: UInt64, pts: CMTime) -> Bool {
        lock.lock()
        // A frame that never comes back holds `inFlight` forever, and every later frame is dropped.
        if inFlight > 0, monotonicMicros() - inFlightSince > 2_000_000 {
            lock.unlock()
            fail("encoder_stalled")
            return false
        }
        guard let s = session, inFlight == 0 else { lock.unlock(); return false }
        inFlight += 1
        inFlightSince = monotonicMicros()
        let key = forceKeyframe
        forceKeyframe = false
        lock.unlock()

        let options = key ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
        let status = VTCompressionSessionEncodeFrame(
            s, imageBuffer: pixelBuffer, presentationTimeStamp: pts, duration: .invalid,
            frameProperties: options, infoFlagsOut: nil
        ) { [weak self] status, _, sample in
            self?.finish(status: status, sample: sample, captureUs: captureUs)
        }
        if status != noErr {
            log("encoder encode_failed status=\(status)")
            lock.lock(); inFlight -= 1; if key { forceKeyframe = true }; lock.unlock()
            noteFailure()
        }
        return status == noErr
    }

    private func noteFailure() {
        lock.lock(); failures += 1; let n = failures; lock.unlock()
        if n >= 3 { fail("encoder_failed") }
    }

    private func fail(_ reason: String) {
        lock.lock(); let first = !failed; failed = true; lock.unlock()
        guard first else { return }
        log("encoder failed reason=\(reason)")
        onFailure(reason)
    }

    private func finish(status: OSStatus, sample: CMSampleBuffer?, captureUs: UInt64) {
        defer { lock.lock(); inFlight -= 1; lock.unlock() }
        guard status == noErr, let sample, let block = CMSampleBufferGetDataBuffer(sample) else {
            if status != noErr {
                log("encoder output_failed status=\(status)")
                noteFailure()
            }
            return
        }
        lock.lock(); failures = 0; lock.unlock()
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]]
        let notSync = attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
        let keyframe = !notSync

        var length = 0
        var pointer: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &pointer) == noErr,
              let pointer else { return }
        let avcc = Data(bytes: pointer, count: length)

        var parameterSets: [Data] = []
        var nalLength: Int32 = 4
        if let format = CMSampleBufferGetFormatDescription(sample) {
            var count = 0
            // HEVC's sets are VPS, SPS and PPS, in the order its decoders want them.
            let parameterSet = hevc ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex : CMVideoFormatDescriptionGetH264ParameterSetAtIndex
            parameterSet(format, 0, nil, nil, &count, &nalLength)
            if keyframe {
                for i in 0..<count {
                    var ptr: UnsafePointer<UInt8>?
                    var size = 0
                    if parameterSet(format, i, &ptr, &size, nil, nil) == noErr, let ptr {
                        parameterSets.append(Data(bytes: ptr, count: size))
                    }
                }
            }
        }
        do {
            let annexB = try AnnexB.fromAVCC(avcc, nalLengthSize: Int(nalLength), parameterSets: parameterSets)
            onFrame(EncodedFrame(annexB: annexB, keyframe: keyframe, captureUs: captureUs, encodedUs: monotonicMicros()))
        } catch {
            log("encoder annexb_failed error=\(error)")
        }
    }
}
