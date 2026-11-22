import AppKit
import ApplicationServices
import CoreGraphics
import OripaneCore
import Foundation

/// Whether events may reach the Mac. The app clears it while Accessibility is off and sets it as
/// soon as it is granted: a flag fixed at launch would need a relaunch after every grant.
enum InputPermission {
    private static let lock = NSLock()
    private static var value = true
    static var granted: Bool {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }
}

/// Turns client input into macOS events on the streamed display. Coordinates arrive normalized to
/// the stream and are mapped through the display's global bounds in points.
///
/// Events are posted from a queue of their own: a CoreGraphics call that blocks (WindowServer
/// restarting, a session without a console) must not stall video and control with it.
final class InputInjector {
    let displayID: CGDirectDisplayID
    private let alwaysDryRun: Bool
    private var dryRun: Bool { alwaysDryRun || !InputPermission.granted }
    /// Mac points per client pixel, for turning finger travel into scroll distance.
    var scrollScale: Double = 0.5
    private let queue = DispatchQueue(label: "oripane.input", qos: .userInteractive)
    private lazy var source = CGEventSource(stateID: .hidSystemState)
    private var buttonDown = false
    /// The keys that went down through `key` and have not come up yet.
    private var held = HeldKeys()
    private var clicks = ClickCounter()
    /// The click count of the press in progress, carried by its drags and its release.
    private var clickState = 1
    private var scrollCarry = ScrollAccumulator()
    // Moves and scrolls are counted rather than logged: a drag sends over a hundred a second, which
    // would bury the log and keep the finger's path on disk.
    private let countLock = NSLock()
    private var moves = 0
    private var scrolls = 0

    init(displayID: CGDirectDisplayID, dryRun: Bool) {
        self.displayID = displayID
        alwaysDryRun = dryRun
    }

    /// `events` are new ones only: the session drops the repeats, since this injector lasts one pipeline.
    func apply(_ events: [SequencedInput]) {
        queue.async { [self] in
            for e in events {
                if e.event.isFinite { handle(e.event) } else { log("input dropped non_finite") }
            }
        }
    }

    func key(androidCode: Int, down: Bool, meta: Int) {
        queue.async { [self] in
            guard let code = Keymap.macKeyCode(android: androidCode) else {
                log("input key_unmapped android=\(androidCode)")
                return
            }
            let flags = Keymap.flags(androidMeta: meta).rawValue
            let strokes = down ? held.press(code, flags: flags) : held.release(code, flags: flags)
            // No key codes: the app keeps this log on disk, and they would spell out a password.
            log("input key down=\(down) modifiers=\(strokes.count - 1)\(suffix)")
            guard !dryRun else { return }
            strokes.forEach(post)
        }
    }

    private func post(_ stroke: KeyStroke) {
        let e = CGEvent(keyboardEventSource: source, virtualKey: stroke.code, keyDown: stroke.down)
        e?.flags = CGEventFlags(rawValue: stroke.flags)
        e?.post(tap: .cghidEventTap)
    }

    func text(_ s: String) {
        queue.async { [self] in
            log("input text length=\(s.count)\(suffix)")
            guard !dryRun else { return }
            for chunk in UnicodeChunks.split(s) {
                for down in [true, false] {
                    let e = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down)
                    chunk.withUnsafeBufferPointer { e?.keyboardSetUnicodeString(stringLength: $0.count, unicodeString: $0.baseAddress) }
                    e?.post(tap: .cghidEventTap)
                }
            }
        }
    }

    /// Lets go of the button and the keys still held. Nothing else would: a session that ends
    /// mid-drag leaves WindowServer holding the button, and the Mac's own mouse then drags.
    func releaseAll() {
        queue.async { [self] in
            let button = buttonDown, keys = held.releaseAll()
            buttonDown = false
            guard button || !keys.isEmpty else { return }
            log("input release button=\(button ? 1 : 0) keys=\(keys.count)\(suffix)")
            guard !dryRun else { return }
            if button, let at = CGEvent(source: nil)?.location {
                CGEvent(mouseEventSource: source, mouseType: .leftMouseUp, mouseCursorPosition: at, mouseButton: .left)?.post(tap: .cghidEventTap)
            }
            keys.forEach(post)
        }
    }

    /// A Mac feature by its `MacAction` name, on the streamed display.
    func system(_ name: String) {
        queue.async { [self] in
            guard let action = MacAction(rawValue: name) else {
                log("input system_unknown action=\(name)")
                return
            }
            log("input system action=\(name)\(suffix)")
            guard !dryRun else { return }
            // A Space switch or a launched app should land on the phone's display, not on the one
            // where the Mac's own mouse left the cursor.
            bringCursor()
            switch action.plan {
            case let .shortcut(key, flags): shortcut(key, CGEventFlags(rawValue: flags))
            case let .media(type): media(type)
            case let .open(path): NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: path), configuration: NSWorkspace.OpenConfiguration())
            }
        }
    }

    private func bringCursor() {
        let b = CGDisplayBounds(displayID)
        guard let at = CGEvent(source: nil)?.location, !b.contains(at) else { return }
        CGEvent(mouseEventSource: source, mouseType: .mouseMoved, mouseCursorPosition: CGPoint(x: b.midX, y: b.midY), mouseButton: .left)?.post(tap: .cghidEventTap)
    }

    /// The modifier keys go down and up around the key, as a hand would press them: the app
    /// switcher only chooses when Command is released.
    private func shortcut(_ key: CGKeyCode, _ flags: CGEventFlags) {
        var keys = HeldKeys()
        (keys.press(key, flags: flags.rawValue) + keys.release(key, flags: flags.rawValue)).forEach(post)
        // No key releases fn here, so its flag would stay in the system's state and ride on every
        // later key and click. A press and release of the fn key itself could fire the Globe key.
        if flags.contains(.maskSecondaryFn) {
            let e = CGEvent(source: source)
            e?.type = .flagsChanged
            e?.flags = []
            e?.post(tap: .cghidEventTap)
        }
    }

    /// A media key is a system-defined event, not a key code: `CGEvent` has no initializer for it.
    private func media(_ type: Int) {
        for down in [true, false] {
            let state = down ? 0xA : 0xB
            NSEvent.otherEvent(
                with: .systemDefined, location: .zero, modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(state << 8)),
                timestamp: 0, windowNumber: 0, context: nil, subtype: 8, data1: type << 16 | state << 8, data2: -1
            )?.cgEvent?.post(tap: .cghidEventTap)
        }
    }

    private var suffix: String { dryRun ? " dry_run=1" : "" }

    /// Pointer moves and drags, and scroll events, since the last call.
    func takeCounts() -> (moves: Int, scrolls: Int) {
        countLock.lock(); defer { countLock.unlock() }
        defer { moves = 0; scrolls = 0 }
        return (moves, scrolls)
    }

    private func count(moves m: Int = 0, scrolls n: Int = 0) {
        countLock.lock(); moves += m; scrolls += n; countLock.unlock()
    }

    private func handle(_ event: InputEvent) {
        switch event {
        case let .pointer(action, x, y):
            let type: CGEventType
            switch action {
            case .move: type = buttonDown ? .leftMouseDragged : .mouseMoved
            case .down, .downContinued:
                type = .leftMouseDown
                buttonDown = true
                clickState = clicks.down(continued: action == .downContinued)
            case .drag: type = .leftMouseDragged
            case .up: type = .leftMouseUp; buttonDown = false
            }
            let counted = action == .down || action == .downContinued || action == .up
            if !counted { count(moves: 1) }
            guard !dryRun else {
                log(String(format: "input pointer action=%@ nx=%.3f ny=%.3f%@ dry_run=1", "\(action)", x, y, counted ? " click_state=\(clickState)" : ""))
                return
            }
            let p = point(x, y)
            // Presses only, and no position: the app keeps this log on disk, and the taps would map
            // out a PIN pad on the screen.
            if counted { log("input pointer action=\(action) click_state=\(clickState)") }
            let e = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: p, mouseButton: .left)
            // Without it every press is a first click, and two taps never make a double click.
            if type != .mouseMoved { e?.setIntegerValueField(.mouseEventClickState, value: Int64(clickState)) }
            e?.post(tap: .cghidEventTap)
        case let .scroll(dx, dy):
            let (sx, sy) = scrollCarry.take(Double(dx) * scrollScale, Double(dy) * scrollScale)
            guard sx != 0 || sy != 0 else { return }
            count(scrolls: 1)
            guard !dryRun else {
                log("input scroll dx=\(sx) dy=\(sy) dry_run=1")
                return
            }
            CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2, wheel1: sy, wheel2: sx, wheel3: 0)?.post(tap: .cghidEventTap)
        case let .rightClick(x, y):
            clicks.rightClick()
            guard !dryRun else {
                log(String(format: "input right_click nx=%.3f ny=%.3f dry_run=1", x, y))
                return
            }
            let p = point(x, y)
            log("input right_click")
            CGEvent(mouseEventSource: source, mouseType: .rightMouseDown, mouseCursorPosition: p, mouseButton: .right)?.post(tap: .cghidEventTap)
            CGEvent(mouseEventSource: source, mouseType: .rightMouseUp, mouseCursorPosition: p, mouseButton: .right)?.post(tap: .cghidEventTap)
        }
    }

    private func point(_ x: Float, _ y: Float) -> CGPoint {
        let b = CGDisplayBounds(displayID)
        let cx = min(max(CGFloat(x), 0), 1), cy = min(max(CGFloat(y), 0), 1)
        return CGPoint(x: b.minX + cx * (b.width - 1), y: b.minY + cy * (b.height - 1))
    }
}
