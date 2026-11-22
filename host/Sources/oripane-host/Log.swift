import Foundation
import Network
import OripaneCore

private let logQueue = DispatchQueue(label: "oripane.log")
private let stamp: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    return f
}()
private var logFile: FileHandle?
private var logURL: URL?
private var logSize = 0
private let maxLogSize = 10_000_000
/// Registered by the first line: a command that logs its reason and exits must not lose that line.
private let drainOnExit: Void = { atexit { logQueue.sync {} } }()

/// Structured `event key=value` lines on stderr, so a run can be checked with grep rather than read.
/// Wall-clock stamps line up with `adb logcat` from the client.
///
/// Written asynchronously: the caller may be the input queue in the middle of a drag, and a write
/// to the disk it waited on would delay the next event. `exit` drains what is still queued.
func log(_ message: String) {
    _ = drainOnExit
    let line = Data((stamp.string(from: Date()) + " " + message + "\n").utf8)
    logQueue.async {
        FileHandle.standardError.write(line)
        guard let file = logFile else { return }
        file.write(line)
        logSize += line.count
        // Checked as it grows, not only at launch: a login item runs for weeks.
        if logSize > maxLogSize, let url = logURL { openLog(url) }
    }
}

/// Where the app writes its log: an app opened from Finder or at login has no terminal for stderr.
/// A test copy keeps its log with its state, away from the owner's.
func appLogURL() -> URL {
    if ProcessInfo.processInfo.environment["ORIPANE_STATE_DIR"] != nil {
        return stateDirectory().appendingPathComponent("host.log")
    }
    return FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/Oripane/host.log")
}

/// Also writes every line to `url`, moving a log over 10 MB to `<name>.1` first.
func logToFile(_ url: URL) {
    logQueue.sync { openLog(url) }
}

/// On the log queue only.
private func openLog(_ url: URL) {
    let fm = FileManager.default
    try? logFile?.close()
    try? fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    if let size = (try? fm.attributesOfItem(atPath: url.path))?[.size] as? Int, size > maxLogSize {
        let old = url.appendingPathExtension("1")
        try? fm.removeItem(at: old)
        try? fm.moveItem(at: url, to: old)
    }
    if !fm.fileExists(atPath: url.path) { fm.createFile(atPath: url.path, contents: nil) }
    try? fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    logFile = try? FileHandle(forWritingTo: url)
    logSize = Int((try? logFile?.seekToEnd()) ?? 0)
    // A move that failed leaves the file this size; trying again on every line would cost each one a rename.
    if logSize > maxLogSize { logSize = 0 }
    logURL = url
}

/// The POSIX error number inside `error`, from Network.framework or Foundation.
func posixCode(_ error: Error) -> Int32? {
    if let e = error as? NWError, case let .posix(code) = e { return code.rawValue }
    if let e = error as? POSIXError { return e.code.rawValue }
    return nil
}

/// `error` for a `key=value` line: `connection_reset errno=54`, not Swift's description of it.
func logReason(_ error: Error) -> String {
    if let e = error as? HostError { return e.logName }
    guard let code = posixCode(error) else { return "\(error)" }
    return "\(PosixText.slug(code)) errno=\(code)"
}

/// `error` for the menu: what to do about the common ones, the system's own words otherwise.
func menuReason(_ error: Error, port: UInt16) -> String {
    posixCode(error).map { PosixText.menu($0, port: port) } ?? error.localizedDescription
}

func monotonicSeconds() -> Double { Double(monotonicMicros()) / 1_000_000 }

func monotonicMicros() -> UInt64 {
    DispatchTime.now().uptimeNanoseconds / 1000
}
