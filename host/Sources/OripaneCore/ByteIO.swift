import Foundation

public enum WireError: Error, Equatable {
    case truncated
    case badVersion(UInt8)
    case badType(UInt8)
    case authentication
    case malformed(String)
}

public struct ByteWriter {
    public private(set) var data = Data()

    public init(capacity: Int = 64) { data.reserveCapacity(capacity) }

    public mutating func u8(_ v: UInt8) { data.append(v) }
    public mutating func u16(_ v: UInt16) { append(v.bigEndian) }
    public mutating func u32(_ v: UInt32) { append(v.bigEndian) }
    public mutating func u64(_ v: UInt64) { append(v.bigEndian) }
    public mutating func f32(_ v: Float) { u32(v.bitPattern) }
    public mutating func bytes(_ d: Data) { data.append(d) }
    public mutating func bytes(_ b: [UInt8]) { data.append(contentsOf: b) }

    private mutating func append<T: FixedWidthInteger>(_ v: T) {
        withUnsafeBytes(of: v) { data.append(contentsOf: $0) }
    }
}

public struct ByteReader {
    private let data: Data
    public private(set) var offset: Int

    public init(_ data: Data) {
        self.data = data
        self.offset = data.startIndex
    }

    public var remaining: Int { data.endIndex - offset }

    public mutating func u8() throws -> UInt8 {
        guard remaining >= 1 else { throw WireError.truncated }
        defer { offset += 1 }
        return data[offset]
    }

    public mutating func u16() throws -> UInt16 { try integer() }
    public mutating func u32() throws -> UInt32 { try integer() }
    public mutating func u64() throws -> UInt64 { try integer() }
    public mutating func f32() throws -> Float { Float(bitPattern: try u32()) }

    public mutating func bytes(_ n: Int) throws -> Data {
        guard n >= 0, remaining >= n else { throw WireError.truncated }
        defer { offset += n }
        return data.subdata(in: offset..<(offset + n))
    }

    public mutating func rest() -> Data {
        defer { offset = data.endIndex }
        return data.subdata(in: offset..<data.endIndex)
    }

    private mutating func integer<T: FixedWidthInteger>() throws -> T {
        let size = MemoryLayout<T>.size
        guard remaining >= size else { throw WireError.truncated }
        var v: T = 0
        for i in 0..<size { v = (v << 8) | T(data[offset + i]) }
        offset += size
        return v
    }
}

extension Data {
    public var hex: String { map { String(format: "%02x", $0) }.joined() }

    public init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var out = Data(capacity: hex.count / 2)
        var it = hex.makeIterator()
        while let a = it.next(), let b = it.next() {
            guard let byte = UInt8(String([a, b]), radix: 16) else { return nil }
            out.append(byte)
        }
        self = out
    }
}
