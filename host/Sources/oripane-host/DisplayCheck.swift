import CoreGraphics
import Foundation

/// Exercises the virtual-display lifecycle one step at a time, so that after a macOS update the
/// private API can be checked in isolation before a phone ever connects. Each step is meant to
/// run in its own process while WindowServer is watched.
enum DisplayCheck {
    static func run(_ step: String) async -> Bool {
        switch step {
        case "create":
            return await withDisplay { d in
                log("check created id=\(d.displayID) online=\(d.waitUntilOnline())")
                return true
            }
        case "stream":
            return await withDisplay { d in await captures(d) }
        case "resize":
            return await withDisplay { d in
                for (w, h) in [(2400, 1080), (1080, 2400)] {
                    let ok = d.configure(pixelWidth: w, pixelHeight: h, refreshRate: 60, hiDPI: true)
                    let b = CGDisplayBounds(d.displayID)
                    log("check resized to=\(w)x\(h) ok=\(ok) points=\(Int(b.width))x\(Int(b.height))")
                    if !ok { return false }
                }
                return true
            }
        case "recreate":
            for i in 1...5 {
                guard await withDisplay({ d in log("check round=\(i) id=\(d.displayID)"); return true }) else { return false }
            }
            return true
        case "match":
            let physical = CGMainDisplayID()
            let before = modeName(physical)
            let ok = await withDisplay { d in
                // Unmirrored on every way out: `withDisplay` releases the display as soon as this returns.
                defer { unmirror(physical) }
                guard VirtualDisplay.mirror(physical, of: d.displayID) else { log("check mirror_failed"); return false }
                log("check mirrored physical=\(physical) virtual=\(d.displayID) main=\(CGMainDisplayID())")
                guard await captures(d) else { return false }
                // Resized while mirrored, the physical display dropped to 800x600, so each size is
                // unmirrored, configured, then mirrored again.
                for (w, h) in [(2400, 1080), (1080, 2400)] {
                    unmirror(physical)
                    let ok = d.configure(pixelWidth: w, pixelHeight: h, refreshRate: 60, hiDPI: true) && VirtualDisplay.mirror(physical, of: d.displayID)
                    let b = CGDisplayBounds(d.displayID)
                    log("check rematched to=\(w)x\(h) ok=\(ok) points=\(Int(b.width))x\(Int(b.height))")
                    if !ok { return false }
                }
                return true
            }
            let after = modeName(physical)
            let mirrored = CGDisplayIsInMirrorSet(physical) != 0
            log("check main before=\(before) after=\(after) mirrored=\(mirrored ? 1 : 0)")
            return ok && before == after && !mirrored
        default:
            return false
        }
    }

    /// Whether the display delivers at least one frame in two seconds.
    private static func captures(_ d: VirtualDisplay) async -> Bool {
        let source = ScreenSource(displayID: d.displayID, width: d.pixelWidth, height: d.pixelHeight, fps: 60)
        let frames = Counter()
        source.onFrame = { _ in frames.add() }
        do { try await source.start() } catch { log("check capture_failed error=\(error)"); return false }
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        await source.stop()
        log("check captured frames=\(frames.value)")
        return frames.value > 0
    }

    private static func unmirror(_ id: CGDirectDisplayID) {
        if CGDisplayIsInMirrorSet(id) != 0, !VirtualDisplay.mirror(id, of: kCGNullDirectDisplay) { log("check unmirror_failed") }
    }

    private static func modeName(_ id: CGDirectDisplayID) -> String {
        guard let m = CGDisplayCopyDisplayMode(id) else { return "none" }
        return "\(m.width)x\(m.height)@\(m.pixelWidth)x\(m.pixelHeight)"
    }

    /// `body` must not keep the display: `release` only destroys it once the last reference is gone.
    private static func withDisplay(_ body: (VirtualDisplay) async -> Bool) async -> Bool {
        var d = VirtualDisplay(maxPixels: 2400, pixelWidth: 1080, pixelHeight: 2400, refreshRate: 60, hiDPI: true, ppi: 420)
        guard d?.configure(pixelWidth: 1080, pixelHeight: 2400, refreshRate: 60, hiDPI: true) == true else {
            log("check create_failed")
            VirtualDisplay.release(&d)
            return false
        }
        let ok = await body(d!)
        VirtualDisplay.release(&d)
        log("check released")
        return ok
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    var value: Int { lock.lock(); defer { lock.unlock() }; return n }
    func add() { lock.lock(); n += 1; lock.unlock() }
}
