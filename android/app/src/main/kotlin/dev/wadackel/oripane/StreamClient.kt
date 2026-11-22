package dev.wadackel.oripane

import android.view.Surface
import dev.wadackel.oripane.protocol.ControlStream
import dev.wadackel.oripane.protocol.Direction
import dev.wadackel.oripane.protocol.Feedback
import dev.wadackel.oripane.protocol.Handshake
import dev.wadackel.oripane.protocol.InputEvent
import dev.wadackel.oripane.protocol.InputPacket
import dev.wadackel.oripane.protocol.LossCounter
import dev.wadackel.oripane.protocol.MediaCodecs
import dev.wadackel.oripane.protocol.MediaHeader
import dev.wadackel.oripane.protocol.MediaType
import dev.wadackel.oripane.protocol.Reassembler
import dev.wadackel.oripane.protocol.SequencedInput
import dev.wadackel.oripane.protocol.SessionCipher
import dev.wadackel.oripane.protocol.VideoFragment
import dev.wadackel.oripane.protocol.VideoParity
import dev.wadackel.oripane.protocol.X25519KeyPair
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Where one [StreamClient] connects: an address and how long the TCP connect may take. */
data class Target(val addr: String, val connectTimeoutMs: Int)

data class DisplayConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val hz: Int,
    val mode: String,
    val scale: Double = 1.0,
    val fps: Int = 60,
    val uiScale: Double = 2.0,
)

data class Stats(
    val fps: Double = 0.0,
    val totalMs: Double = 0.0,
    val arrivalMs: Double = 0.0,
    val decodeMs: Double = 0.0,
    val lossPercent: Double = 0.0,
    val mbps: Double = 0.0,
    val recovered: Long = 0,
    val dropped: Long = 0,
    val rttMs: Double = 0.0,
    val decoder: String = "",
    /** Nothing authenticated has come over UDP for a while: the picture is frozen, not still. */
    val noMedia: Boolean = false,
)

sealed interface ClientState {
    data object Connecting : ClientState
    data class Pairing(val code: String) : ClientState
    /**
     * [fallback] is the mode the Mac could not set up (`"match"`), when it streams another instead.
     * [codec] is `"h264"` or `"hevc"`.
     */
    data class Streaming(val width: Int, val height: Int, val mode: String, val fallback: String? = null, val codec: String = "h264") : ClientState
    /**
     * Only [ConnectionController] reports this: the connection dropped and is being re-made.
     * [message] says why, when the Mac told us; a dropped route has nothing to add.
     */
    data class Reconnecting(val last: Streaming, val message: String? = null, val attempt: Int = 0, val route: String? = null) : ClientState
    data class Failed(val reason: FailReason, val message: String) : ClientState
    data object Closed : ClientState
}

/**
 * One attempt at a connection to a Mac host: control over TCP, media over UDP, decode to the
 * attached Surface. [ConnectionController] makes a new one for every attempt and route.
 *
 * Callbacks to [listener] arrive on background threads, except the Closed that [close] reports on
 * the caller's thread. The first failure wins: a socket closed by the liveness check makes the
 * read fail too, and that later error must not hide the reason.
 */
class StreamClient(
    private val target: Target,
    /** The pairing to authenticate with; null pairs first. */
    private val pairing: Pairing?,
    /** Pair again when the host no longer knows [pairing]; only a manual entry, at the Mac, may. */
    private val repairOnUnknown: Boolean,
    @Volatile private var dataSaver: Boolean,
    private val listener: Listener,
    private val controlPort: Int = CONTROL_PORT,
    private val mediaPort: Int = CONTROL_PORT + 1,
) {
    interface Listener {
        fun onState(state: ClientState)
        fun onStream(stream: ClientState.Streaming)
        fun onStats(stats: Stats)
        fun onWarning(awdl: Boolean)
        fun onAuthenticated(pairing: Pairing, hostId: String, name: String, tailnet: List<String>, addr: String)
    }

    @Volatile private var closed = false
    @Volatile private var authenticated = false
    private val failure = AtomicReference<FailReason?>(null)
    // Its own thread: `timers` also does the blocking control writes, which a dead path can stall.
    private val liveness = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastPongMs = 0L
    /** When an authenticated media packet (a hello ack at least, once a second) last arrived. */
    @Volatile private var lastMediaMs = System.nanoTime() / 1_000_000
    private var socket: Socket? = null
    private var control: ControlStream? = null
    private var udp: DatagramSocket? = null
    private var cipher: SessionCipher? = null
    private var sessionId = 0L
    private val mediaSeq = AtomicLong(0)
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val decoder = VideoDecoder(onNeedKeyframe = { sendControl(JSONObject().put("t", "keyframe")) }, onRendered = ::rendered)
    private var surface: Surface? = null
    private var stream: ClientState.Streaming? = null
    /** The app is in front; set by [ConnectionController]. */
    @Volatile private var visible = true
    /** The Mac was asked to stop the video, and the pending ask; guarded by `this`, like [surface]'s writes. */
    private var hostPaused = false
    private var pauseTask: ScheduledFuture<*>? = null
    /** Bumped by every [holdStream]: a pause task that runs after a newer call does nothing. */
    private var holdGen = 0
    @Volatile private var display: DisplayConfig? = null

    // Media accounting, guarded by `this`.
    private val reassembler = Reassembler()
    private val losses = LossCounter()
    private var recoveredAtFeedback = 0L
    private var droppedAtFeedback = 0L
    private var bytes = 0L
    private var arrivalSum = 0.0
    private var arrivalCount = 0
    private var rendered = 0
    private var totalSum = 0.0
    private var decodeSum = 0.0
    private var windowStart = nowMicros()
    private var lastFrameId = 0L
    private var feedbackTotalSum = 0L
    private var feedbackFrames = 0
    private var feedbackDecodeSum = 0L

    // Clock: host µs ≈ client µs + offset, from the lowest-RTT pong among the recent ones.
    private val pongs = ArrayDeque<Pair<Long, Long>>()
    @Volatile private var offset: Long? = null
    @Volatile private var rttUs = 0L

    private val inputLock = Any()
    @Volatile private var inputsSent = 0
    private var inputSeq = 0L
    private val recentInputs = ArrayDeque<SequencedInput>()

    fun start(config: DisplayConfig) {
        display = config
        Thread({ run() }, "oripane-control").start()
    }

    fun close() {
        if (closed) return
        closed = true
        timers.shutdownNow()
        liveness.shutdownNow()
        decoder.shutdown()
        // Called from the UI thread, where sockets may not be touched; the goodbye lets the Mac
        // tear its virtual display down now rather than when the TCP connection times out.
        val c = control
        val s = socket
        val u = udp
        Thread {
            if (cipher != null) runCatching { c?.write(JSONObject().put("t", "bye").toString().toByteArray()) }
            runCatching { s?.close() }
            runCatching { u?.close() }
        }.start()
        listener.onState(ClientState.Closed)
    }

    fun attachSurface(s: Surface?) {
        // A resumed host starts a new stream with a keyframe; asking as well would send two.
        val resuming = synchronized(this) {
            surface = s
            hostPaused
        }
        holdStream()
        val st = stream
        if (s != null && st != null) decoder.configure(s, st.width, st.height, st.codec, requestKeyframe = !resuming) else if (s == null) decoder.release()
    }

    fun setVisible(v: Boolean) {
        visible = v
        holdStream()
    }

    /**
     * Asks the Mac to stop capturing and encoding while nothing can show the video, half a second
     * after the surface goes: a fold replaces the surface at once and must not rebuild the stream.
     * Before the first stream there is no surface in front either, which only the app being in
     * the background makes a reason to hold. Sent under the lock, a pause and a resume reach the
     * control writer in the order they were decided.
     */
    private fun holdStream() {
        synchronized(this) {
            pauseTask?.cancel(false)
            pauseTask = null
            val gen = ++holdGen
            if (cipher == null || closed) return
            val hold = surface == null && (stream != null || !visible)
            if (hold && !hostPaused) {
                pauseTask = timers.runCatching {
                    schedule({
                        synchronized(this@StreamClient) {
                            if (gen != holdGen || closed) return@synchronized
                            hostPaused = true
                            log("client pause")
                            sendControl(json("t" to "pause"))
                        }
                    }, PAUSE_DELAY_MS, TimeUnit.MILLISECONDS)
                }.getOrNull()
            } else if (!hold && hostPaused) {
                hostPaused = false
                log("client resume")
                sendControl(json("t" to "resume"))
            }
        }
    }

    /** Sends [config] if it changed, or anyway with [force]: a mode the Mac overrode looks unchanged here. */
    fun updateDisplay(config: DisplayConfig, force: Boolean = false) {
        if (config == display && !force) return
        display = config
        if (cipher != null) sendDisplay(config)
    }

    // MARK: control

    private fun run() {
        listener.onState(ClientState.Connecting)
        try {
            var c = connect() ?: return
            if (!authenticate(c, pairing ?: pair(c))) {
                // The host closes after auth_unknown, so pairing again takes a new connection.
                if (!repairOnUnknown) return fail(FailReason.AUTH_UNKNOWN, AUTH_UNKNOWN_MESSAGE)
                runCatching { socket?.close() }
                c = connect() ?: return
                if (!authenticate(c, pair(c))) return fail(FailReason.AUTH_UNKNOWN, AUTH_UNKNOWN_MESSAGE)
            }
            readLoop(c)
        } catch (e: PairDeniedException) {
            fail(FailReason.PAIR_DENIED, "The Mac declined the pairing.")
        } catch (e: Exception) {
            log("client error=${e.javaClass.simpleName}:${e.message}")
            if (authenticated) fail(FailReason.IO, "Lost the connection to the Mac.") else fail(FailReason.HANDSHAKE_FAILED, "Couldn't authenticate with the Mac.")
        }
    }

    /** A connected control stream with the handshake read timeout set, or null after failing. */
    private fun connect(): ControlStream? {
        val s = Socket()
        s.tcpNoDelay = true
        // Set first, so a close() during the connect closes this socket too.
        socket = s
        try {
            s.connect(InetSocketAddress(target.addr, controlPort), target.connectTimeoutMs)
            if (closed) {
                s.close()
                return null
            }
        } catch (e: Exception) {
            runCatching { s.close() }
            log("client connect_failed addr=${target.addr} error=${e.javaClass.simpleName}")
            fail(FailReason.CONNECT_FAILED, "Couldn't connect to ${target.addr}.")
            return null
        }
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        return ControlStream(s.getInputStream(), s.getOutputStream()).also { control = it }
    }

    private fun fail(reason: FailReason, message: String) {
        if (closed || !failure.compareAndSet(null, reason)) return
        log("client failed reason=$reason")
        listener.onState(ClientState.Failed(reason, message))
    }

    private fun pair(c: ControlStream): Pairing {
        val keys = X25519KeyPair.generate()
        c.write(json("t" to "pair_commit", "v" to 1, "name" to android.os.Build.MODEL, "commit" to Handshake.commitment(keys.publicRaw).b64()).toString().toByteArray())
        val hk = JSONObject(String(c.read()))
        val hostPub = hk.getString("hostPub").unb64()
        c.write(json("t" to "pair_reveal", "clientPub" to keys.publicRaw.b64()).toString().toByteArray())
        val code = Handshake.sas(hostPub, keys.publicRaw)
        log("pairing code=$code")
        listener.onState(ClientState.Pairing(code))
        // The person at the Mac takes as long as they take.
        socket?.soTimeout = 0
        val reply = JSONObject(String(c.read()))
        socket?.soTimeout = HANDSHAKE_TIMEOUT_MS
        if (reply.getString("t") != "pair_ok") throw PairDeniedException()
        val shared = keys.sharedSecret(hostPub)
        log("pairing approved addr=${target.addr}")
        return Pairing(reply.getString("clientId").unb64(), Handshake.pairKey(shared, hostPub, keys.publicRaw))
    }

    /** False when the host does not know [p]. */
    private fun authenticate(c: ControlStream, p: Pairing): Boolean {
        val cn = Handshake.randomBytes(32)
        c.write(json("t" to "auth_hello", "v" to 1, "clientId" to p.clientId.b64(), "nonce" to cn.b64()).toString().toByteArray())
        val ch = JSONObject(String(c.read()))
        if (ch.getString("t") == "auth_unknown") return false
        val hn = ch.getString("nonce").unb64()
        if (!Handshake.verify(ch.getString("proof").unb64(), p.pairKey, "host", cn, hn)) {
            throw SecurityException("Couldn't authenticate the Mac.")
        }
        c.write(json("t" to "auth_proof", "proof" to Handshake.proof(p.pairKey, "client", cn, hn).b64()).toString().toByteArray())
        val key = Handshake.sessionKey(p.pairKey, cn, hn)
        c.secure(key)
        val ok = JSONObject(String(c.read()))
        sessionId = ok.getLong("sessionId")
        val name = ok.getString("name")
        // Only addresses Tailscale could have handed out become routes to this Mac.
        val tailnet = ok.getJSONArray("tailnet").let { a -> (0 until a.length()).map { a.getString(it) } }.filter(::isTailnet)
        cipher = SessionCipher(key)
        authenticated = true
        socket?.soTimeout = 0
        log("auth ok session=$sessionId name=$name tailnet=${tailnet.joinToString(",")}")
        listener.onAuthenticated(p, ok.getString("hostId"), name, tailnet, target.addr)
        startMedia()
        sendLimit()
        display?.let { sendDisplay(it) }
        // Authenticated in the background, where no surface will come to start the hold.
        holdStream()
        return true
    }

    private fun readLoop(c: ControlStream) {
        while (!closed) {
            val m = JSONObject(String(c.read()))
            when (m.optString("t")) {
                "stream" -> {
                    val st = ClientState.Streaming(
                        m.getInt("w"), m.getInt("h"), m.optString("mode"), m.optString("fallback").ifEmpty { null },
                        codec = if (m.optString("codec") == "hevc") "hevc" else "h264",
                    )
                    stream = st
                    log("stream size=${st.width}x${st.height} mode=${st.mode} fallback=${st.fallback ?: "-"} codec=${st.codec}")
                    surface?.let { decoder.configure(it, st.width, st.height, st.codec, requestKeyframe = false) } ?: decoder.awaitSurface()
                    listener.onState(st)
                    listener.onStream(st)
                }
                "pong" -> pong(m.getLong("c"), m.getLong("h"))
                "warn" -> if (m.optString("code") == "awdl") listener.onWarning(m.optBoolean("active"))
                "bye" -> {
                    log("client bye reason=${m.optString("reason").ifEmpty { "-" }} detail=${m.optString("detail").ifEmpty { "-" }}")
                    when (m.optString("reason")) {
                        "replaced" -> return fail(FailReason.REPLACED, "Another device connected to the Mac.")
                        "stream_failed" -> return fail(FailReason.STREAM_FAILED, STREAM_FAILED_MESSAGE)
                    }
                    break
                }
            }
        }
        fail(FailReason.IO, "The Mac closed the connection.")
    }

    fun setDataSaver(on: Boolean) {
        if (on == dataSaver) return
        dataSaver = on
        if (cipher != null) sendLimit()
    }

    private fun sendLimit() {
        sendControl(json("t" to "limit", "maxBitrate" to if (dataSaver) DATA_SAVER_BPS else 0))
    }

    private fun sendDisplay(d: DisplayConfig) {
        // The stream is never larger than the panel, so a decoder that takes the panel takes the stream.
        val codecs = offeredCodecs(hardwareHevc = VideoDecoder.decodesInHardware("hevc", d.width, d.height))
        log("display w=${d.width} h=${d.height} dpi=${d.dpi} mode=${d.mode} scale=${d.scale} fps=${d.fps} ui_scale=${d.uiScale} codecs=${codecs.joinToString(",")}")
        // A JSONArray, not the List: Android's org.json writes a List it is handed as a string.
        sendControl(json("t" to "display", "w" to d.width, "h" to d.height, "dpi" to d.dpi, "hz" to d.hz, "mode" to d.mode, "scale" to d.scale, "fps" to d.fps, "uiScale" to d.uiScale, "codecs" to JSONArray(codecs)))
    }

    private fun sendControl(m: JSONObject) {
        val c = control ?: return
        if (cipher == null) return
        timers.runCatching {
            execute { runCatching { c.write(m.toString().toByteArray()) } }
        }
    }

    fun sendKey(code: Int, down: Boolean, meta: Int) {
        inputsSent++
        sendControl(json("t" to "key", "code" to code, "down" to down, "meta" to meta))
    }

    fun sendText(s: String) = sendControl(json("t" to "text", "s" to s))

    fun sendMac(a: MacAction) {
        inputsSent++
        sendControl(json("t" to "system", "action" to a.key))
    }

    private fun pong(c: Long, h: Long) {
        lastPongMs = System.nanoTime() / 1_000_000
        val now = nowMicros()
        val rtt = now - c
        synchronized(pongs) {
            pongs.addLast(rtt to (h - (c + now) / 2))
            while (pongs.size > 10) pongs.removeFirst()
            val best = pongs.minBy { it.first }
            offset = best.second
            rttUs = best.first
        }
    }

    // MARK: media

    private fun startMedia() {
        val u = DatagramSocket()
        u.receiveBufferSize = 4 shl 20
        u.connect(InetSocketAddress(target.addr, mediaPort))
        udp = u
        Thread({ receiveLoop(u) }, "oripane-udp").apply { priority = Thread.MAX_PRIORITY }.start()
        sendMedia(MediaType.Hello, ByteArray(0))
        timers.scheduleWithFixedDelay({ sendMedia(MediaType.Hello, ByteArray(0)) }, 1, 1, TimeUnit.SECONDS)
        timers.scheduleWithFixedDelay({ sendControl(json("t" to "ping", "c" to nowMicros())) }, 0, 1, TimeUnit.SECONDS)
        timers.scheduleWithFixedDelay(::feedback, 200, 200, TimeUnit.MILLISECONDS)
        timers.scheduleWithFixedDelay(::publishStats, 500, 500, TimeUnit.MILLISECONDS)
        lastPongMs = System.nanoTime() / 1_000_000
        lastMediaMs = lastPongMs
        liveness.scheduleWithFixedDelay(::checkLiveness, 500, 500, TimeUnit.MILLISECONDS)
    }

    private fun checkLiveness() {
        if (System.nanoTime() / 1_000_000 - lastPongMs < LIVENESS_MS) return
        fail(FailReason.LIVENESS, "The Mac stopped responding.")
        // Unblocks the read loop, which a vanished route would otherwise leave waiting forever.
        runCatching { socket?.close() }
        liveness.shutdown()
    }

    private fun sendMedia(type: MediaType, plain: ByteArray) {
        val u = udp ?: return
        val c = cipher ?: return
        val packet = MediaCodecs.seal(MediaHeader(type, sessionId, mediaSeq.getAndIncrement()), plain, c, Direction.MediaToHost)
        runCatching { u.send(DatagramPacket(packet, packet.size)) }.onFailure { log("udp send_failed type=$type error=${it.javaClass.simpleName}") }
    }

    private fun receiveLoop(u: DatagramSocket) {
        val buf = ByteArray(65536)
        val packet = DatagramPacket(buf, buf.size)
        // A connected UDP socket reports an ICMP unreachable as an exception on the next receive;
        // one such moment must not end the video for good, a lasting one goes to a reconnect.
        var failingSince = 0L
        while (!closed) {
            try {
                u.receive(packet)
            } catch (e: Exception) {
                if (closed) return
                val now = System.nanoTime() / 1_000_000
                if (failingSince == 0L) {
                    failingSince = now
                    log("udp receive_failed error=${e.javaClass.simpleName}:${e.message}")
                } else if (now - failingSince > RECEIVE_FAILURE_MS) {
                    fail(FailReason.IO, "Lost the video from the Mac.")
                    return
                }
                Thread.sleep(50)
                continue
            }
            if (failingSince != 0L) {
                log("udp receive_recovered ms=${System.nanoTime() / 1_000_000 - failingSince}")
                failingSince = 0L
            }
            val c = cipher ?: continue
            val (h, plain) = try {
                MediaCodecs.open(buf, packet.length, c, Direction.MediaToClient)
            } catch (e: Exception) {
                continue
            }
            if (h.sessionId != sessionId) continue
            lastMediaMs = System.nanoTime() / 1_000_000
            val frame = synchronized(this) {
                losses.onPacket(h.seq)
                bytes += packet.length
                val fp = when (h.type) {
                    MediaType.VideoFragment -> VideoFragment.decode(plain)
                    MediaType.VideoParity -> VideoParity.decode(plain)
                    else -> null
                } ?: return@synchronized null
                val droppedBefore = reassembler.framesDropped
                val expiredBefore = reassembler.framesExpired
                val f = reassembler.add(fp)
                if (reassembler.framesExpired > expiredBefore) log("decoder expired frames=${reassembler.framesExpired - expiredBefore} reason=pending_full")
                if (reassembler.framesDropped > droppedBefore || reassembler.framesExpired > expiredBefore) decoder.markBroken()
                if (f != null) {
                    lastFrameId = f.info.frameId
                    offset?.let { arrivalSum += (nowMicros() + it - f.info.captureUs) / 1000.0; arrivalCount++ }
                }
                f
            } ?: continue
            decoder.submit(frame)
        }
    }

    private fun rendered(captureUs: Long, decodeUs: Long) {
        val off = offset ?: return
        val total = nowMicros() + off - captureUs
        synchronized(this) {
            rendered++
            totalSum += total / 1000.0
            decodeSum += decodeUs / 1000.0
            feedbackTotalSum += total
            feedbackDecodeSum += decodeUs
            feedbackFrames++
        }
    }

    private fun feedback() {
        // The receive loop only sees a loss when a newer frame completes; on a still screen none does.
        synchronized(this) {
            val n = reassembler.expire()
            if (n > 0) {
                log("decoder expired frames=$n reason=timeout")
                decoder.markBroken()
            }
        }
        decoder.requestIfStillWaiting()
        val fb = synchronized(this) {
            // Read and reset in one go: a packet counted between the two would be lost from both windows.
            val (received, lost) = losses.take()
            val frames = feedbackFrames.coerceAtLeast(1)
            val f = Feedback(
                lastFrameId, received, lost,
                reassembler.framesRecovered - recoveredAtFeedback, reassembler.framesDropped - droppedAtFeedback,
                (feedbackDecodeSum / frames).coerceAtLeast(0), (feedbackTotalSum / frames).coerceAtLeast(0),
            )
            recoveredAtFeedback = reassembler.framesRecovered
            droppedAtFeedback = reassembler.framesDropped
            feedbackDecodeSum = 0
            feedbackTotalSum = 0
            feedbackFrames = 0
            windowLoss += lost
            windowReceived += received
            f
        }
        sendMedia(MediaType.Feedback, fb.encode())
    }

    private var windowLoss = 0L
    private var windowReceived = 0L

    private fun publishStats() {
        val s = synchronized(this) {
            val secs = (nowMicros() - windowStart) / 1e6
            val st = Stats(
                fps = rendered / secs,
                totalMs = if (rendered > 0) totalSum / rendered else 0.0,
                arrivalMs = if (arrivalCount > 0) arrivalSum / arrivalCount else 0.0,
                decodeMs = if (rendered > 0) decodeSum / rendered else 0.0,
                lossPercent = if (windowReceived + windowLoss > 0) 100.0 * windowLoss / (windowReceived + windowLoss) else 0.0,
                mbps = bytes * 8 / secs / 1e6,
                recovered = reassembler.framesRecovered,
                dropped = reassembler.framesDropped,
                rttMs = rttUs / 1000.0,
                decoder = decoder.decoderName + if (decoder.lowLatency) " (low-latency)" else "",
                noMedia = System.nanoTime() / 1_000_000 - lastMediaMs > NO_MEDIA_MS,
            )
            rendered = 0; totalSum = 0.0; decodeSum = 0.0; arrivalSum = 0.0; arrivalCount = 0
            bytes = 0; windowLoss = 0; windowReceived = 0; windowStart = nowMicros()
            st
        }
        log("stats inputs=$inputsSent fps=%.1f total_ms=%.1f arrival_ms=%.1f decode_ms=%.1f loss=%.2f mbps=%.2f recovered=%d dropped=%d rtt_ms=%.1f".format(
            s.fps, s.totalMs, s.arrivalMs, s.decodeMs, s.lossPercent, s.mbps, s.recovered, s.dropped, s.rttMs,
        ))
        listener.onStats(s)
    }

    // MARK: input

    fun sendInput(event: InputEvent) {
        // Not yet able to send (connecting, or the attempt of a reconnect): kept until connected, it
        // would ride on the first packet and click where the finger was on a frozen picture.
        if (udp == null || cipher == null) return
        inputsSent++
        val packet = synchronized(inputLock) {
            inputSeq++
            recentInputs.addLast(SequencedInput(inputSeq, event))
            while (recentInputs.size > 4) recentInputs.removeFirst()
            InputPacket.encode(recentInputs.toList())
        }
        sendMedia(MediaType.Input, packet)
    }

    private fun json(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    private class PairDeniedException : Exception()

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 5000
        private const val LIVENESS_MS = 3000L
        private const val RECEIVE_FAILURE_MS = 3000L
        private const val NO_MEDIA_MS = 3000L
        private const val PAUSE_DELAY_MS = 500L
        const val STREAM_FAILED_MESSAGE = "The Mac's video stopped."
        const val AUTH_UNKNOWN_MESSAGE = "The Mac no longer has this phone's pairing. On the same LAN as the Mac, add it by IP address and connect again."
    }
}
