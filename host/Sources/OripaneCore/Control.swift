import Foundation

/// Length-prefixed framing for the TCP control channel.
public struct FrameBuffer {
    public static let maxFrame = 1 << 20

    private var buffer = Data()

    public init() {}

    public static func frame(_ payload: Data) -> Data {
        var w = ByteWriter(capacity: 4 + payload.count)
        w.u32(UInt32(payload.count))
        w.bytes(payload)
        return w.data
    }

    public mutating func append(_ d: Data) { buffer.append(d) }

    public mutating func next() throws -> Data? {
        guard buffer.count >= 4 else { return nil }
        var r = ByteReader(buffer)
        let len = Int(try r.u32())
        guard len <= Self.maxFrame else { throw WireError.malformed("control frame of \(len) bytes") }
        guard buffer.count >= 4 + len else { return nil }
        let start = buffer.startIndex + 4
        let payload = buffer.subdata(in: start..<(start + len))
        buffer = buffer.subdata(in: (start + len)..<buffer.endIndex)
        return payload
    }
}

/// Seals and opens control messages with implicit per-direction counters.
public struct ControlCipher {
    private let cipher: SessionCipher
    private let sendDir: Direction
    private let recvDir: Direction
    private var sent: UInt64 = 0
    private var received: UInt64 = 0

    public init(sessionKey: Data, isHost: Bool) {
        cipher = SessionCipher(key: sessionKey)
        sendDir = isHost ? .controlToClient : .controlToHost
        recvDir = isHost ? .controlToHost : .controlToClient
    }

    public mutating func seal(_ plaintext: Data) throws -> Data {
        defer { sent += 1 }
        return try cipher.seal(plaintext, dir: sendDir, counter: sent)
    }

    public mutating func open(_ sealed: Data) throws -> Data {
        defer { received += 1 }
        return try cipher.open(sealed, dir: recvDir, counter: received)
    }
}

public enum Json {
    public static func encode(_ object: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
    }

    public static func decode(_ data: Data) throws -> [String: Any] {
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw WireError.malformed("control message is not an object")
        }
        return obj
    }
}

/// VideoToolbox emits AVCC or HVCC (4-byte length-prefixed NAL units) with the parameter sets kept
/// in the format description; MediaCodec wants an Annex-B stream with them in-band before each
/// keyframe (SPS and PPS for H.264, VPS, SPS and PPS for HEVC).
public enum AnnexB {
    public static let startCode = Data([0, 0, 0, 1])

    public static func fromAVCC(_ avcc: Data, nalLengthSize: Int = 4, parameterSets: [Data] = []) throws -> Data {
        var out = Data(capacity: avcc.count + 64)
        for ps in parameterSets {
            out.append(startCode)
            out.append(ps)
        }
        var i = avcc.startIndex
        while i < avcc.endIndex {
            guard avcc.endIndex - i >= nalLengthSize else { throw WireError.truncated }
            var len = 0
            for k in 0..<nalLengthSize { len = (len << 8) | Int(avcc[i + k]) }
            i += nalLengthSize
            guard avcc.endIndex - i >= len else { throw WireError.truncated }
            out.append(startCode)
            out.append(avcc.subdata(in: i..<(i + len)))
            i += len
        }
        return out
    }
}
