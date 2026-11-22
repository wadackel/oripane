import Foundation

/// Typed text cut into the UTF-16 runs a key event carries. Each run ends on a `Character`
/// boundary: a run that ended between the halves of a surrogate pair would type two broken halves.
public enum UnicodeChunks {
    /// Runs of at most `maxUnits` UTF-16 units, except a single `Character` longer than that (a long
    /// combining sequence), which goes as a run of its own.
    public static func split(_ s: String, maxUnits: Int = 20) -> [[UInt16]] {
        var runs: [[UInt16]] = []
        var run: [UInt16] = []
        for c in s {
            let units = Array(String(c).utf16)
            if !run.isEmpty, run.count + units.count > maxUnits {
                runs.append(run)
                run = []
            }
            run += units
        }
        if !run.isEmpty { runs.append(run) }
        return runs
    }
}

/// Finger travel on the phone, in pixels of the video frame, to Mac scroll points.
public enum ScrollScale {
    /// Points per frame pixel. The frame is the stream fitted inside the panel: in mirror it is
    /// letterboxed, so the panel's own height would make content move slower than the finger.
    public static func pointsPerPixel(displayHeight: Double, clientWidth: Int, clientHeight: Int, streamWidth: Int, streamHeight: Int) -> Double {
        let frameHeight = min(Double(clientHeight), Double(clientWidth) * Double(streamHeight) / Double(max(1, streamWidth)))
        return displayHeight / max(1, frameHeight)
    }
}

/// Whole scroll points to post, with each axis's fraction carried into the next event: rounding
/// every event on its own lost a slow finger entirely and turned a vertical swipe's small sideways
/// part into a point per event.
public struct ScrollAccumulator {
    private var x = 0.0
    private var y = 0.0

    public init() {}

    public mutating func take(_ dx: Double, _ dy: Double) -> (Int32, Int32) {
        x += dx
        y += dy
        let sx = Self.whole(x), sy = Self.whole(y)
        x -= Double(sx)
        y -= Double(sy)
        // Only a rounding remainder carries: what a clamp cut off must not keep scrolling afterwards.
        if abs(x) >= 1 { x = 0 }
        if abs(y) >= 1 { y = 0 }
        return (sx, sy)
    }

    // Clamped as a Double: `Int32(_:)` traps on a value outside its range.
    private static func whole(_ v: Double) -> Int32 {
        Int32(min(Double(Int32.max), max(Double(Int32.min), v.rounded())))
    }
}
