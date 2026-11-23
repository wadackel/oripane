package dev.wadackel.oripane

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One connection as the user sees it, from starting it until they disconnect. It makes a
 * [StreamClient] for every attempt and route, picks the route to a saved Mac, keeps the record of
 * each Mac up to date, and reconnects after a drop. Main thread only.
 */
class ConnectionController(
    private val prefs: Prefs,
    private val ui: Ui,
    /** The default network's Wi-Fi transport right now, ahead of the callback that reports it. */
    private val wifiNow: () -> Boolean,
    /** The window's size and the settings now, with the display mode from `Prefs`. */
    private val currentDisplay: () -> DisplayConfig,
    /** Where hosts listen for control, for this launch; media is the next port. */
    private val controlPort: Int,
) {
    interface Ui {
        fun onState(state: ClientState)
        /** Every `stream` message, even one equal to the last: a mode switch the Mac refused looks like that. */
        fun onStream(stream: ClientState.Streaming)
        fun onStats(stats: Stats)
        fun onWarning(awdl: Boolean)
        fun onDataSaver(active: Boolean)
    }

    sealed interface Entry {
        /** A Mac from the saved list, or one whose Bonjour TXT matched a saved one. */
        data class Saved(val hostId: String) : Entry

        /** A typed address or name, or a discovered Mac that matches no record: the user is at hand to pair. */
        data class Address(val input: String) : Entry
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The client of the current attempt; callbacks from any other are dropped. */
    var client: StreamClient? = null
        private set

    /** What to call the Mac while connecting: the record's name, or what the user typed. */
    var label = ""
        private set

    private var surface: Surface? = null
    /** The display at the start of the connection: its scale, fps and UI scale hold until the user disconnects. */
    private var started: DisplayConfig? = null
    private var entry: Entry? = null
    private var hostId: String? = null
    /** The record a typed address matched: the only one its re-pairing may take over. */
    private var repairOf: String? = null
    private var authenticated = false
    private var streaming = false
    private var lastStreaming: ClientState.Streaming? = null
    private var reconnectReason: String? = null
    /** When this run of reconnect attempts began, for the time limit; 0 when none runs. */
    private var reconnectStartedMs = 0L
    /** What the badge says under "Reconnecting…", kept for every attempt of the run. */
    private var reconnectNotice: String? = null
    private var attempt = 0
    private var candidates: List<Candidate> = emptyList()
    private var index = 0
    private var route: Candidate? = null

    /** The kind of route the connection runs on now ("lan" or "tailnet"), once one was picked. */
    val routeKind: String? get() = route?.kind?.label

    /** The saved Mac this connection is to, once known: a typed address learns it at authentication. */
    val connectedHostId: String? get() = hostId
    private var paused = false
    private var scheduled: Runnable? = null
    private var waitingForBonjour: Runnable? = null
    /** The connects raced before a saved Mac's route is chosen; a result for any other is dropped. */
    private var race: RouteRace? = null
    private var raceCheck: Runnable? = null
    /** A LAN address Bonjour found while streaming over the tailnet, being connected to. */
    private var lanProbe: String? = null
    /** Ends the reconnect run once a stream has lasted; one that drops sooner continues it. */
    private var streamLasted: Runnable? = null
    /** Closes a connection left in the background, so the Mac's display goes. */
    private var backgroundClose: Runnable? = null
    private var wifi = false
    private var discovered: List<Discovered> = emptyList()
    private var dataSaver = false

    val active get() = entry != null

    fun start(e: Entry) {
        stop()
        entry = e
        started = currentDisplay()
        label = when (e) {
            is Entry.Saved -> prefs.savedMacs.firstOrNull { it.hostId == e.hostId }?.displayName ?: ""
            is Entry.Address -> e.input
        }
        hostId = (e as? Entry.Saved)?.hostId
        log("settings ${prefs.settings.describe()}")
        refreshDataSaver(force = true)
        ui.onState(ClientState.Connecting)
        when (e) {
            is Entry.Saved -> attemptSaved()
            is Entry.Address -> resolve(e.input)
        }
    }

    fun stop() {
        cancelTimers()
        entry = null
        hostId = null
        repairOf = null
        authenticated = false
        streaming = false
        lastStreaming = null
        reconnectReason = null
        reconnectStartedMs = 0
        reconnectNotice = null
        attempt = 0
        route = null
        started = null
        lanProbe = null
        dropClient()
    }

    fun attachSurface(s: Surface?) {
        surface = s
        // The size first: a surface back after a fold must not resume the stream at the old size
        // and then rebuild it for the new one.
        if (s != null) refreshDisplay()
        client?.attachSurface(s)
    }

    /**
     * The window or the display mode changed: the current client sends the new `display`. A mode
     * picked from the menu goes out even when it equals the last request, since the Mac may have
     * overridden that one.
     */
    fun refreshDisplay(force: Boolean = false) {
        display()?.let { client?.updateDisplay(it, force) }
    }

    /** Built for every client, so a reconnect takes the mode chosen from the menu. */
    private fun display(): DisplayConfig? = started?.let { s -> currentDisplay().copy(scale = s.scale, fps = s.fps, uiScale = s.uiScale) }

    fun onPause() {
        paused = true
        client?.setVisible(false)
        armBackgroundClose()
        scheduled?.let {
            handler.removeCallbacks(it)
            scheduled = null
            attempt--
        }
        waitingForBonjour?.let {
            handler.removeCallbacks(it)
            waitingForBonjour = null
        }
    }

    fun onResume() {
        paused = false
        client?.setVisible(true)
        backgroundClose?.let { handler.removeCallbacks(it) }
        backgroundClose = null
        // No attempt ran in the background, so the time limit starts over.
        if (reconnectReason != null) reconnectStartedMs = SystemClock.elapsedRealtime()
        // A race under way is an attempt without a client yet.
        if (reconnectReason != null && client == null && scheduled == null && race == null) scheduleAttempt()
    }

    fun setNetwork(wifi: Boolean) {
        if (wifi == this.wifi) return
        this.wifi = wifi
        refreshDataSaver()
        if (!wifi && race != null) {
            // Its LAN routes would only time out; the tailnet ones are raced again without the Wi-Fi hold.
            attemptSaved()
            return
        }
        if (wifi || route?.kind != RouteKind.LAN) return
        if (streaming) {
            reconnect("wifi_lost")
        } else if (reconnectReason != null && client != null) {
            // An attempt still waiting on a LAN route would only time out; move to the tailnet.
            dropClient()
            attemptSaved()
        }
    }

    fun setDiscovered(list: List<Discovered>) {
        discovered = list
        val id = hostId ?: return
        val found = list.firstOrNull { it.id == id } ?: return
        val r = race
        if (waitingForBonjour != null) {
            handler.removeCallbacks(waitingForBonjour!!)
            waitingForBonjour = null
            attemptSaved()
        } else if (r != null && wifi) {
            r.add(Candidate(RouteKind.LAN, found.addr), SystemClock.elapsedRealtime())?.let { probe(r, it) }
            // Found ends the hold on the tailnet as well.
            decideRace()
        } else if (streaming && route?.kind == RouteKind.TAILNET && wifi) {
            probeLan(found.addr)
        }
    }

    /** Re-reads the data-saver setting, as after the user changed it. */
    fun refreshDataSaver(force: Boolean = false) {
        val on = dataSaverActive(prefs.dataSaver, wifi)
        if (on == dataSaver && !force) return
        dataSaver = on
        client?.setDataSaver(on)
        ui.onDataSaver(on)
        log("data_saver active=$on setting=${prefs.dataSaver.key} wifi=$wifi")
    }

    // MARK: attempts

    private fun resolve(input: String) {
        val e = entry
        Thread {
            val addr = runCatching { InetAddress.getAllByName(normalizeHost(input)).firstOrNull { it is Inet4Address }?.hostAddress }.getOrNull()
            handler.post {
                if (entry !== e) return@post
                if (addr == null) {
                    finish(ClientState.Failed(FailReason.CONNECT_FAILED, "Couldn't find an address for $input."))
                    return@post
                }
                val record = prefs.savedMacs.forAddress(addr)
                repairOf = record?.hostId
                candidates = listOf(Candidate(routeKind(addr), addr))
                index = 0
                tryCandidate(record?.pairing, repair = true)
            }
        }.start()
    }

    /**
     * Routes to the saved Mac, raced as TCP connects. A record without any address waits on Wi-Fi
     * for Bonjour, the only thing that can find it.
     */
    private fun attemptSaved(bonjourWaited: Boolean = false) {
        // Before any way out: a race left running would start an attempt of its own.
        endRace()
        val id = hostId ?: return
        val mac = prefs.savedMacs.firstOrNull { it.hostId == id }
        if (mac == null) {
            finish(ClientState.Failed(FailReason.CONNECT_FAILED, "Couldn't find the saved Mac."))
            return
        }
        if (wifi && !bonjourWaited && mac.lanAddrs.isEmpty() && mac.tailnet.isEmpty() && discovered.none { it.id == id }) {
            val r = Runnable { waitingForBonjour = null; attemptSaved(bonjourWaited = true) }
            waitingForBonjour = r
            handler.postDelayed(r, BONJOUR_WAIT_MS)
            return
        }
        repairOf = null
        val routes = routeCandidates(wifi, discovered, mac)
        if (routes.isEmpty()) {
            attemptFailed(ClientState.Failed.naming(FailReason.CONNECT_FAILED, mac.displayName) { "No route to $it." })
            return
        }
        val r = RouteRace(routes, SystemClock.elapsedRealtime())
        race = r
        routes.indices.forEach { probe(r, it) }
    }

    private fun probe(r: RouteRace, i: Int) {
        val c = r.candidates[i]
        val begun = SystemClock.elapsedRealtime()
        Thread {
            val ok = canConnect(c.addr, c.timeoutMs)
            handler.post {
                if (race !== r) return@post
                val now = SystemClock.elapsedRealtime()
                log("route probe addr=${c.addr} kind=${c.kind.label} ok=$ok ms=${now - begun}")
                if (ok) r.succeeded(i, now) else r.failed(i)
                decideRace()
            }
        }.start()
    }

    private fun decideRace() {
        val r = race ?: return
        val id = hostId ?: return
        val mac = prefs.savedMacs.firstOrNull { it.hostId == id }
        if (mac == null) {
            endRace()
            finish(ClientState.Failed(FailReason.CONNECT_FAILED, "Couldn't find the saved Mac."))
            return
        }
        raceCheck?.let { handler.removeCallbacks(it) }
        raceCheck = null
        val now = SystemClock.elapsedRealtime()
        val seen = discovered.any { it.id == id }
        when (val d = r.decide(now, wifi, seen)) {
            is RouteDecision.Pick -> {
                val chosen = r.candidates[d.index]
                log("route chosen kind=${chosen.kind.label} addr=${chosen.addr} after_ms=${now - r.startMs}")
                endRace()
                // The others that connected follow, for a Mac that refuses the chosen one.
                candidates = listOf(chosen) + r.reachable().filter { it != chosen }
                index = 0
                tryCandidate(mac.pairing, repair = false)
            }
            RouteDecision.Wait -> r.nextCheckMs(now, wifi, seen)?.let { at ->
                val check = Runnable { raceCheck = null; decideRace() }
                raceCheck = check
                handler.postDelayed(check, at - now)
            }
            RouteDecision.NoRoute -> {
                endRace()
                attemptFailed(ClientState.Failed.naming(FailReason.CONNECT_FAILED, mac.displayName) { "Couldn't reach $it." })
            }
        }
    }

    private fun endRace() {
        race = null
        raceCheck?.let { handler.removeCallbacks(it) }
        raceCheck = null
    }

    /** Moves to the LAN only once it is known to connect: a reconnect rebuilds the Mac's display. */
    private fun probeLan(addr: String) {
        if (lanProbe != null) return
        lanProbe = addr
        val c = client
        val begun = SystemClock.elapsedRealtime()
        Thread {
            val ok = canConnect(addr, RouteKind.LAN.connectTimeoutMs)
            handler.post {
                lanProbe = null
                log("route probe addr=$addr kind=lan ok=$ok ms=${SystemClock.elapsedRealtime() - begun} reason=lan_available")
                if (ok && client === c && streaming && route?.kind == RouteKind.TAILNET && wifi) reconnect("lan_available")
            }
        }.start()
    }

    private fun canConnect(addr: String, timeoutMs: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(addr, controlPort), timeoutMs) } }.isSuccess

    private fun tryCandidate(pairing: Pairing?, repair: Boolean) {
        val c = candidates[index]
        route = c
        log("route kind=${c.kind.label} addr=${c.addr} port=$controlPort")
        if (reconnectReason != null) lastStreaming?.let { ui.onState(ClientState.Reconnecting(it, reconnectNotice, attempt, c.kind.label)) }
        dropClient()
        lateinit var cl: StreamClient
        cl = StreamClient(Target(c.addr, c.timeoutMs), pairing, repair, dataSaver, object : StreamClient.Listener {
            override fun onState(state: ClientState) = post(cl) { onClientState(state, pairing, repair) }
            override fun onStream(stream: ClientState.Streaming) = post(cl) { ui.onStream(stream) }
            override fun onStats(stats: Stats) = post(cl) { ui.onStats(stats) }
            override fun onWarning(awdl: Boolean) = post(cl) { ui.onWarning(awdl) }
            override fun onAuthenticated(pairing: Pairing, hostId: String, name: String, tailnet: List<String>, addr: String) =
                post(cl) { remember(pairing, hostId, name, tailnet, addr) }
        }, controlPort = controlPort, mediaPort = controlPort + 1)
        client = cl
        cl.setVisible(!paused)
        cl.attachSurface(surface)
        display()?.let { cl.start(it) }
    }

    private fun post(from: StreamClient, block: () -> Unit) {
        handler.post { if (client === from) block() }
    }

    private fun remember(pairing: Pairing, id: String, name: String, tailnet: List<String>, addr: String) {
        val all = prefs.savedMacs
        if (!mayUpdate(id, pairing, repairOf, all)) {
            val claimedName = all.first { it.hostId == id }.displayName
            log("client host_claim_refused host=$id")
            finish(ClientState.Failed(FailReason.HANDSHAKE_FAILED, "This Mac claims to be the saved “$claimedName”. The connection was stopped. If it is the same Mac, remove the saved record and connect again."))
            return
        }
        val saved = all.firstOrNull { it.hostId == id }.updated(id, name, pairing, addr, tailnet, System.currentTimeMillis())
        prefs.savedMacs = all.upsert(saved)
        hostId = id
        label = saved.displayName
        authenticated = true
        // A pairing the Mac allowed after the timer found nothing to close starts it again.
        if (paused && backgroundClose == null) armBackgroundClose()
    }

    /** Checked when it runs: a connection may be made, or authenticated, in the background. */
    private fun armBackgroundClose() {
        backgroundClose?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            backgroundClose = null
            if (client == null || !authenticated) return@Runnable
            log("reconnect background_close")
            dropClient()
            cancelTimers()
            streaming = false
            if (reconnectReason == null) {
                reconnectReason = "background"
                reconnectStartedMs = SystemClock.elapsedRealtime()
                attempt = 0
            }
            showReconnecting(null)
        }
        backgroundClose = r
        handler.postDelayed(r, BACKGROUND_CLOSE_MS)
    }

    private fun onClientState(state: ClientState, pairing: Pairing?, repair: Boolean) {
        when (state) {
            ClientState.Connecting, ClientState.Closed, is ClientState.Reconnecting -> {}
            is ClientState.Pairing -> ui.onState(state)
            is ClientState.Streaming -> {
                streaming = true
                lastStreaming = state
                // Bonjour found the Mac before this tailnet route was picked, and will not report it again.
                if (route?.kind == RouteKind.TAILNET && wifi) discovered.firstOrNull { it.id == hostId }?.let { probeLan(it.addr) }
                if (reconnectReason != null) {
                    streamLasted?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        streamLasted = null
                        reconnectReason = null
                        reconnectStartedMs = 0
                        reconnectNotice = null
                        attempt = 0
                    }
                    streamLasted = r
                    handler.postDelayed(r, STREAM_SURVIVES_MS)
                }
                ui.onState(state)
            }
            is ClientState.Failed -> {
                // The stream did not last: this failure belongs to the same run of attempts.
                streamLasted?.let { handler.removeCallbacks(it) }
                streamLasted = null
                onFailed(state, pairing, repair)
            }
        }
    }

    private fun onFailed(state: ClientState.Failed, pairing: Pairing?, repair: Boolean) {
        when (nextStep(state.reason, authenticated)) {
            NextStep.NEXT_CANDIDATE -> if (++index < candidates.size) tryCandidate(pairing, repair) else attemptFailed(state)
            // A Mac that never sent a picture would fail the same way again behind "Connecting…".
            NextStep.RETRY_LATER -> if (state.reason == FailReason.STREAM_FAILED && lastStreaming == null) finish(state) else attemptFailed(state)
            NextStep.RETRY_NOW -> if (route?.kind == RouteKind.LAN && !wifiNow()) {
                // The socket on a lost Wi-Fi often fails before the network callback arrives;
                // the next attempt must already skip the LAN routes.
                discovered = emptyList()
                setNetwork(false)
                if (reconnectReason == null) reconnect("wifi_lost")
            } else {
                reconnect(state.reason.name.lowercase())
            }
            NextStep.STOP -> finish(state)
        }
    }

    private fun attemptFailed(state: ClientState.Failed) {
        dropClient()
        if (!authenticated) {
            finish(state.copy(reason = FailReason.CONNECT_FAILED))
            return
        }
        val notice = state.message.takeIf { state.reason == FailReason.STREAM_FAILED }
        if (reconnectReason == null) {
            reconnect(if (notice != null) "stream_failed" else "io", notice)
        } else {
            showReconnecting(notice)
            scheduleAttempt()
        }
    }

    /** Starts (or restarts) recovering a connection that has authenticated once. */
    private fun reconnect(reason: String, notice: String? = null) {
        dropClient()
        cancelTimers()
        streaming = false
        if (reconnectReason == null) {
            reconnectReason = reason
            reconnectStartedMs = SystemClock.elapsedRealtime()
            attempt = 0
        }
        // Every time: a drop within a lasting-stream window continues the run, and still needs the badge.
        showReconnecting(notice)
        scheduleAttempt()
    }

    private fun showReconnecting(notice: String?) {
        reconnectNotice = notice
        lastStreaming?.let { ui.onState(ClientState.Reconnecting(it, notice, attempt, route?.kind?.label)) }
    }

    private fun scheduleAttempt() {
        if (paused) return
        if (reconnectGaveUp(reconnectStartedMs, SystemClock.elapsedRealtime())) {
            log("reconnect gave_up attempts=$attempt")
            // Home calls GAVE_UP not reachable. A run whose last attempt failed on anything but a
            // stream nearly always found no route that connects; one the Mac answered with a
            // failed stream reached it.
            finish(ClientState.Failed(if (reconnectNotice == null) FailReason.GAVE_UP else FailReason.IO, "The Mac didn't come back."))
            return
        }
        attempt++
        val r = Runnable {
            scheduled = null
            log("reconnect attempt=$attempt reason=$reconnectReason")
            attemptSaved()
        }
        scheduled = r
        handler.postDelayed(r, reconnectDelayMs(attempt))
    }

    private fun finish(state: ClientState.Failed) {
        stop()
        ui.onState(state)
    }

    private fun dropClient() {
        // Detached first, so the Closed it reports on this thread is dropped.
        val c = client ?: return
        client = null
        c.close()
    }

    private fun cancelTimers() {
        scheduled?.let { handler.removeCallbacks(it) }
        scheduled = null
        waitingForBonjour?.let { handler.removeCallbacks(it) }
        waitingForBonjour = null
        endRace()
        streamLasted?.let { handler.removeCallbacks(it) }
        streamLasted = null
        backgroundClose?.let { handler.removeCallbacks(it) }
        backgroundClose = null
    }
}
