import Darwin
import Foundation

/// Watches awdl0. While AWDL is up, the Wi-Fi radio leaves the infrastructure channel every few
/// seconds and the stream sees 40-75ms stalls; the client shows a warning instead of guessing.
final class AwdlGuard {
    private(set) var active = false
    var onChange: ((Bool) -> Void)?
    private var timer: DispatchSourceTimer?

    func start() {
        active = Self.isUp()
        log("awdl active=\(active)")
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "oripane.awdl"))
        t.schedule(deadline: .now() + 2, repeating: 2)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            let now = Self.isUp()
            if now != self.active {
                self.active = now
                self.onChange?(now)
            }
        }
        t.resume()
        timer = t
    }

    static func isUp() -> Bool {
        // Test hooks: turning AWDL on or off for real needs sudo.
        switch ProcessInfo.processInfo.environment["ORIPANE_FAKE_AWDL"] {
        case "1": return true
        case "0": return false
        default: break
        }
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return false }
        defer { freeifaddrs(head) }
        var p = head
        while let cur = p {
            if String(cString: cur.pointee.ifa_name) == "awdl0" {
                let flags = Int32(cur.pointee.ifa_flags)
                if flags & IFF_UP != 0 && flags & IFF_RUNNING != 0 { return true }
            }
            p = cur.pointee.ifa_next
        }
        return false
    }
}
