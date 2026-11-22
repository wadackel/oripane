import CVirtualDisplay
import CoreGraphics
import Foundation

/// A display that exists only while this object lives. Created once per session with room for
/// any orientation, then resized in place: destroying a virtual display while WindowServer is
/// still processing its hotplug has aborted WindowServer (SkyLight `GenerateModeListForDisplay`).
///
/// Sized at the client's pixels × 2 / the display size setting (`VirtualDisplaySize`); with `hiDPI`
/// the Mac lays it out at half that size in points so the UI stays readable on a phone-sized panel.
final class VirtualDisplay {
    private static var serial = UInt32(Date().timeIntervalSince1970) & 0xFFFF
    let displayID: CGDirectDisplayID
    let maxPixels: Int
    private(set) var pixelWidth: Int
    private(set) var pixelHeight: Int
    private let display: CGVirtualDisplay

    /// The display is given its first mode in the same step that creates it: a virtual display
    /// whose mode list is momentarily empty is what WindowServer aborted on.
    init?(maxPixels: Int, pixelWidth: Int, pixelHeight: Int, refreshRate: Double, hiDPI: Bool, ppi: Double, name: String = "Oripane") {
        let descriptor = CGVirtualDisplayDescriptor()
        descriptor.queue = DispatchQueue(label: "oripane.virtual-display")
        descriptor.name = name
        descriptor.maxPixelsWide = UInt32(maxPixels)
        descriptor.maxPixelsHigh = UInt32(maxPixels)
        let mm = Double(maxPixels) / ppi * 25.4
        descriptor.sizeInMillimeters = CGSize(width: mm, height: mm)
        descriptor.vendorID = 0xF01D
        descriptor.productID = 0x0001
        // A display created right after the previous one was released, under the same serial,
        // never came online; the system still held the old one under that identity.
        Self.serial &+= 1
        descriptor.serialNum = Self.serial
        descriptor.terminationHandler = { _, _ in log("virtual display terminated by the system") }
        guard let settings = Self.settings(pixelWidth: pixelWidth, pixelHeight: pixelHeight, refreshRate: refreshRate, hiDPI: hiDPI, maxPixels: maxPixels),
              let display = CGVirtualDisplay(descriptor: descriptor) else { return nil }
        // Releasing `display` on failure destroys it again right away.
        guard display.apply(settings) else { return nil }
        self.display = display
        self.displayID = display.displayID
        self.maxPixels = maxPixels
        self.pixelWidth = pixelWidth & ~1
        self.pixelHeight = pixelHeight & ~1
    }

    /// Settings with exactly one mode, or nil when that mode would not fit the descriptor: an
    /// oversized mode is filtered out by WindowServer, which leaves the same empty mode list.
    private static func settings(pixelWidth: Int, pixelHeight: Int, refreshRate: Double, hiDPI: Bool, maxPixels: Int) -> CGVirtualDisplaySettings? {
        let w = pixelWidth & ~1, h = pixelHeight & ~1
        guard w >= 64, h >= 64, w <= maxPixels, h <= maxPixels else { return nil }
        let scale = hiDPI ? 2 : 1
        let settings = CGVirtualDisplaySettings()
        settings.hiDPI = hiDPI ? 1 : 0
        settings.modes = [CGVirtualDisplayMode(width: UInt(w / scale), height: UInt(h / scale), refreshRate: refreshRate)]
        return settings
    }

    /// Switches to a `pixelWidth`×`pixelHeight` backing store, laid out at half that in points
    /// when `hiDPI`.
    func configure(pixelWidth: Int, pixelHeight: Int, refreshRate: Double, hiDPI: Bool) -> Bool {
        // Even dimensions keep the encoder and hardware decoders happy.
        let w = pixelWidth & ~1, h = pixelHeight & ~1
        if w != self.pixelWidth || h != self.pixelHeight {
            guard let settings = Self.settings(pixelWidth: w, pixelHeight: h, refreshRate: refreshRate, hiDPI: hiDPI, maxPixels: maxPixels) else { return false }
            let settled = DisplayReconfiguration.expect(displayID)
            guard display.apply(settings) else { return false }
            settled.wait()
            self.pixelWidth = w
            self.pixelHeight = h
        }
        guard waitUntilOnline() else { return false }
        // The system lists the HiDPI variant of the mode but may activate the 1x one.
        let deadline = Date().addingTimeInterval(3)
        while Date() < deadline {
            if activateMode(hiDPI: hiDPI) { return true }
            Thread.sleep(forTimeInterval: 0.05)
        }
        let opts = [kCGDisplayShowDuplicateLowResolutionModes: kCFBooleanTrue] as CFDictionary
        let listed = ((CGDisplayCopyAllDisplayModes(displayID, opts) as? [CGDisplayMode]) ?? []).map { "\($0.width)x\($0.height)@\($0.pixelWidth)x\($0.pixelHeight)" }
        let cur = CGDisplayCopyDisplayMode(displayID).map { "\($0.width)x\($0.height)@\($0.pixelWidth)x\($0.pixelHeight)" } ?? "none"
        log("display hidpi_mode=unavailable id=\(displayID) current=\(cur) modes=\(listed.joined(separator: ","))")
        return true
    }

    /// The display shows up in CoreGraphics and ScreenCaptureKit asynchronously.
    func waitUntilOnline(timeout: TimeInterval = 5) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if CGDisplayIsOnline(displayID) != 0, CGDisplayPixelsWide(displayID) > 0 { return true }
            Thread.sleep(forTimeInterval: 0.05)
        }
        log("display offline id=\(displayID) online=\(CGDisplayIsOnline(displayID)) width=\(CGDisplayPixelsWide(displayID))")
        return false
    }

    private func activateMode(hiDPI: Bool) -> Bool {
        let opts = [kCGDisplayShowDuplicateLowResolutionModes: kCFBooleanTrue] as CFDictionary
        let modes = (CGDisplayCopyAllDisplayModes(displayID, opts) as? [CGDisplayMode]) ?? []
        let scale = hiDPI ? 2 : 1
        if let cur = CGDisplayCopyDisplayMode(displayID), cur.pixelWidth == pixelWidth, cur.pixelHeight == pixelHeight, cur.width * scale == pixelWidth {
            return true
        }
        guard let mode = modes.first(where: {
            $0.pixelWidth == pixelWidth && $0.pixelHeight == pixelHeight && $0.width * scale == pixelWidth
        }) else { return false }
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success else { return false }
        CGConfigureDisplayWithDisplayMode(config, displayID, mode, nil)
        let settled = DisplayReconfiguration.expect(displayID)
        guard CGCompleteDisplayConfiguration(config, .forSession) == .success else { return false }
        settled.wait()
        return true
    }
}

/// Waits for WindowServer to report that a display finished reconfiguring (mode switch, removal),
/// so the next display change never lands while it is still regenerating mode lists. Falls back
/// to a timeout when no notification arrives.
final class DisplayReconfiguration {
    private static let lock = NSLock()
    private static var waiters: [CGDirectDisplayID: [DisplayReconfiguration]] = [:]
    /// CoreGraphics delivers these notifications on the run loop of the registering thread, so
    /// this runs on the main thread before the application loop starts.
    static func prepare() { _ = registered }

    private static let registered: Void = {
        CGDisplayRegisterReconfigurationCallback({ display, flags, _ in
            guard !flags.contains(.beginConfigurationFlag) else { return }
            DisplayReconfiguration.lock.lock()
            let ws = DisplayReconfiguration.waiters.removeValue(forKey: display) ?? []
            DisplayReconfiguration.lock.unlock()
            ws.forEach { $0.done.signal() }
        }, nil)
    }()

    private let done = DispatchSemaphore(value: 0)
    private let displayID: CGDirectDisplayID

    private init(_ id: CGDirectDisplayID) { displayID = id }

    /// Call before the change, so a fast notification is not missed.
    static func expect(_ id: CGDirectDisplayID) -> DisplayReconfiguration {
        _ = registered
        let w = DisplayReconfiguration(id)
        lock.lock(); waiters[id, default: []].append(w); lock.unlock()
        return w
    }

    @discardableResult
    func wait(timeout: TimeInterval = 3) -> Bool {
        let ok = done.wait(timeout: .now() + timeout) == .success
        if !ok {
            Self.lock.lock(); Self.waiters[displayID]?.removeAll { $0 === self }; Self.lock.unlock()
            log("display reconfiguration_timeout id=\(displayID)")
        }
        return ok
    }
}

extension VirtualDisplay {
    /// Makes `display` a mirror of `master`, or ends its mirroring when `master` is
    /// `kCGNullDirectDisplay`, and waits for WindowServer to finish: a change stacked on one still
    /// in progress is what aborted it. False only when CoreGraphics refuses the configuration; a
    /// wait that times out is logged and counts as done. `ORIPANE_FAKE_MATCH=0` refuses every
    /// mirror (not its end), to exercise the fallback.
    static func mirror(_ display: CGDirectDisplayID, of master: CGDirectDisplayID) -> Bool {
        if master != kCGNullDirectDisplay, ProcessInfo.processInfo.environment["ORIPANE_FAKE_MATCH"] == "0" { return false }
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success else { return false }
        guard CGConfigureDisplayMirrorOfDisplay(config, display, master) == .success else {
            CGCancelDisplayConfiguration(config)
            return false
        }
        let settled = DisplayReconfiguration.expect(display)
        guard CGCompleteDisplayConfiguration(config, .forSession) == .success else { return false }
        settled.wait()
        return true
    }

    /// Destroys the display and waits for WindowServer to finish removing it.
    /// `display` must hold the last strong reference.
    static func release(_ display: inout VirtualDisplay?) {
        guard let id = display?.displayID else { return }
        // Another reference would destroy the display later, unannounced, possibly while the
        // next one is being created: the sequence that aborted WindowServer.
        precondition(isKnownUniquelyReferenced(&display), "virtual display released while still referenced")
        let settled = DisplayReconfiguration.expect(id)
        display = nil
        settled.wait()
    }
}
