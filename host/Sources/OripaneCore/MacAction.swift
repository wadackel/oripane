import Foundation

/// A Mac feature the client asks for by name (`system` control message), and how the host carries
/// it out. Flags are raw `CGEventFlags` values, so the table stays testable without CoreGraphics.
public enum MacAction: String, CaseIterable {
    case missionControl, appExpose, spaceLeft, spaceRight, showDesktop, apps, launcher, screenshot, lockScreen, previousApp
    case volumeUp, volumeDown, mute, playPause, nextTrack, previousTrack, brightnessUp, brightnessDown

    public enum Plan: Equatable {
        /// A virtual key pressed with the modifier keys that `flags` names held around it.
        case shortcut(key: UInt16, flags: UInt64)
        /// An `NX_KEYTYPE_*` media key.
        case media(Int)
        /// An application bundle to open.
        case open(String)
    }

    public enum Flag {
        public static let shift: UInt64 = 0x20000
        public static let control: UInt64 = 0x40000
        public static let option: UInt64 = 0x80000
        public static let command: UInt64 = 0x100000
        /// The default Mission Control and Spaces shortcuts are registered with it, because a
        /// hardware arrow or F key carries it; a synthesized key has to carry it too.
        public static let fn: UInt64 = 0x800000
    }

    public var plan: Plan {
        switch self {
        case .missionControl: .shortcut(key: 0x7E, flags: Flag.control | Flag.fn)
        case .appExpose: .shortcut(key: 0x7D, flags: Flag.control | Flag.fn)
        case .spaceLeft: .shortcut(key: 0x7B, flags: Flag.control | Flag.fn)
        case .spaceRight: .shortcut(key: 0x7C, flags: Flag.control | Flag.fn)
        case .showDesktop: .shortcut(key: 0x67, flags: Flag.fn)
        case .apps: .open("/System/Applications/Apps.app")
        case .launcher: .shortcut(key: 0x31, flags: Flag.command)
        case .screenshot: .shortcut(key: 0x17, flags: Flag.command | Flag.shift)
        case .lockScreen: .shortcut(key: 0x0C, flags: Flag.control | Flag.command)
        case .previousApp: .shortcut(key: 0x30, flags: Flag.command)
        case .volumeUp: .media(0)
        case .volumeDown: .media(1)
        case .mute: .media(7)
        case .playPause: .media(16)
        case .nextTrack: .media(17)
        case .previousTrack: .media(18)
        case .brightnessUp: .media(2)
        case .brightnessDown: .media(3)
        }
    }
}
