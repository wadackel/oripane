/// What the menu bar app says about the host, kept apart from AppKit so the wording is testable.
public enum HostStatus: Equatable {
    case starting
    /// The host waits for Screen Recording before it listens: a phone it let in would get no picture.
    case needsScreenRecording
    /// Screen Recording was granted while running; capture works only in a process started after it.
    case relaunchNeeded
    case waiting
    /// `mode` is nil until the phone's first stream is set up. `fallback`: match was asked for and
    /// the host streams mirror instead.
    /// `clientId`: the pairing it authenticated with, for marking it among the paired phones.
    case connected(name: String, mode: String?, fallback: Bool = false, clientId: String? = nil)
    /// The last session ended because the host could not keep the video going.
    case streamFailed(reason: String)
    case failed(reason: String)

    public struct Menu: Equatable {
        public var line: String
        public var warnings: [String]
        public var icon: Icon
    }

    public enum Icon: Equatable { case idle, connected, attention }

    /// Privacy permissions the app can read; Local Network has no API to ask.
    public enum Permission: CaseIterable { case screenRecording, accessibility }

    public func menu(missing: Set<Permission>) -> Menu {
        let line: String
        var attention = !missing.isEmpty
        switch self {
        case .starting: line = "Starting…"
        case .needsScreenRecording: line = "Waiting for Screen Recording"; attention = true
        case .relaunchNeeded: line = "Relaunch Oripane to finish setup"; attention = true
        case .waiting: line = "Ready — waiting for a phone"
        case let .connected(name, mode, fallback, _):
            line = "Connected: \(Self.short(name))" + (mode.map { " · \(Self.modeName($0))" + (fallback ? " (Match unavailable)" : "") } ?? "")
        case let .streamFailed(reason): line = "Video stopped — \(reason)"; attention = true
        case let .failed(reason): line = "Not running — \(reason)"; attention = true
        }
        let warnings = Permission.allCases.filter(missing.contains).map { p -> String in
            switch p {
            case .screenRecording: return "Screen Recording is off — open Setup…"
            case .accessibility: return "Accessibility is off — taps stay on the phone"
            }
        }
        let icon: Icon
        if attention { icon = .attention } else if case .connected = self { icon = .connected } else { icon = .idle }
        return Menu(line: line, warnings: warnings, icon: icon)
    }

    private static func modeName(_ mode: String) -> String {
        switch mode {
        case "mirror": return "Mirror"
        case "match": return "Match"
        default: return "Extend"
        }
    }

    /// A phone name is up to 64 characters; a menu that wide pushes the rest of the menu out too.
    private static func short(_ name: String) -> String {
        name.count > 32 ? String(name.prefix(31)) + "…" : name
    }
}
