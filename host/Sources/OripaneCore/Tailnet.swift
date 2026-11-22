import Foundation

public enum Tailnet {
    /// The Mac's tailnet addresses among its interfaces: IPv4 in 100.64.0.0/10 on a `utun`
    /// interface. IPv4 only, because the media socket is `AF_INET`.
    public static func addresses(_ interfaces: [(name: String, ipv4: String)]) -> [String] {
        var out: [String] = []
        for (name, addr) in interfaces where name.hasPrefix("utun") && inRange(addr) && !out.contains(addr) {
            out.append(addr)
        }
        return out
    }

    static func inRange(_ ipv4: String) -> Bool {
        let parts = ipv4.split(separator: ".", omittingEmptySubsequences: false).map { UInt8($0) }
        guard parts.count == 4, let a = parts[0], let b = parts[1], parts.allSatisfy({ $0 != nil }) else { return false }
        return a == 100 && (b & 0xC0) == 64
    }
}
