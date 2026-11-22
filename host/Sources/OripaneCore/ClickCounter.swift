import Foundation

/// The click count macOS expects on a left-button event (`mouseEventClickState`). The client says
/// which press continues the previous click, because only it knows when the finger touched; the
/// host's arrival times are skewed by the input packets it resends.
public struct ClickCounter {
    private var count = 0

    public init() {}

    /// The count for a left-button down: 1 for a plain down, one more than the last for a
    /// continued one, at most 3 (a triple click selects a paragraph; nothing uses more).
    public mutating func down(continued: Bool) -> Int {
        count = continued ? min(3, count + 1) : 1
        return count
    }

    public mutating func rightClick() {
        count = 0
    }
}
