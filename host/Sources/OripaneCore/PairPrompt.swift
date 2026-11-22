import Foundation

/// How a pairing request reaches the owner, on the host's terminal or in the app's dialog.
public enum PairPrompt {
    /// What the owner reads before answering a request on the terminal. The code comes first on a
    /// line of its own: printed after the name on one line, a name such as
    /// `Fold"? Check that the phone shows 111111` would show its code before the real one.
    public static func question(name: String, code: String, from host: String, waiting: Int) -> String {
        "\nPairing request: the phone must show \(code)\n  from \"\(printable(name))\"\n  \(origin(host))\n"
            + (waitingLine(waiting).map { "  \($0)\n" } ?? "") + "Pair? [y/N] "
    }

    /// Where a request came from: the address and whether that is the LAN or Tailscale. Phones
    /// connect over IPv4, so a tailnet address is one in 100.64.0.0/10.
    public static func origin(_ host: String) -> String {
        if host == "::1" || host.hasPrefix("127.") { return "From this Mac" }
        return "From \(host) · \(Tailnet.inRange(host) ? "Tailscale" : "LAN")"
    }

    public static func waitingLine(_ count: Int) -> String? {
        switch count {
        case ..<1: return nil
        case 1: return "1 more phone is waiting."
        default: return "\(count) more phones are waiting."
        }
    }

    /// A newline would split a line of the log, and an escape sequence would rewrite the owner's
    /// terminal. The line and paragraph separators are neither control nor format characters, yet
    /// break a line in the dialog.
    public static func printable(_ text: String) -> String {
        String(String.UnicodeScalarView(text.unicodeScalars.filter {
            !CharacterSet.controlCharacters.contains($0) && $0 != "\u{2028}" && $0 != "\u{2029}"
        }))
    }
}
