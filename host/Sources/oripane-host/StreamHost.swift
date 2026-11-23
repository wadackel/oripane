import CoreGraphics
import OripaneCore
import Foundation
import Network

struct HostOptions {
    var controlPort: UInt16 = 47800
    var mediaPort: UInt16 = 47801
    var source = "screen"            // screen | test
    var forcedMode: String?          // overrides the client's choice: extend | mirror | match
    var scale: Double?               // fixed, for testing, over the client's `display` scale
    var fps: Int?                    // fixed, for testing, over the client's `display` fps
    var codec: String?               // fixed, for testing, over the client's `display` codecs: h264 | hevc
    var bitrate: Int?                // fixed, for testing, over the client's `limit`; otherwise derived from the stream size
    var groupSize = 8
    var dropRate = 0.0
    var autoApprove = false
    var dryRunInput = false
    var advertise = true
    var keepRunning = false          // the app: a failed listener is retried, never the end of the process
}

/// One authenticated client at a time: owns its pipeline (display, capture, encoder) and the
/// media session, and reacts to control messages.
final class StreamHost: Approver {
    private let options: HostOptions
    private let store: PairingStore
    private let hostId: Data
    private let name: String
    private var control: ControlServer!
    private let media: MediaSocket
    private let queue = DispatchQueue(label: "oripane.host")
    private let sendQueue = DispatchQueue(label: "oripane.send", qos: .userInteractive)
    private let awdl = AwdlGuard()
    /// The app's dialog; without one, a request is asked on the terminal.
    var approver: Approver?
    /// Called on the host's queue whenever what the menu shows may have changed.
    var onStatus: ((HostStatus) -> Void)?

    private var session: Session?
    /// Media packets that failed authentication since the last `udp auth_failed` line; the host's queue only.
    private var authFailures = 0
    /// Why the last session's video stopped, for the menu until a stream starts again; the host's queue only.
    private var lastStreamFailure: String?
    /// Every pipeline stop and start runs on this chain, one after another: a virtual display
    /// cannot be created while the previous one is still being released.
    private var pipelineChain: Task<Void, Never>?

    private func enqueue(_ work: @escaping () async -> Void) {
        let previous = pipelineChain
        pipelineChain = Task {
            await previous?.value
            await work()
        }
    }

    init(options: HostOptions, store: PairingStore = PairingStore()) throws {
        self.options = options
        self.store = store
        hostId = HostIdentity.load()
        // Off `queue`: resolving the computer name can block.
        name = Host.current().localizedName ?? "oripane"
        media = try MediaSocket(port: options.mediaPort)
        media.dropRate = options.dropRate
        try makeControl()
        media.onPacket = { [weak self] data, from, len in self?.handleMedia(data, from, len) }
        awdl.onChange = { [weak self] active in
            log("awdl active=\(active)")
            self?.queue.async { self?.session?.control.send(["t": "warn", "code": "awdl", "active": active]) }
        }
    }

    /// Stops the stream and removes the virtual display before the process exits, so the display
    /// is never dropped in the middle of a mode change.
    func shutdown(then finish: @escaping () -> Void) {
        queue.async { [self] in
            if let s = session { end(s) }
            enqueue { finish() }
        }
    }

    private func makeControl() throws {
        let c = try ControlServer(port: options.controlPort, store: store, approver: self, advertise: options.advertise, name: name, hostId: hostId)
        c.onEstablished = { [weak self] c in self?.queue.async { self?.begin(c) } }
        c.onReady = { [weak self] in self?.queue.async { self?.report() } }
        if options.keepRunning {
            c.onFailure = { [weak self] e in self?.queue.async { self?.listenerFailed(e) } }
        }
        control = c
    }

    private func listenerFailed(_ error: NWError) {
        control.stop()
        onStatus?(.failed(reason: menuReason(error, port: options.controlPort)))
        queue.asyncAfter(deadline: .now() + 2) { [self] in
            do {
                try makeControl()
                control.start()
            } catch {
                log("control listener_failed reason=\(logReason(error))")
                listenerFailed(error as? NWError ?? .posix(.EINVAL))
            }
        }
    }

    private func report() {
        guard let onStatus else { return }
        if let s = session {
            onStatus(.connected(name: s.control.clientName, mode: s.mode, fallback: s.fallback, clientId: s.control.clientId))
        } else if let f = lastStreamFailure {
            onStatus(.streamFailed(reason: f))
        } else {
            onStatus(.waiting)
        }
    }

    /// Forgets a phone, and ends its session if it is the one connected: it reconnects and is refused.
    func unpair(_ id: String) {
        queue.async { [self] in
            if let p = store.remove(id) { log("pairing removed name=\(p.name)") }
            if let s = session, s.control.clientId == id { s.control.disconnect(reason: "unpaired") }
        }
    }

    func start() {
        control.start()
        media.startReceiving()
        awdl.start()
        log("host ready control=\(options.controlPort) media=\(options.mediaPort) source=\(options.source) scale=\(options.scale.map { "\($0)" } ?? "-") fps=\(options.fps.map { "\($0)" } ?? "-") drop=\(options.dropRate)")
    }

    // MARK: Approver

    /// The host's queue only.
    private var cooldown = PairCooldown()

    func approve(_ request: PairRequest, completion: @escaping (PairOutcome) -> Void) -> () -> Void {
        if options.autoApprove {
            log("pairing auto_approved code=\(request.code)")
            completion(.allowed)
            return {}
        }
        // Set on the queue before the withdrawal, which goes through the queue too, can run.
        var withdraw: (() -> Void)?
        queue.async { [self] in
            if cooldown.blocks(request.from, now: monotonicSeconds()) {
                log("pairing denied reason=cooldown from=\(request.from)")
                return completion(.refused)
            }
            let answer: (PairOutcome) -> Void = { [weak self] outcome in
                if outcome == .denied { self?.queue.async { self?.cooldown.denied(request.from, at: monotonicSeconds()) } }
                completion(outcome)
            }
            withdraw = approver?.approve(request, completion: answer) ?? ask(request, completion: answer)
        }
        return { [weak self] in self?.queue.async { withdraw?() } }
    }

    // MARK: Terminal approval (the host's queue only)

    private struct Ask {
        let request: PairRequest
        let completion: (PairOutcome) -> Void
    }

    private var asks = ApprovalQueue<Ask>()
    /// Bumped whenever the question on the terminal changes: a line typed for an earlier one is not its answer.
    private var askGeneration = 0
    /// When the question on the terminal was printed.
    private var askedAt = 0.0
    private var reading = false
    /// Standard input ended: no one can answer on the terminal any more.
    private var noTerminal = false

    private func ask(_ request: PairRequest, completion: @escaping (PairOutcome) -> Void) -> () -> Void {
        guard !noTerminal else {
            log("pairing denied reason=no_terminal from=\(request.from)")
            completion(.refused)
            return {}
        }
        let token = UUID()
        switch asks.add(token, from: request.from, Ask(request: request, completion: completion)) {
        case .full:
            log("pairing denied reason=busy from=\(request.from)")
            completion(.refused)
            return {}
        case .waiting:
            log("pairing queued waiting=\(asks.waiting)")
            // The question on screen was printed before this one came.
            if let line = PairPrompt.waitingLine(asks.waiting) { print("\n  \(line)", terminator: "") }
            fflush(stdout)
        case .shown:
            showQuestion()
        }
        return { [weak self] in self?.withdrawAsk(token) }
    }

    private func showQuestion() {
        guard let (_, a) = asks.shown else { return }
        askGeneration += 1
        askedAt = monotonicSeconds()
        print(PairPrompt.question(name: a.request.name, code: a.request.code, from: a.request.from, waiting: asks.waiting), terminator: "")
        fflush(stdout)
        guard !reading else { return }
        reading = true
        let generation = askGeneration
        Thread.detachNewThread { [weak self] in
            let line = readLine()
            self?.queue.async { self?.answerQuestion(line, generation: generation) }
        }
    }

    private func answerQuestion(_ line: String?, generation: Int) {
        reading = false
        guard let line else {
            // At its end, standard input returns at once every time: asking again would spin.
            log("pairing terminal_closed")
            noTerminal = true
            while let (token, a) = asks.shown {
                _ = asks.answered(token)
                a.completion(.refused)
            }
            return
        }
        guard let (token, a) = asks.shown else { return }
        guard generation == askGeneration else {
            log("pairing answer_ignored reason=stale")
            print("\nThat answer reached the question the phone cancelled; answer again.")
            showQuestion()
            return
        }
        // A line typed ahead, a second `y` for the request before, is read at once: like the
        // dialog's Allow, an answer counts only a second after its question.
        let elapsed = monotonicSeconds() - askedAt
        guard elapsed >= 1 else {
            log("pairing answer_ignored reason=early")
            // Asked again once the second is up, so lines typed ahead cannot loop it faster.
            queue.asyncAfter(deadline: .now() + (1 - elapsed)) { [weak self] in
                if self?.askGeneration == generation { self?.showQuestion() }
            }
            return
        }
        let ok = line.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("y")
        let next = asks.answered(token)
        a.completion(ok ? .allowed : .denied)
        if next != nil { showQuestion() }
    }

    private func withdrawAsk(_ token: UUID) {
        let wasShown = asks.shown?.token == token
        if asks.withdrawn(token) != nil {
            showQuestion()
        } else if wasShown {
            askGeneration += 1
            print("\nThe phone gave up; the question above no longer applies.")
            fflush(stdout)
        }
    }

    // MARK: Session lifecycle

    private func begin(_ c: ControlConnection) {
        // Unpaired between its proof and here, while `unpair` could not see a session yet.
        guard store.contains(c.clientId) else {
            c.disconnect(reason: "unpaired")
            return
        }
        store.touch(c.clientId)
        if let old = session {
            // A phone told nothing would reconnect and take the session back, and two clients
            // would keep replacing each other.
            log("session replaced id=\(old.id) bye=sent")
            old.control.sendThenClose(["t": "bye", "reason": "replaced"], reason: "replaced")
            end(old)
        }
        let s = Session(id: UInt32.random(in: 1...UInt32.max), control: c, cipher: SessionCipher(key: c.sessionKey))
        session = s
        c.onMessage = { [weak self] m in self?.queue.async { self?.handleControl(m, s) } }
        c.onDisconnect = { [weak self] in self?.queue.async { if self?.session === s { self?.end(s) } } }
        // Collected per session: Tailscale may have come up after the host started.
        let tailnet = Tailnet.addresses(interfaceAddresses())
        c.send(["t": "auth_ok", "sessionId": Int(s.id), "hostId": hostId.base64EncodedString(), "name": name, "tailnet": tailnet])
        if awdl.active { c.send(["t": "warn", "code": "awdl", "active": true]) }
        log("session start id=\(s.id) name=\(c.clientName)")
        report()
        watch(s)
    }

    /// A phone that vanished without closing (Wi-Fi gone, a frozen process) never reaches
    /// `onDisconnect`, and its keepalives may still be answered by its kernel. It sends a ping and
    /// a hello every second, so this much silence means it is gone.
    private func watch(_ s: Session) {
        queue.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, self.session === s else { return }
            let silent = monotonicMicros() - s.lastHeard
            guard silent <= 5_000_000 else {
                log("session timeout id=\(s.id) silent_ms=\(silent / 1000)")
                s.control.disconnect(reason: "timeout")
                return
            }
            // Here rather than where frames are sent: a stalled stream must still log its line.
            s.reportIfDue()
            self.watch(s)
        }
    }

    private func end(_ s: Session) {
        if session === s { session = nil }
        media.clearPeer()
        let p = s.pipeline
        s.pipeline = nil
        p?.injector.releaseAll()
        enqueue {
            await p?.stop()
            // A virtual display released while it is the main display's mirror master was never tried.
            self.unmatch(s)
            if s.virtualDisplay != nil {
                VirtualDisplay.release(&s.virtualDisplay)
                log("display released session=\(s.id)")
            }
        }
        log("session end id=\(s.id) frames=\(s.totalFrames)")
        report()
    }

    private func handleControl(_ m: [String: Any], _ s: Session) {
        guard session === s else { return }
        s.lastHeard = monotonicMicros()
        switch m["t"] as? String {
        case "display":
            s.lastDisplay = m
            // Kept for `resume`, which builds at the size of now rather than twice.
            if s.paused { log("display stored session=\(s.id) reason=paused") } else { reconfigure(s, m) }
        case "pause":
            pause(s)
        case "resume":
            resume(s)
        case "limit":
            let requested = (m["maxBitrate"] as? NSNumber)?.intValue ?? 0
            s.maxBitrate = BitrateLimit.normalize(requested)
            log("limit max_bitrate=\(s.maxBitrate) requested=\(requested)")
            applyCeiling(s)
        case "keyframe":
            s.pipeline?.requestKeyframe()
        case "ping":
            s.control.send(["t": "pong", "c": m["c"] ?? 0, "h": Int(monotonicMicros())])
        case "key":
            s.inputPackets += 1
            s.pipeline?.injector.key(androidCode: m["code"] as? Int ?? -1, down: m["down"] as? Bool ?? false, meta: m["meta"] as? Int ?? 0)
        case "text":
            if let t = m["s"] as? String { s.pipeline?.injector.text(t) }
        case "system":
            s.inputPackets += 1
            if let a = m["action"] as? String { s.pipeline?.injector.system(a) }
        case "bye":
            s.control.close(reason: "bye")
        default:
            log("control unknown_message t=\(m["t"] ?? "nil")")
        }
    }

    private func reconfigure(_ s: Session, _ m: [String: Any]) {
        let w = m["w"] as? Int ?? 0, h = m["h"] as? Int ?? 0
        guard w >= 64, h >= 64, w <= 8192, h <= 8192 else { log("display invalid w=\(w) h=\(h)"); return }
        let mode = options.forcedMode ?? (m["mode"] as? String ?? "extend")
        let needsVirtual = options.source != "test" && mode != "mirror"
        let req = DisplayRequest.resolve(m, forcedScale: options.scale, forcedFps: options.fps, forcedCodec: options.codec)
        let dpi = req.dpi
        let pixels = VirtualDisplaySize.pixels(clientWidth: w, clientHeight: h, uiScale: req.uiScale)
        let side = VirtualDisplaySize.side(clientWidth: w, clientHeight: h, uiScale: req.uiScale)
        func show(_ v: Double?) -> String { v.map { "\($0)" } ?? "-" }
        log("display request w=\(w) h=\(h) dpi=\(Int(dpi)) mode=\(mode) scale=\(req.scale) fps=\(req.fps) ui_scale=\(needsVirtual ? "\(req.uiScale)" : "-") requested_scale=\(show(req.requestedScale)) requested_fps=\(show(req.requestedFps)) requested_ui_scale=\(show(req.requestedUiScale)) scale_forced=\(req.scaleForced) fps_forced=\(req.fpsForced) codec=\(req.codec) codec_forced=\(req.codecForced)")
        if needsVirtual, pixels.width > 8192 || pixels.height > 8192 || side > 8192 {
            log("display invalid w=\(pixels.width) h=\(pixels.height) side=\(side)")
            return
        }
        // Half a frame interval: spread longer, the next frame would wait behind it. Set before the
        // pipeline starts, which sends its first keyframe before it is handed back.
        s.spreadUs = 1_000_000 / max(1, req.fps) / 2
        let old = s.pipeline
        s.pipeline = nil
        old?.injector.releaseAll()
        let maxBitrate = s.maxBitrate
        enqueue {
            await old?.stop()
            // Every change starts unmirrored: configuring the virtual display while the main
            // display mirrors it dropped the main display to 800x600.
            self.unmatch(s)
            // A session that could not match once streams mirror from then on: retrying on every
            // fold would create and destroy a virtual display each time.
            var used = mode == "match" && s.matchFailed ? "mirror" : mode
            let usesVirtual = self.options.source != "test" && used != "mirror"
            do {
                if !usesVirtual, s.virtualDisplay != nil {
                    // The old pipeline captured this display, so it goes only once that has stopped;
                    // macOS moves its windows back to the main screen.
                    VirtualDisplay.release(&s.virtualDisplay)
                    log("display released session=\(s.id) reason=mirror")
                }
                if usesVirtual {
                    // `side` leaves room for both panels of a foldable, so folding resizes in
                    // place instead of destroying and recreating the display.
                    // No `if let` binding here: it would keep the display alive through `release`.
                    if let max = s.virtualDisplay?.maxPixels, max < side { VirtualDisplay.release(&s.virtualDisplay) }
                    if s.virtualDisplay == nil {
                        guard let vd = VirtualDisplay(maxPixels: side, pixelWidth: pixels.width, pixelHeight: pixels.height, refreshRate: Double(req.fps), hiDPI: true, ppi: dpi) else {
                            throw HostError.virtualDisplayFailed
                        }
                        s.virtualDisplay = vd
                        log("display created id=\(vd.displayID) max=\(side)")
                    }
                }
                if used == "match", usesVirtual {
                    // A mirroring `unmatch` could not end keeps its display; otherwise the main display
                    // is the physical one again. Still mirrored, another mirror would only stack changes.
                    let physical = s.matchedDisplay ?? CGMainDisplayID()
                    let virtualID = s.virtualDisplay!.displayID
                    var failure: String?
                    if physical == virtualID || CGDisplayIsInMirrorSet(physical) != 0 {
                        failure = "mirrored"
                    } else if !s.virtualDisplay!.configure(pixelWidth: pixels.width, pixelHeight: pixels.height, refreshRate: Double(req.fps), hiDPI: true) {
                        failure = "configure"
                    } else {
                        // Set before trying: a refused configuration may still have been applied.
                        s.matchedDisplay = physical
                        if !VirtualDisplay.mirror(physical, of: virtualID) { failure = "mirror" }
                    }
                    if let failure {
                        log("display match_unavailable physical=\(physical) virtual=\(virtualID) reason=\(failure)")
                        s.matchFailed = true
                        self.unmatch(s)
                        VirtualDisplay.release(&s.virtualDisplay)
                        log("display released session=\(s.id) reason=match_unavailable")
                        used = "mirror"
                    } else {
                        let b = CGDisplayBounds(virtualID)
                        log("display match physical=\(physical) virtual=\(virtualID) points=\(Int(b.width))x\(Int(b.height))")
                    }
                }
                let fallback = mode == "match" && used == "mirror"
                let p: Pipeline
                do {
                    p = try await Pipeline.make(
                        clientWidth: w, clientHeight: h, mode: used, virtualDisplay: usesVirtual ? s.virtualDisplay : nil,
                        virtualPixels: pixels, scale: req.scale, fps: req.fps, codec: req.codec, maxBitrate: maxBitrate, options: self.options
                    ) { [weak self, weak s] frame in
                        guard let self, let s else { return }
                        self.sendQueue.async { self.transmit(frame, s) }
                    } onFailure: { [weak self, weak s] reason in
                        guard let self, let s else { return }
                        self.queue.async { self.failStream(s, reason) }
                    }
                } catch {
                    // A display left half-configured is worse than none.
                    self.unmatch(s)
                    VirtualDisplay.release(&s.virtualDisplay)
                    throw error
                }
                self.queue.async {
                    // A pause that came while it was built: `resume` builds again from the last `display`.
                    guard self.session === s, !s.paused else { self.enqueue { await p.stop() }; return }
                    // A pause and resume while this was built started another build, which took
                    // no old pipeline to stop: this one was not in place yet.
                    if let prev = s.pipeline {
                        prev.injector.releaseAll()
                        self.enqueue { await prev.stop() }
                    }
                    s.pipeline = p
                    s.mode = used
                    s.fallback = fallback
                    self.lastStreamFailure = nil
                    self.report()
                    s.packetizer = Packetizer(groupSize: self.options.groupSize)
                    s.rate = RateController(initial: p.bitrate, now: monotonicMicros())
                    // A `limit` may have arrived while the pipeline was being built.
                    self.applyCeiling(s)
                    var stream: [String: Any] = ["t": "stream", "w": p.streamWidth, "h": p.streamHeight, "fps": p.fps, "codec": p.codec, "mode": p.mode]
                    if fallback { stream["fallback"] = "match" }
                    s.control.send(stream)
                    // No keyframe request here: the encoder starts with one, and the phone asks if it is lost.
                    log("stream sent w=\(p.streamWidth) h=\(p.streamHeight) mode=\(p.mode) fallback=\(fallback ? "match" : "-") codec=\(p.codec)")
                }
            } catch {
                log("pipeline failed reason=\(logReason(error))")
                self.queue.async { self.failStream(s, "pipeline_failed") }
            }
        }
    }

    /// Nothing on the phone can show the video: capture and encoding stop, the display stays.
    private func pause(_ s: Session) {
        guard !s.paused else { return }
        s.paused = true
        log("session paused id=\(s.id)")
        let old = s.pipeline
        s.pipeline = nil
        old?.injector.releaseAll()
        enqueue { await old?.stop() }
    }

    private func resume(_ s: Session) {
        guard s.paused else {
            log("session resume_ignored id=\(s.id) reason=not_paused")
            return
        }
        s.paused = false
        log("session resumed id=\(s.id)")
        if let m = s.lastDisplay { reconfigure(s, m) }
    }

    /// The video cannot go on. The phone hears why and reconnects after a pause, which builds a new
    /// pipeline; the menu says so until a stream starts again.
    private func failStream(_ s: Session, _ reason: String) {
        // Two rebuilds can both fail before the first bye has closed the connection.
        guard session === s, !s.streamFailed else { return }
        s.streamFailed = true
        log("stream failed id=\(s.id) reason=\(reason)")
        lastStreamFailure = Self.failureText[reason] ?? reason
        s.control.sendThenClose(["t": "bye", "reason": "stream_failed", "detail": reason], reason: "stream_failed")
    }

    private static let failureText = [
        "pipeline_failed": "it could not start",
        "capture_stopped": "screen capture stopped — check Screen Recording",
        "encoder_failed": "the encoder failed",
        "encoder_stalled": "the encoder stopped responding",
        "fake": "ORIPANE_FAKE_STREAM_FAILURE",
    ]

    /// Ends the main display's mirroring of the session's virtual display, if it still does.
    /// Runs on the pipeline chain only, like every other display change. A failure keeps
    /// `matchedDisplay`, so the next call tries again.
    private func unmatch(_ s: Session) {
        guard let id = s.matchedDisplay else { return }
        // Ending a mirror that is not there brings no notification, only the wait's full timeout.
        if CGDisplayIsInMirrorSet(id) != 0, !VirtualDisplay.mirror(id, of: kCGNullDirectDisplay) {
            log("display unmatch_failed physical=\(id)")
            return
        }
        s.matchedDisplay = nil
    }

    /// Points the rate controller at the session's limit; `--bitrate` keeps its fixed value.
    private func applyCeiling(_ s: Session) {
        guard options.bitrate == nil, let rate = s.rate, let p = s.pipeline else { return }
        let ceiling = BitrateLimit.ceiling(unlimited: p.unlimitedBitrate, limit: s.maxBitrate)
        guard ceiling != rate.ceiling else { return }
        if let next = rate.setCeiling(ceiling, now: monotonicMicros()) { p.setBitrate(next) }
        log("rate ceiling=\(ceiling) bitrate=\(rate.current)")
    }

    // MARK: Media

    private func transmit(_ frame: EncodedFrame, _ s: Session) {
        guard media.hasPeer else { return }
        let frameId = s.nextFrameId
        s.nextFrameId &+= 1
        let hostUs = UInt32(clamping: Int64(monotonicMicros()) - Int64(frame.captureUs))
        var out: [Data] = []
        // A keyframe is the one frame that cannot be skipped and the largest burst, so it gets
        // twice the parity; losing it costs a round trip and another keyframe.
        let packetizer = frame.keyframe ? Packetizer(groupSize: max(1, s.packetizer.groupSize / 2)) : s.packetizer
        for p in packetizer.packets(frame: frame.annexB, frameId: frameId, keyframe: frame.keyframe, captureUs: frame.captureUs, hostUs: hostUs) {
            let (type, plain): (MediaType, Data)
            switch p {
            case let .fragment(f): (type, plain) = (.videoFragment, f.encoded)
            case let .parity(q): (type, plain) = (.videoParity, q.encoded)
            }
            let header = MediaHeader(type: type, sessionId: s.id, seq: s.takeSeq())
            if let sealed = try? MediaCodec.seal(header, plaintext: plain, cipher: s.cipher, dir: .mediaToClient) {
                out.append(sealed)
            }
        }
        let sendStart = monotonicMicros()
        media.send(out, spreadUs: s.spreadUs)
        s.noteSent(bytes: frame.annexB.count)
        if frame.keyframe {
            log(String(format: "video keyframe id=%u bytes=%d packets=%d send_ms=%.1f", frameId, frame.annexB.count, out.count, Double(monotonicMicros() - sendStart) / 1000))
        }
    }

    private func handleMedia(_ data: Data, _ from: sockaddr_storage, _ len: socklen_t) {
        queue.async { [self] in
            guard let s = session, let header = try? MediaHeader.decode(data), header.sessionId == s.id else { return }
            guard let (h, plain) = try? MediaCodec.open(data, cipher: s.cipher, dir: .mediaToHost) else {
                // The session id travels in the clear, so anyone on the path can send these; a line
                // each would let them fill the log and hold up this queue.
                authFailures += 1
                if authFailures == 1 {
                    queue.asyncAfter(deadline: .now() + 1) { [self] in
                        log("udp auth_failed from=\(MediaSocket.describe(from)) count=\(authFailures)")
                        authFailures = 0
                    }
                }
                return
            }
            // Replays carry an old seq; inputs and feedback are only ever applied forwards.
            guard s.lastInboundSeq.map({ h.seq > $0 }) ?? true else { return }
            s.lastInboundSeq = h.seq
            s.lastHeard = monotonicMicros()
            switch h.type {
            case .hello:
                media.setPeer(from, len)
                let ack = MediaHeader(type: .helloAck, sessionId: s.id, seq: s.takeSeq())
                if let sealed = try? MediaCodec.seal(ack, plaintext: Data(), cipher: s.cipher, dir: .mediaToClient) {
                    sendQueue.async { self.media.send([sealed]) }
                }
            case .input:
                s.inputPackets += 1
                // The session, not the pipeline, remembers what was applied: a new pipeline would
                // take the repeats in the next packet as new and replay a click from before it.
                // Without a pipeline nothing is marked, so inputs made meanwhile come with the next.
                if let p = s.pipeline, let events = try? InputPacket.decode(plain) { p.injector.apply(s.inputs.fresh(events)) }
            case .feedback:
                guard let fb = try? Feedback.decode(plain) else { return }
                s.addFeedback(fb)
                if options.bitrate == nil, let rate = s.rate, let p = s.pipeline, let next = rate.update(fb, now: monotonicMicros()) {
                    p.setBitrate(next)
                    log("rate bitrate=\(next) loss=\(fb.packetsLost)/\(UInt64(fb.packetsReceived) + UInt64(fb.packetsLost))")
                }
            default:
                break
            }
        }
    }
}

final class Session {
    let id: UInt32
    let control: ControlConnection
    let cipher: SessionCipher
    var pipeline: Pipeline?
    /// The mode of the stream in progress, for the menu.
    var mode: String?
    /// Match was asked for and could not be set up, so `mode` is mirror instead.
    var fallback = false
    /// The bye for a stream failure is on its way; the host's queue only.
    var streamFailed = false
    /// The phone asked to stop the video, and the last `display` it sent; the host's queue only.
    var paused = false
    var lastDisplay: [String: Any]?
    /// Lives while the session extends or matches the Mac's desktop, and is only touched from the
    /// host's pipeline chain: switching to mirror releases it, switching back creates it again.
    var virtualDisplay: VirtualDisplay?
    /// The physical display that mirrors `virtualDisplay` in match mode. While it mirrors,
    /// `CGMainDisplayID()` names the virtual display, so this is what ends the mirroring.
    /// Pipeline chain only, as `virtualDisplay`: `end` must see what a chained change set.
    var matchedDisplay: CGDirectDisplayID?
    /// Match failed once in this session; later `display` messages stream mirror. Pipeline chain only.
    var matchFailed = false
    var packetizer = Packetizer()
    var rate: RateController?
    /// The client's `limit`, 0 for none; read and written on the host's queue only.
    var maxBitrate = 0
    /// Inputs applied so far, across pipelines; the host's queue only.
    var inputs = InputSequence()
    // Outbound seq is the AES-GCM nonce counter and is taken from two queues; it must never repeat.
    private var nextSeq: UInt64 = 0
    private let seqLock = NSLock()
    var nextFrameId: UInt32 = 0
    var lastInboundSeq: UInt64?
    /// When anything authenticated last arrived, control or media; the host's queue only.
    var lastHeard = monotonicMicros()
    /// Control and media input messages since the last stats line; the host's queue only.
    var inputPackets = 0
    // Frames and bytes are counted on the send queue, everything else on the host's queue.
    private let sentLock = NSLock()
    private var framesSent = 0
    private var bytesSent = 0
    private var framesTotal = 0
    private var spread = 0
    private var window = FeedbackWindow()
    private var lastReport = monotonicMicros()

    init(id: UInt32, control: ControlConnection, cipher: SessionCipher) {
        self.id = id
        self.control = control
        self.cipher = cipher
    }

    func takeSeq() -> UInt64 {
        seqLock.lock(); defer { seqLock.unlock() }
        defer { nextSeq += 1 }
        return nextSeq
    }

    func noteSent(bytes: Int) {
        sentLock.lock(); framesSent += 1; framesTotal += 1; bytesSent += bytes; sentLock.unlock()
    }

    var totalFrames: Int { sentLock.lock(); defer { sentLock.unlock() }; return framesTotal }

    /// How long a large frame's packets may take to go out; set on the host's queue, read on the send queue.
    var spreadUs: Int {
        get { sentLock.lock(); defer { sentLock.unlock() }; return spread }
        set { sentLock.lock(); spread = newValue; sentLock.unlock() }
    }

    func addFeedback(_ fb: Feedback) { window.add(fb) }

    /// One line per five seconds, every value counted over those five seconds alone.
    func reportIfDue() {
        let now = monotonicMicros()
        guard now - lastReport >= 5_000_000 else { return }
        let secs = Double(now - lastReport) / 1e6
        sentLock.lock()
        let frames = framesSent, bytes = bytesSent
        framesSent = 0
        bytesSent = 0
        sentLock.unlock()
        let fb = window
        window = FeedbackWindow()
        let input = pipeline?.injector.takeCounts() ?? (moves: 0, scrolls: 0)
        log(String(format: "stats fps=%.1f mbps=%.2f encoder_dropped=%d client_received=%d client_lost=%d client_recovered=%d client_dropped=%d client_total_ms=%.1f client_decode_ms=%.1f inputs=%d pointer_moves=%d scrolls=%d",
                   Double(frames) / secs, Double(bytes) * 8 / secs / 1e6, pipeline?.takeEncoderDropped() ?? 0,
                   fb.received, fb.lost, fb.recovered, fb.dropped, fb.meanTotalMs, fb.meanDecodeMs,
                   inputPackets, input.moves, input.scrolls))
        inputPackets = 0
        lastReport = now
    }
}

/// IPv4 addresses of every interface, as (interface name, dotted quad).
private func interfaceAddresses() -> [(name: String, ipv4: String)] {
    var head: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&head) == 0, let first = head else { return [] }
    defer { freeifaddrs(head) }
    var out: [(name: String, ipv4: String)] = []
    for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
        guard let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        var addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
        guard inet_ntop(AF_INET, &addr, &buf, socklen_t(buf.count)) != nil else { continue }
        out.append((name: String(cString: p.pointee.ifa_name), ipv4: String(cString: buf)))
    }
    return out
}
