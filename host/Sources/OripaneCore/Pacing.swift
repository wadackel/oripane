/// How a frame's packets go out. One larger than a few dozen packets (a keyframe) goes in runs of
/// 16 spread over the given time: all at once it overruns the Wi-Fi queue and loses packets in a
/// row, which parity repairs only one per group of.
public enum Pacing {
    public static let run = 16

    /// The pause after each run but the last, in microseconds; 0 sends everything at once.
    public static func pauseUs(packets: Int, spreadUs: Int) -> Int {
        guard packets > 2 * run, spreadUs > 0 else { return 0 }
        let runs = (packets + run - 1) / run
        return spreadUs / (runs - 1)
    }
}
