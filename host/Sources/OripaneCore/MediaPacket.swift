import Foundation

public enum MediaType: UInt8 {
    case videoFragment = 1
    case videoParity = 2
    case input = 3
    case feedback = 4
    case hello = 5
    case helloAck = 6
}

public struct MediaHeader: Equatable {
    public static let size = 14
    public static let version: UInt8 = 1

    public var type: MediaType
    public var sessionId: UInt32
    public var seq: UInt64

    public init(type: MediaType, sessionId: UInt32, seq: UInt64) {
        self.type = type
        self.sessionId = sessionId
        self.seq = seq
    }

    public var encoded: Data {
        var w = ByteWriter(capacity: Self.size)
        w.u8(Self.version)
        w.u8(type.rawValue)
        w.u32(sessionId)
        w.u64(seq)
        return w.data
    }

    public static func decode(_ packet: Data) throws -> MediaHeader {
        var r = ByteReader(packet)
        let version = try r.u8()
        guard version == Self.version else { throw WireError.badVersion(version) }
        let raw = try r.u8()
        guard let type = MediaType(rawValue: raw) else { throw WireError.badType(raw) }
        return MediaHeader(type: type, sessionId: try r.u32(), seq: try r.u64())
    }
}

public enum MediaCodec {
    public static func seal(_ header: MediaHeader, plaintext: Data, cipher: SessionCipher, dir: Direction) throws -> Data {
        let aad = header.encoded
        return aad + (try cipher.seal(plaintext, dir: dir, counter: header.seq, aad: aad))
    }

    public static func open(_ packet: Data, cipher: SessionCipher, dir: Direction) throws -> (MediaHeader, Data) {
        let header = try MediaHeader.decode(packet)
        let start = packet.startIndex + MediaHeader.size
        let aad = packet.subdata(in: packet.startIndex..<start)
        let body = packet.subdata(in: start..<packet.endIndex)
        return (header, try cipher.open(body, dir: dir, counter: header.seq, aad: aad))
    }
}

/// Frame-level fields every fragment and parity packet repeats, so that any one packet of a frame
/// is enough to know its size and timing — including a fragment rebuilt from parity.
public struct FrameInfo: Equatable {
    public var frameId: UInt32
    public var fragCount: UInt16
    public var keyframe: Bool
    public var captureUs: UInt64
    public var hostUs: UInt32
    public var frameBytes: UInt32

    public init(frameId: UInt32, fragCount: UInt16, keyframe: Bool, captureUs: UInt64, hostUs: UInt32, frameBytes: UInt32) {
        self.frameId = frameId
        self.fragCount = fragCount
        self.keyframe = keyframe
        self.captureUs = captureUs
        self.hostUs = hostUs
        self.frameBytes = frameBytes
    }
}

public struct VideoFragment: Equatable {
    public var info: FrameInfo
    public var fragIndex: UInt16
    public var payload: Data

    public init(info: FrameInfo, fragIndex: UInt16, payload: Data) {
        self.info = info
        self.fragIndex = fragIndex
        self.payload = payload
    }

    public var encoded: Data {
        var w = ByteWriter(capacity: 25 + payload.count)
        w.u32(info.frameId)
        w.u16(fragIndex)
        w.u16(info.fragCount)
        w.u8(info.keyframe ? 1 : 0)
        w.u64(info.captureUs)
        w.u32(info.hostUs)
        w.u32(info.frameBytes)
        w.bytes(payload)
        return w.data
    }

    public static func decode(_ d: Data) throws -> VideoFragment {
        var r = ByteReader(d)
        let frameId = try r.u32()
        let fragIndex = try r.u16()
        let fragCount = try r.u16()
        let flags = try r.u8()
        let info = FrameInfo(
            frameId: frameId, fragCount: fragCount, keyframe: flags & 1 != 0,
            captureUs: try r.u64(), hostUs: try r.u32(), frameBytes: try r.u32()
        )
        return VideoFragment(info: info, fragIndex: fragIndex, payload: r.rest())
    }
}

public struct VideoParity: Equatable {
    public var info: FrameInfo
    public var groupStart: UInt16
    public var groupCount: UInt8
    public var lenXor: UInt16
    public var parity: Data

    public init(info: FrameInfo, groupStart: UInt16, groupCount: UInt8, lenXor: UInt16, parity: Data) {
        self.info = info
        self.groupStart = groupStart
        self.groupCount = groupCount
        self.lenXor = lenXor
        self.parity = parity
    }

    public var encoded: Data {
        var w = ByteWriter(capacity: 28 + parity.count)
        w.u32(info.frameId)
        w.u16(groupStart)
        w.u8(groupCount)
        w.u16(info.fragCount)
        w.u8(info.keyframe ? 1 : 0)
        w.u64(info.captureUs)
        w.u32(info.hostUs)
        w.u32(info.frameBytes)
        w.u16(lenXor)
        w.bytes(parity)
        return w.data
    }

    public static func decode(_ d: Data) throws -> VideoParity {
        var r = ByteReader(d)
        let frameId = try r.u32()
        let groupStart = try r.u16()
        let groupCount = try r.u8()
        let fragCount = try r.u16()
        let flags = try r.u8()
        let info = FrameInfo(
            frameId: frameId, fragCount: fragCount, keyframe: flags & 1 != 0,
            captureUs: try r.u64(), hostUs: try r.u32(), frameBytes: try r.u32()
        )
        let lenXor = try r.u16()
        return VideoParity(info: info, groupStart: groupStart, groupCount: groupCount, lenXor: lenXor, parity: r.rest())
    }
}

public enum PointerAction: UInt8 {
    case move = 0, down = 1, drag = 2, up = 3
    /// Left button down as the next click of a multi-click (the client decides what continues).
    case downContinued = 4
}

public enum InputEvent: Equatable {
    case pointer(action: PointerAction, x: Float, y: Float)
    case scroll(dx: Float, dy: Float)
    case rightClick(x: Float, y: Float)

    /// Whether every coordinate is a number; a NaN or an infinity would trap where it becomes
    /// an integer on the host.
    public var isFinite: Bool {
        switch self {
        case let .pointer(_, x, y), let .rightClick(x, y): return x.isFinite && y.isFinite
        case let .scroll(dx, dy): return dx.isFinite && dy.isFinite
        }
    }
}

public struct SequencedInput: Equatable {
    public var seq: UInt32
    public var event: InputEvent

    public init(seq: UInt32, event: InputEvent) {
        self.seq = seq
        self.event = event
    }
}

/// Which inputs of a packet are new. Every packet repeats the last few inputs against loss, so
/// each input is applied once, in order; the sequence wraps.
public struct InputSequence {
    private var last: UInt32?

    public init() {}

    public mutating func fresh(_ events: [SequencedInput]) -> [SequencedInput] {
        events.filter { e in
            if let l = last, !(e.seq &- l > 0 && e.seq &- l < 0x8000_0000) { return false }
            last = e.seq
            return true
        }
    }
}

public enum InputPacket {
    public static func encode(_ events: [SequencedInput]) -> Data {
        var w = ByteWriter()
        w.u8(UInt8(events.count))
        for e in events {
            w.u32(e.seq)
            switch e.event {
            case let .pointer(action, x, y):
                w.u8(1); w.u8(action.rawValue); w.f32(x); w.f32(y)
            case let .scroll(dx, dy):
                w.u8(2); w.f32(dx); w.f32(dy)
            case let .rightClick(x, y):
                w.u8(3); w.f32(x); w.f32(y)
            }
        }
        return w.data
    }

    public static func decode(_ d: Data) throws -> [SequencedInput] {
        var r = ByteReader(d)
        let count = try r.u8()
        var out: [SequencedInput] = []
        for _ in 0..<count {
            let seq = try r.u32()
            let kind = try r.u8()
            switch kind {
            case 1:
                let raw = try r.u8()
                guard let action = PointerAction(rawValue: raw) else { throw WireError.malformed("pointer action \(raw)") }
                out.append(SequencedInput(seq: seq, event: .pointer(action: action, x: try r.f32(), y: try r.f32())))
            case 2:
                out.append(SequencedInput(seq: seq, event: .scroll(dx: try r.f32(), dy: try r.f32())))
            case 3:
                out.append(SequencedInput(seq: seq, event: .rightClick(x: try r.f32(), y: try r.f32())))
            default:
                throw WireError.malformed("input kind \(kind)")
            }
        }
        return out
    }
}

public struct Feedback: Equatable {
    public var lastFrameId: UInt32
    public var packetsReceived: UInt32
    public var packetsLost: UInt32
    public var framesRecovered: UInt32
    public var framesDropped: UInt32
    public var decodeUs: UInt32
    public var totalUs: UInt32

    public init(lastFrameId: UInt32, packetsReceived: UInt32, packetsLost: UInt32, framesRecovered: UInt32, framesDropped: UInt32, decodeUs: UInt32, totalUs: UInt32) {
        self.lastFrameId = lastFrameId
        self.packetsReceived = packetsReceived
        self.packetsLost = packetsLost
        self.framesRecovered = framesRecovered
        self.framesDropped = framesDropped
        self.decodeUs = decodeUs
        self.totalUs = totalUs
    }

    public var encoded: Data {
        var w = ByteWriter(capacity: 28)
        for v in [lastFrameId, packetsReceived, packetsLost, framesRecovered, framesDropped, decodeUs, totalUs] { w.u32(v) }
        return w.data
    }

    public static func decode(_ d: Data) throws -> Feedback {
        var r = ByteReader(d)
        return Feedback(
            lastFrameId: try r.u32(), packetsReceived: try r.u32(), packetsLost: try r.u32(),
            framesRecovered: try r.u32(), framesDropped: try r.u32(), decodeUs: try r.u32(), totalUs: try r.u32()
        )
    }
}
