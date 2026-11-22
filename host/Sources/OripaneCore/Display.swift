import Foundation

/// What a `display` message asks for, settled against the host's launch options.
public struct DisplayRequest: Equatable {
    public var scale: Double
    public var fps: Int
    public var uiScale: Double
    /// The panel's density, which sizes the virtual display in millimetres.
    public var dpi: Double
    public var requestedScale: Double?
    public var requestedFps: Double?
    public var requestedUiScale: Double?
    public var scaleForced: Bool
    public var fpsForced: Bool
    /// `"hevc"` when the phone offers it in `codecs`, `"h264"` otherwise.
    public var codec: String
    public var codecForced: Bool

    /// `--scale`, `--fps` and `--codec` win over the phone's settings, so a test run can pin them.
    /// Every value is clamped to its range, the launch options included.
    public static func resolve(_ m: [String: Any], forcedScale: Double?, forcedFps: Int?, forcedCodec: String? = nil) -> DisplayRequest {
        let scale = number(m["scale"]), fps = number(m["fps"]), ui = number(m["uiScale"])
        // Clamped as a Double: `Int(_:)` traps on a value outside Int's range.
        let f = min(120, max(1, forcedFps.map(Double.init) ?? fps ?? 60))
        return DisplayRequest(
            scale: min(1.0, max(0.25, forcedScale ?? scale ?? 1.0)),
            fps: Int(f.rounded()),
            uiScale: min(2.0, max(1.5, ui ?? 2.0)),
            dpi: min(1000, max(72, number(m["dpi"]) ?? 420)).rounded(),
            requestedScale: scale,
            requestedFps: fps,
            requestedUiScale: ui,
            scaleForced: forcedScale != nil,
            fpsForced: forcedFps != nil,
            codec: forcedCodec ?? ((m["codecs"] as? [String])?.contains("hevc") == true ? "hevc" : "h264"),
            codecForced: forcedCodec != nil
        )
    }

    /// org.json writes 1.0 as `1`, so any number counts; a JSON boolean also bridges to NSNumber
    /// and is treated as absent.
    static func number(_ v: Any?) -> Double? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return nil }
        return n.doubleValue
    }
}

/// The size of the virtual display for a phone panel. It is made HiDPI (2x), so the Mac lays it
/// out at half its pixels; a `uiScale` below 2 gives more room by making it larger than the panel,
/// and capture scales it back down to the panel.
public enum VirtualDisplaySize {
    public static func pixels(clientWidth: Int, clientHeight: Int, uiScale: Double) -> (width: Int, height: Int) {
        (even(Double(clientWidth) * 2 / uiScale), even(Double(clientHeight) * 2 / uiScale))
    }

    /// The largest side the display may be resized to without being recreated. The floor scales
    /// with `uiScale` too, so either panel of a foldable fits whichever one the session began on.
    public static func side(clientWidth: Int, clientHeight: Int, uiScale: Double) -> Int {
        let v = max(Double(max(clientWidth, clientHeight)), 2560) * 2 / uiScale
        let up = Int(v.rounded(.up))
        return up + (up & 1)
    }

    static func even(_ v: Double) -> Int { max(2, Int(v.rounded()) & ~1) }
}
