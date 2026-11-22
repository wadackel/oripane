import Foundation

/// Multiplicative decrease on loss, slow additive recovery back up to the ceiling. Time comes in
/// as an argument so the recovery steps can be tested without waiting.
public final class RateController {
    public private(set) var ceiling: Int
    public private(set) var current: Int
    private var cleanSince: UInt64

    public init(initial: Int, now: UInt64) {
        ceiling = initial
        current = initial
        cleanSince = now
    }

    /// The new bitrate when it changes, nil otherwise.
    public func update(_ fb: Feedback, now: UInt64) -> Int? {
        // Widened first: two UInt32 counters from the network can sum past UInt32.max and trap.
        let total = UInt64(fb.packetsReceived) + UInt64(fb.packetsLost)
        guard total > 20 else { return nil }
        let loss = Double(fb.packetsLost) / Double(total)
        if loss > 0.05 {
            cleanSince = now
            let next = max(BitrateLimit.floor, Int(Double(current) * 0.8))
            guard next != current else { return nil }
            current = next
            return next
        }
        if loss > 0.01 { cleanSince = now; return nil }
        if now - cleanSince > 3_000_000, current < ceiling {
            cleanSince = now
            current = min(ceiling, current + ceiling / 10)
            return current
        }
        return nil
    }

    /// Lowering the ceiling below the current bitrate drops to it at once and returns it; raising
    /// it returns nil and leaves the climb to the clean-feedback recovery.
    public func setCeiling(_ value: Int, now: UInt64) -> Int? {
        ceiling = value
        guard current > value else { return nil }
        current = value
        cleanSince = now
        return value
    }
}

/// The client's `limit` message: a cap on the bitrate the host picks, 0 for none.
public enum BitrateLimit {
    /// The lowest bitrate the rate controller backs off to.
    public static let floor = 2_000_000

    public static func normalize(_ requested: Int) -> Int {
        requested <= 0 ? 0 : max(floor, requested)
    }

    public static func ceiling(unlimited: Int, limit: Int) -> Int {
        limit > 0 ? min(unlimited, limit) : unlimited
    }
}

/// The client's feedback added up over one stats line: counts summed, times averaged over the
/// reports that carried one (a client sends 0 until its first frame is shown).
public struct FeedbackWindow {
    public private(set) var received = 0
    public private(set) var lost = 0
    public private(set) var recovered = 0
    public private(set) var dropped = 0
    private var timed = 0
    private var totalUs = 0
    private var decodeUs = 0

    public init() {}

    public mutating func add(_ fb: Feedback) {
        received += Int(fb.packetsReceived)
        lost += Int(fb.packetsLost)
        recovered += Int(fb.framesRecovered)
        dropped += Int(fb.framesDropped)
        guard fb.totalUs > 0 else { return }
        totalUs += Int(fb.totalUs)
        decodeUs += Int(fb.decodeUs)
        timed += 1
    }

    public var meanTotalMs: Double { timed == 0 ? 0 : Double(totalUs) / Double(timed) / 1000 }
    public var meanDecodeMs: Double { timed == 0 ? 0 : Double(decodeUs) / Double(timed) / 1000 }
}
