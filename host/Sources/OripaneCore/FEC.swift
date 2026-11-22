import Foundation

public enum FramePacket: Equatable {
    case fragment(VideoFragment)
    case parity(VideoParity)
}

/// Splits one encoded frame into fragments and appends one XOR parity packet after every
/// `groupSize` fragments, so a single loss inside a group is repaired without a round trip.
public struct Packetizer {
    public static let fragmentSize = 1100

    public var groupSize: Int

    public init(groupSize: Int = 8) {
        precondition((1...255).contains(groupSize))
        self.groupSize = groupSize
    }

    public func packets(frame: Data, frameId: UInt32, keyframe: Bool, captureUs: UInt64, hostUs: UInt32) -> [FramePacket] {
        let f = Self.fragmentSize
        let count = max(1, (frame.count + f - 1) / f)
        precondition(count <= Int(UInt16.max), "frame too large")
        let info = FrameInfo(
            frameId: frameId, fragCount: UInt16(count), keyframe: keyframe,
            captureUs: captureUs, hostUs: hostUs, frameBytes: UInt32(frame.count)
        )
        var out: [FramePacket] = []
        out.reserveCapacity(count + count / groupSize + 1)
        var groupStart = 0
        var group: [Data] = []
        for i in 0..<count {
            let lo = frame.startIndex + i * f
            let hi = min(frame.endIndex, lo + f)
            let payload = frame.subdata(in: lo..<hi)
            out.append(.fragment(VideoFragment(info: info, fragIndex: UInt16(i), payload: payload)))
            group.append(payload)
            if group.count == groupSize || i == count - 1 {
                // For a single-fragment frame the parity is a plain copy, which is exactly the
                // redundancy a frame that small needs.
                out.append(.parity(Self.parity(info: info, groupStart: groupStart, payloads: group)))
                groupStart = i + 1
                group.removeAll(keepingCapacity: true)
            }
        }
        return out
    }

    static func parity(info: FrameInfo, groupStart: Int, payloads: [Data]) -> VideoParity {
        let longest = payloads.map(\.count).max() ?? 0
        var parity = [UInt8](repeating: 0, count: longest)
        var lenXor: UInt16 = 0
        for p in payloads {
            lenXor ^= UInt16(p.count)
            p.withUnsafeBytes { raw in
                for (i, b) in raw.enumerated() { parity[i] ^= b }
            }
        }
        return VideoParity(
            info: info, groupStart: UInt16(groupStart), groupCount: UInt8(payloads.count),
            lenXor: lenXor, parity: Data(parity)
        )
    }
}

/// Collects the packets of frames and yields each frame once it is whole, repairing one missing
/// fragment per parity group. Frames older than the newest completed one are discarded.
public struct Reassembler {
    public struct Frame: Equatable {
        public var info: FrameInfo
        public var data: Data
        public var recovered: Bool
    }

    private final class Pending {
        let info: FrameInfo
        var fragments: [Data?]
        var have = 0
        var parities: [UInt16: VideoParity] = [:]
        var recovered = false

        init(info: FrameInfo) {
            self.info = info
            self.fragments = Array(repeating: nil, count: Int(info.fragCount))
        }
    }

    private var pending: [UInt32: Pending] = [:]
    public private(set) var lastDelivered: UInt32?
    public private(set) var framesDropped = 0
    public private(set) var framesRecovered = 0

    public init() {}

    public mutating func add(_ packet: FramePacket) -> Frame? {
        let info: FrameInfo
        switch packet {
        case let .fragment(f): info = f.info
        case let .parity(p): info = p.info
        }
        if let last = lastDelivered, !Self.isNewer(info.frameId, than: last) { return nil }
        let entry = pending[info.frameId] ?? {
            let p = Pending(info: info)
            pending[info.frameId] = p
            return p
        }()
        switch packet {
        case let .fragment(f):
            let i = Int(f.fragIndex)
            guard i < entry.fragments.count, entry.fragments[i] == nil else { return nil }
            entry.fragments[i] = f.payload
            entry.have += 1
        case let .parity(p):
            entry.parities[p.groupStart] = p
        }
        repair(entry)
        guard entry.have == entry.fragments.count else { return nil }
        return deliver(entry)
    }

    private mutating func repair(_ entry: Pending) {
        for (start, p) in entry.parities {
            let lo = Int(start)
            let hi = min(entry.fragments.count, lo + Int(p.groupCount))
            let missing = (lo..<hi).filter { entry.fragments[$0] == nil }
            guard missing.count == 1, let gap = missing.first else { continue }
            var bytes = [UInt8](p.parity)
            var len = p.lenXor
            for i in lo..<hi where i != gap {
                let d = entry.fragments[i]!
                len ^= UInt16(d.count)
                d.withUnsafeBytes { raw in
                    for (j, b) in raw.enumerated() where j < bytes.count { bytes[j] ^= b }
                }
            }
            guard Int(len) <= bytes.count else { continue }
            entry.fragments[gap] = Data(bytes.prefix(Int(len)))
            entry.have += 1
            entry.recovered = true
        }
    }

    private mutating func deliver(_ entry: Pending) -> Frame {
        var data = Data(capacity: Int(entry.info.frameBytes))
        for f in entry.fragments { data.append(f!) }
        let id = entry.info.frameId
        let older = pending.keys.filter { !Self.isNewer($0, than: id) }
        // Counting the gap in ids, not the incomplete entries, also catches frames of which not a
        // single packet arrived; the decoder must hear about those too.
        if let last = lastDelivered { framesDropped += Int(id &- last &- 1) } else { framesDropped += older.count - 1 }
        for key in older { pending[key] = nil }
        lastDelivered = id
        if entry.recovered { framesRecovered += 1 }
        return Frame(info: entry.info, data: data, recovered: entry.recovered)
    }

    /// Frame ids wrap at 2^32; a difference under half the space counts as newer.
    public static func isNewer(_ a: UInt32, than b: UInt32) -> Bool {
        a != b && (a &- b) < 0x8000_0000
    }
}
