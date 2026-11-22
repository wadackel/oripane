import CoreGraphics

/// Android `KeyEvent` keycodes to macOS virtual key codes (ANSI layout). The client sends raw
/// keycodes so the mapping lives next to the only platform that needs it.
enum Keymap {
    private static let letters: [CGKeyCode] = [
        0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E, // A-M
        0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06, // N-Z
    ]
    private static let digits: [CGKeyCode] = [0x1D, 0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19]
    private static let functionKeys: [CGKeyCode] = [0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F]
    private static let table: [Int: CGKeyCode] = [
        66: 0x24, 160: 0x4C, // ENTER, NUMPAD_ENTER
        67: 0x33, 112: 0x75, // DEL (backspace), FORWARD_DEL
        61: 0x30, 62: 0x31, 111: 0x35, // TAB, SPACE, ESCAPE
        19: 0x7E, 20: 0x7D, 21: 0x7B, 22: 0x7C, // DPAD up/down/left/right
        122: 0x73, 123: 0x77, 92: 0x74, 93: 0x79, // HOME, END, PAGE_UP, PAGE_DOWN
        55: 0x2B, 56: 0x2F, 69: 0x1B, 70: 0x18, 71: 0x21, 72: 0x1E, // , . - = [ ]
        73: 0x2A, 74: 0x29, 75: 0x27, 76: 0x2C, 68: 0x32, // \ ; ' / `
        59: 0x38, 60: 0x3C, 113: 0x3B, 114: 0x3E, 57: 0x3A, 58: 0x3D, // shift, ctrl, alt (L/R)
        117: 0x37, 118: 0x36, 115: 0x39, // META (command) L/R, CAPS_LOCK
    ]

    static func macKeyCode(android code: Int) -> CGKeyCode? {
        switch code {
        case 29...54: return letters[code - 29]
        case 7...16: return digits[code - 7]
        case 131...142: return functionKeys[code - 131]
        default: return table[code]
        }
    }

    static func flags(androidMeta meta: Int) -> CGEventFlags {
        var f: CGEventFlags = []
        if meta & 0x1 != 0 { f.insert(.maskShift) }
        if meta & 0x2 != 0 { f.insert(.maskAlternate) }
        if meta & 0x1000 != 0 { f.insert(.maskControl) }
        if meta & 0x10000 != 0 { f.insert(.maskCommand) }
        return f
    }
}
