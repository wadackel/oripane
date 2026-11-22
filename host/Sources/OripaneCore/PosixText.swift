import Foundation

/// A POSIX error number in words: a sentence for the menu that says what to do, and a short name
/// for a `key=value` log line. Swift's own description (`POSIXErrorCode(rawValue: 54): …`) is
/// neither.
public enum PosixText {
    public static func menu(_ code: Int32, port: UInt16? = nil) -> String {
        switch code {
        case EADDRINUSE: return port.map { "Port \($0) is in use" } ?? "The port is in use"
        case ENETDOWN, ENETUNREACH: return "The network is down — check Wi-Fi"
        case EACCES, EPERM: return "macOS refused the network access — check Privacy & Security"
        default: return String(cString: strerror(code))
        }
    }

    public static func slug(_ code: Int32) -> String {
        switch code {
        case ECONNRESET: return "connection_reset"
        case ETIMEDOUT: return "timed_out"
        case ENETDOWN: return "network_down"
        case ENETUNREACH: return "network_unreachable"
        case EHOSTUNREACH: return "host_unreachable"
        case EPIPE: return "broken_pipe"
        case ECONNREFUSED: return "connection_refused"
        case ECONNABORTED: return "connection_aborted"
        case EADDRINUSE: return "address_in_use"
        default: return "posix"
        }
    }
}
