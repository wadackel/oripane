import Foundation

/// Pairing requests in the order they came, the first one shown to the owner and the rest waiting.
/// One address gets one place: from a second, a single phone could fill the queue by itself.
public struct ApprovalQueue<Item> {
    public enum Placement: Equatable { case shown, waiting, full }

    public typealias Shown = (token: UUID, item: Item)

    /// How many may wait behind the one shown.
    public static var maxWaiting: Int { 3 }

    private var entries: [(token: UUID, from: String, item: Item)] = []

    public init() {}

    public var shown: Shown? { entries.first.map { ($0.token, $0.item) } }

    /// The requests behind the one shown.
    public var waiting: Int { max(0, entries.count - 1) }

    public mutating func add(_ token: UUID, from: String, _ item: Item) -> Placement {
        if entries.contains(where: { $0.from == from }) || entries.count > Self.maxWaiting { return .full }
        entries.append((token, from, item))
        return entries.count == 1 ? .shown : .waiting
    }

    /// The owner answered [token]; the next request to show, if any. An answer to a request not shown is ignored.
    public mutating func answered(_ token: UUID) -> Shown? {
        guard entries.first?.token == token else { return nil }
        entries.removeFirst()
        return shown
    }

    /// The phone gave up on [token]; the next request to show when it was the one shown.
    public mutating func withdrawn(_ token: UUID) -> Shown? {
        guard let i = entries.firstIndex(where: { $0.token == token }) else { return nil }
        entries.remove(at: i)
        return i == 0 ? shown : nil
    }
}

/// Addresses whose request the owner denied, refused without asking for a while: otherwise anyone on
/// the LAN, which the host advertises itself to, could put the question on the screen again at once.
public struct PairCooldown {
    public static let seconds: Double = 30

    private var deniedAt: [String: Double] = [:]

    public init() {}

    public mutating func denied(_ host: String, at now: Double) {
        deniedAt = deniedAt.filter { now - $0.value < Self.seconds }
        deniedAt[host] = now
    }

    public func blocks(_ host: String, now: Double) -> Bool {
        deniedAt[host].map { now - $0 < Self.seconds } ?? false
    }
}

/// A paired phone as the menu shows it.
public struct PairedPhone {
    public var id: String
    public var name: String
    public var pairedAt: Date
    public var lastUsedAt: Date?

    public init(id: String, name: String, pairedAt: Date, lastUsedAt: Date?) {
        self.id = id
        self.name = name
        self.pairedAt = pairedAt
        self.lastUsedAt = lastUsedAt
    }
}

/// The Paired Phones submenu, kept apart from AppKit so the wording is testable.
public enum PairedPhoneRows {
    public struct Row: Equatable {
        public var id: String
        public var title: String
        public var details: [String]
        public var connected: Bool
    }

    /// Phones that share a name, as one paired again does, carry the time of pairing in the title too.
    public static func rows(_ phones: [PairedPhone], connectedId: String?, timeZone: TimeZone) -> [Row] {
        let full = formatter("MMM d, yyyy 'at' h:mm a", timeZone)
        let short = formatter("MMM d, h:mm a", timeZone)
        let counts = Dictionary(phones.map { ($0.name, 1) }, uniquingKeysWith: +)
        return phones.map { p in
            Row(
                id: p.id,
                title: counts[p.name, default: 0] > 1 ? "\(p.name) — paired \(short.string(from: p.pairedAt))" : p.name,
                details: [
                    "Paired \(full.string(from: p.pairedAt))",
                    p.lastUsedAt.map { "Last used \(full.string(from: $0))" } ?? "No use recorded",
                ],
                connected: p.id == connectedId
            )
        }
    }

    /// The menu is in English; a Japanese date in it would read as a mix.
    private static func formatter(_ format: String, _ timeZone: TimeZone) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = timeZone
        f.dateFormat = format
        return f
    }
}
