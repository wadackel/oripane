import Foundation

/// A key event as the host posts it: a Mac virtual key code, with raw `CGEventFlags`.
public struct KeyStroke: Equatable {
    public let code: UInt16
    public let down: Bool
    public let flags: UInt64

    public init(_ code: UInt16, down: Bool, flags: UInt64) {
        self.code = code
        self.down = down
        self.flags = flags
    }
}

/// The keys the host holds down on the Mac, and the key events that press and release them.
///
/// A modifier flag set on a key event alone stays in the system's modifier state after the key is
/// released, and every later click and key then carries it. So a flag no held key carries gets its
/// modifier key pressed before the key and released after it, as a hand would.
public struct HeldKeys {
    /// Each modifier with the Mac key codes that hold it, the left one first, in the order pressed.
    private static let modifiers: [(flag: UInt64, codes: [UInt16])] = [
        (MacAction.Flag.command, [0x37, 0x36]),
        (MacAction.Flag.shift, [0x38, 0x3C]),
        (MacAction.Flag.option, [0x3A, 0x3D]),
        (MacAction.Flag.control, [0x3B, 0x3E]),
    ]
    private static let modifierFlags = modifiers.reduce(0) { $0 | $1.flag }

    public private(set) var down: Set<UInt16> = []
    /// Modifier keys pressed here for a flag rather than sent by the client, in the order pressed.
    /// They are one list, not one per key: a key repeat would otherwise lose them, and of two
    /// overlapping keys the first released would leave the other's release carrying a lone flag.
    private var added: [UInt16] = []

    public init() {}

    /// A key down with `flags`; the key itself carries its own flag, as a physical modifier key's does.
    public mutating func press(_ code: UInt16, flags: UInt64) -> [KeyStroke] {
        var strokes: [KeyStroke] = []
        for m in Self.modifiers where flags & m.flag != 0 && !m.codes.contains(code) && down.isDisjoint(with: m.codes) {
            down.insert(m.codes[0])
            if !added.contains(m.codes[0]) { added.append(m.codes[0]) }
            strokes.append(KeyStroke(m.codes[0], down: true, flags: held(flags)))
        }
        down.insert(code)
        strokes.append(KeyStroke(code, down: true, flags: flags))
        return strokes
    }

    /// A key up, and the added modifier keys once no key but modifiers is down.
    public mutating func release(_ code: UInt16, flags: UInt64) -> [KeyStroke] {
        down.remove(code)
        var strokes = [KeyStroke(code, down: false, flags: flags)]
        guard down.allSatisfy(Self.isModifier) else { return strokes }
        for key in added.reversed() where down.contains(key) {
            down.remove(key)
            strokes.append(KeyStroke(key, down: false, flags: held(flags)))
        }
        added = []
        return strokes
    }

    /// Every key still down, the added modifier keys last, with no flags.
    public mutating func releaseAll() -> [KeyStroke] {
        let keys = down.subtracting(added).sorted() + added.reversed().filter(down.contains)
        down = []
        added = []
        return keys.map { KeyStroke($0, down: false, flags: 0) }
    }

    private static func isModifier(_ code: UInt16) -> Bool { modifiers.contains { $0.codes.contains(code) } }

    /// The bits of `flags` that are not modifiers (fn), with the modifiers whose keys are down.
    private func held(_ flags: UInt64) -> UInt64 {
        Self.modifiers.reduce(flags & ~Self.modifierFlags) { f, m in down.isDisjoint(with: m.codes) ? f : f | m.flag }
    }
}
