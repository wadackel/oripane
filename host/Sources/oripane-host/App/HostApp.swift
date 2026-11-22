import AppKit
import OripaneCore

private var appDelegate: HostApp?

/// The host as a menu bar app: what `Oripane.app` runs when it is opened rather than given a command.
func runMenuBarApp(_ options: HostOptions) -> Never {
    let delegate = HostApp(options: options)
    appDelegate = delegate
    runApplication(policy: .accessory) {
        NSApplication.shared.delegate = delegate
        delegate.launch()
    }
}

final class HostApp: NSObject, NSApplicationDelegate {
    private let options: HostOptions
    /// Shared with the host, so the menu lists what the host just added.
    let store = PairingStore()
    private let dialog = PairingDialog()
    private var host: StreamHost?
    private var menu: StatusMenu!
    private var setup: SetupWindowController?
    let setupModel = SetupModel()
    private var signalSources: [DispatchSourceSignal] = []
    private var permissionTimer: Timer?
    private(set) var status = HostStatus.starting
    private var missing: Set<HostStatus.Permission> = []

    /// A test run (`--source test`, `--dry-run-input`) needs neither permission; the test source
    /// never injects input either.
    private var needsScreenRecording: Bool { options.source == "screen" }
    private var needsAccessibility: Bool { !options.dryRunInput && options.source == "screen" }

    var menuState: HostStatus.Menu { status.menu(missing: missing) }

    init(options: HostOptions) {
        self.options = options
    }

    /// Runs once the event loop is up, after `applicationDidFinishLaunching` has already gone by.
    func launch() {
        if handOffToRunningCopy() { return }
        logToFile(appLogURL())
        let info = Bundle.main.infoDictionary
        log("ui launched version=\(info?["CFBundleShortVersionString"] ?? "-") pid=\(getpid()) log=\(appLogURL().path)")
        for sigNum in [SIGINT, SIGTERM] {
            signal(sigNum, SIG_IGN)
            let src = DispatchSource.makeSignalSource(signal: sigNum, queue: .main)
            // One way out, whatever asked: the same path as Quit, logout and restart.
            src.setEventHandler { HostApp.quit() }
            src.resume()
            signalSources.append(src)
        }
        setupModel.relaunch = { [weak self] in self?.relaunch() }
        menu = StatusMenu(app: self)
        checkPermissions()
        let timer = Timer(timeInterval: 2, repeats: true) { [weak self] _ in self?.checkPermissions() }
        RunLoop.main.add(timer, forMode: .common)
        permissionTimer = timer
        if needsScreenRecording && !Privacy.screenRecording {
            setStatus(.needsScreenRecording)
        } else {
            startHost()
        }
        if !missing.isEmpty { showSetup() }
    }

    /// A second copy (`open -n`, a test) hands the request to the one already running and leaves:
    /// two hosts cannot share the ports, and two icons would sit in the menu bar.
    private func handOffToRunningCopy() -> Bool {
        let me = getpid()
        guard let id = Bundle.main.bundleIdentifier,
              NSRunningApplication.runningApplications(withBundleIdentifier: id).contains(where: { $0.processIdentifier != me })
        else { return false }
        NSWorkspace.shared.openApplication(at: Bundle.main.bundleURL, configuration: NSWorkspace.OpenConfiguration()) { _, _ in
            exit(0)
        }
        return true
    }

    // MARK: Permissions

    private func checkPermissions() {
        let screen = Privacy.screenRecording, ax = Privacy.accessibility
        var now: Set<HostStatus.Permission> = []
        if needsScreenRecording && !screen { now.insert(.screenRecording) }
        if needsAccessibility && !ax { now.insert(.accessibility) }
        InputPermission.granted = ax
        if permissionTimer == nil || now != missing {
            log("permission screen_recording=\(screen ? "granted" : "missing") accessibility=\(ax ? "granted" : "missing")")
        }
        missing = now
        // Capture works only in a process started after the grant.
        if case .needsScreenRecording = status, screen { setStatus(.relaunchNeeded) }
        setupModel.screenRecording = !needsScreenRecording ? .notNeeded
            : status == .relaunchNeeded ? .relaunch : screen ? .on : .off
        setupModel.accessibility = !needsAccessibility ? .notNeeded : ax ? .on : .off
        if setup?.window?.isVisible == true { setupModel.loginItem = LoginItem.status }
        menu.refresh()
    }

    // MARK: Host

    private func startHost() {
        do {
            let h = try StreamHost(options: options, store: store)
            h.approver = dialog
            h.onStatus = { [weak self] s in DispatchQueue.main.async { self?.setStatus(s) } }
            host = h
            h.start()
        } catch {
            let failed = HostStatus.failed(reason: menuReason(error, port: options.mediaPort))
            // Retried every 2 s: one line per new failure, not one per retry.
            if failed != status { log("host failed reason=\(logReason(error))") }
            setStatus(failed)
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.startHost() }
        }
    }

    private func setStatus(_ s: HostStatus) {
        guard s != status else { return }
        status = s
        log("ui status line=\"\(menuState.line)\"")
        menu.refresh()
    }

    func unpair(_ id: String) {
        if let host { host.unpair(id) } else if let p = store.remove(id) { log("pairing removed name=\(p.name)") }
    }

    // MARK: Windows and actions

    /// `terminate` waits for the host in a nested event loop; called from a main-queue block, that
    /// loop cannot drain the main queue where the shutdown reply and its time limit are queued.
    static func quit() {
        RunLoop.main.perform(inModes: [.common]) { NSApp.terminate(nil) }
    }

    func showSetup(loginError: String? = nil) {
        setupModel.loginItem = LoginItem.status
        setupModel.loginError = loginError
        if setup == nil { setup = SetupWindowController(model: setupModel) }
        NSApp.activate()
        setup?.showWindow(nil)
        setup?.window?.makeKeyAndOrderFront(nil)
    }

    func showLog() {
        NSWorkspace.shared.open(appLogURL())
    }

    func showAbout() {
        NSApp.activate()
        NSApp.orderFrontStandardAboutPanel(nil)
    }

    /// Opens this copy again once this process is gone, with the same options and state directory.
    /// Launching first would make the new copy hand off to this one and quit.
    func relaunch() {
        var openArgs: [String] = []
        if let dir = ProcessInfo.processInfo.environment["ORIPANE_STATE_DIR"] { openArgs += ["--env", "ORIPANE_STATE_DIR=\(dir)"] }
        openArgs.append(Bundle.main.bundlePath)
        let args = Array(CommandLine.arguments.dropFirst())
        if !args.isEmpty { openArgs += ["--args"] + args }
        let waiter = Process()
        waiter.executableURL = URL(fileURLWithPath: "/bin/sh")
        waiter.arguments = ["-c", "while kill -0 \(getpid()) 2>/dev/null; do sleep 0.2; done; exec /usr/bin/open \"$@\"", "sh"] + openArgs
        do {
            try waiter.run()
            log("ui relaunch")
            HostApp.quit()
        } catch {
            log("ui relaunch_failed error=\(error)")
        }
    }

    // MARK: NSApplicationDelegate

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showSetup()
        return false
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard let host else { return .terminateNow }
        log("host stopping")
        var replied = false
        func reply() {
            guard !replied else { return }
            replied = true
            NSApp.reply(toApplicationShouldTerminate: true)
        }
        host.shutdown { DispatchQueue.main.async { reply() } }
        // A stuck release must not hold up logout or restart.
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
            if !replied { log("host stop_timeout") }
            reply()
        }
        return .terminateLater
    }
}
