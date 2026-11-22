import AppKit
import ApplicationServices
import CoreGraphics
import OripaneCore
import Foundation

/// Opened as an app (Finder, `open`, a login item) rather than run as a command: inside a bundle and
/// with no subcommand. `open --args --source test …` still reaches the run options; arguments that do
/// not parse are refused before the app starts, and a test copy's log records why.
let isAppLaunch = Bundle.main.bundleURL.pathExtension == "app"
    && (CommandLine.arguments.dropFirst().first.map { $0.hasPrefix("-") } ?? true)

let args: HostCommandLine.Parsed
switch HostCommandLine.parse(Array(CommandLine.arguments.dropFirst())) {
case .help:
    print(HostCommandLine.usage)
    exit(0)
case let .failure(message):
    // In any bundle rather than only isAppLaunch, so a typo that is not an option is caught too;
    // only with a state directory, because the owner's running app writes the default log and may
    // be rotating it, while a test copy opened with `open --args` has no stderr anyone reads.
    if Bundle.main.bundleURL.pathExtension == "app", ProcessInfo.processInfo.environment["ORIPANE_STATE_DIR"] != nil {
        logToFile(appLogURL())
        log("ui arguments_rejected error=\"\(message.replacingOccurrences(of: "\"", with: "'"))\"")
    }
    FileHandle.standardError.write(Data("oripane-host: \(message)\n\n\(HostCommandLine.usage)\n".utf8))
    exit(2)
case let .parsed(parsed):
    args = parsed
}

func runOptions(_ args: HostCommandLine.Parsed) -> HostOptions {
    var o = HostOptions()
    o.source = args.values["--source"] ?? o.source
    if let port = args.values["--port"].flatMap(UInt16.init) {
        o.controlPort = port
        o.mediaPort = port + 1
    }
    o.forcedMode = args.values["--mode"]
    o.scale = args.values["--scale"].flatMap(Double.init)
    o.fps = args.values["--fps"].flatMap(Int.init)
    o.codec = args.values["--codec"]
    o.bitrate = args.values["--bitrate"].flatMap(Int.init)
    o.groupSize = args.values["--group"].flatMap(Int.init).map { min(255, max(1, $0)) } ?? o.groupSize
    o.dropRate = args.values["--drop"].flatMap(Double.init) ?? 0
    o.autoApprove = args.flags.contains("--auto-approve")
    o.dryRunInput = args.flags.contains("--dry-run-input")
    o.advertise = !args.flags.contains("--no-bonjour")
    return o
}

/// An application event loop rather than a bare run loop or dispatchMain(): without
/// NSApplication the process gets no display reconfiguration callbacks, and CoreGraphics never
/// learns the modes of a virtual display it created itself. `start` runs once the loop is up;
/// a display created before that missed its notifications too.
func runApplication(policy: NSApplication.ActivationPolicy = .prohibited, _ start: @escaping () -> Void) -> Never {
    let app = NSApplication.shared
    app.setActivationPolicy(policy)
    DisplayReconfiguration.prepare()
    DispatchQueue.main.async(execute: start)
    app.run()
    exit(0)
}

var signalSources: [DispatchSourceSignal] = []

switch args.command {
case "run" where isAppLaunch:
    var o = runOptions(args)
    o.keepRunning = true
    runMenuBarApp(o)

case "run":
    var o = runOptions(args)
    if o.source == "screen" && !CGPreflightScreenCaptureAccess() {
        log("permission screen_recording=missing — grant it in System Settings > Privacy & Security > Screen Recording")
        CGRequestScreenCaptureAccess()
        exit(1)
    }
    if !o.dryRunInput && !AXIsProcessTrusted() {
        log("permission accessibility=missing — input will be logged, not injected")
        o.dryRunInput = true
    }
    let options = o
    runApplication {
        let host: StreamHost
        do { host = try StreamHost(options: options) } catch {
            log("host failed reason=\(logReason(error))")
            exit(1)
        }
        host.start()
        for sigNum in [SIGINT, SIGTERM] {
            signal(sigNum, SIG_IGN)
            let src = DispatchSource.makeSignalSource(signal: sigNum)
            src.setEventHandler {
                log("host stopping")
                host.shutdown { exit(0) }
            }
            src.resume()
            signalSources.append(src)
        }
    }

case "probe":
    var p = ProbeClient()
    p.host = args.values["--host"] ?? p.host
    if let port = args.values["--port"].flatMap(UInt16.init) {
        p.controlPort = port
        p.mediaPort = port + 1
    }
    p.width = args.values["--width"].flatMap(Int.init) ?? p.width
    p.height = args.values["--height"].flatMap(Int.init) ?? p.height
    p.mode = args.values["--mode"] ?? p.mode
    p.seconds = args.values["--seconds"].flatMap(Double.init) ?? p.seconds
    p.output = args.values["--out"] ?? p.output
    p.stateFile = args.values["--state"] ?? p.stateFile
    p.sendInput = args.flags.contains("--input")
    p.codecs = args.values["--codecs"].map { $0.split(separator: ",").map(String.init) } ?? []
    if let r = args.values["--resize"]?.split(separator: ":"), r.count == 2, let after = Double(r[0]) {
        let wh = r[1].split(separator: "x").compactMap { Int($0) }
        if wh.count == 2 { p.resize = (after, wh[0], wh[1]) }
    }
    do { try p.run() } catch { print("probe failed: \(error)"); exit(1) }

case "display-check":
    guard let step = args.operand else { exit(2) }
    runApplication {
        // Off the main thread: reconfiguration callbacks need the main run loop free.
        Task.detached {
            let ok = await DisplayCheck.run(step)
            log("check step=\(step) result=\(ok ? "ok" : "failed")")
            exit(ok ? 0 : 1)
        }
    }

case "login-item":
    switch args.operand {
    case "on": exit(LoginItem.set(true) == nil ? 0 : 1)
    case "off": exit(LoginItem.set(false) == nil ? 0 : 1)
    case "status": print("login item: \(LoginItem.describe(LoginItem.status))")
    default: exit(2)
    }

case "pairings":
    for (id, p) in PairingStore().all() { print("\(p.pairedAt)  last_used=\(p.lastUsedAt.map { "\($0)" } ?? "-")  \(p.name)  \(id)") }

case "unpair-all":
    PairingStore().clear()
    print("all pairings removed")

case "vectors":
    guard let path = args.operand else { exit(2) }
    let data = try JSONSerialization.data(withJSONObject: try Vectors.generate(), options: [.prettyPrinted, .sortedKeys])
    try data.write(to: URL(fileURLWithPath: path))
    print("wrote \(path)")

default:
    exit(2)
}

