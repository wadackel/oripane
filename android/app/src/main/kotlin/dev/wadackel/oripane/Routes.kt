package dev.wadackel.oripane

import java.security.MessageDigest

// The rules for remembering Macs, picking a route to one and recovering a dropped connection.
// Plain data in, plain data out, so they are tested on the JVM without a device.

/** A Mac this phone has authenticated with, keyed by the host's lasting `hostId`. */
data class SavedMac(
    val hostId: String,
    val name: String,
    val pairing: Pairing,
    /** LAN addresses that authenticated, newest first. */
    val lanAddrs: List<String> = emptyList(),
    /** The tailnet addresses the Mac last reported. */
    val tailnet: List<String> = emptyList(),
    val lastUsed: Long = 0,
    /** A name given on this phone; the Mac's own name never overwrites it. */
    val customName: String? = null,
    /**
     * The wash colour (ARGB) of the last frame seen from this Mac, which tints its home card; null
     * before a first session, or when that frame had too little colour. Kept on this phone only.
     */
    val tint: Int? = null,
) {
    val displayName get() = customName ?: name
}

/** A Bonjour result: the service name, its IPv4 address and the TXT `id` (the host's `hostId`). */
data class Discovered(val name: String, val addr: String, val id: String?)

enum class RouteKind(val label: String, val connectTimeoutMs: Int) {
    LAN("lan", 2000),
    TAILNET("tailnet", 5000),
}

data class Candidate(val kind: RouteKind, val addr: String) {
    val timeoutMs get() = kind.connectTimeoutMs
}

const val MAX_LAN_ADDRS = 4

/** Tailscale hands out 100.64.0.0/10. */
fun isTailnet(addr: String): Boolean {
    val parts = addr.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
    return octets[0] == 100 && (octets[1] and 0xC0) == 64
}

fun routeKind(addr: String) = if (isTailnet(addr)) RouteKind.TAILNET else RouteKind.LAN

/**
 * The record after an `auth_ok` reached through [addr]. An empty [tailnet] keeps the previous
 * list, so a Mac whose Tailscale was briefly down does not lose its way in from outside.
 */
fun SavedMac?.updated(hostId: String, name: String, pairing: Pairing, addr: String, tailnet: List<String>, now: Long): SavedMac {
    val lan = this?.lanAddrs.orEmpty()
    return SavedMac(
        hostId = hostId,
        name = name,
        pairing = pairing,
        lanAddrs = if (isTailnet(addr)) lan else (listOf(addr) + lan.filter { it != addr }).take(MAX_LAN_ADDRS),
        tailnet = tailnet.ifEmpty { this?.tailnet.orEmpty() },
        lastUsed = now,
        customName = this?.customName,
        tint = this?.tint,
    )
}

/**
 * Whether an `auth_ok` claiming the saved Mac [claimed] may update its record. `hostId` is public
 * (Bonjour TXT), so only a connection that proved that record's pairing key, or the re-pairing
 * of that record from a typed address ([repairOf]), may take it over; a fresh pairing or another
 * Mac's pairing claiming it would otherwise redirect the saved Mac's pairing and tailnet routes.
 * The key, not the client id: the id travels in the clear and a host picks it at pairing. An
 * unknown id is a new record, as when a Mac regenerated its `host.json`.
 */
fun mayUpdate(claimed: String, used: Pairing, repairOf: String?, saved: List<SavedMac>): Boolean {
    val existing = saved.firstOrNull { it.hostId == claimed } ?: return true
    return MessageDigest.isEqual(used.pairKey, existing.pairing.pairKey) || repairOf == existing.hostId
}

fun List<SavedMac>.upsert(mac: SavedMac): List<SavedMac> = filter { it.hostId != mac.hostId } + mac

/** The record whose known addresses include [addr], the most recently used when several do. */
fun List<SavedMac>.forAddress(addr: String): SavedMac? = filter { addr in it.lanAddrs || addr in it.tailnet }.maxByOrNull { it.lastUsed }

/**
 * Where to try a saved Mac, in order: the LAN address Bonjour found for it, the LAN addresses it
 * authenticated on before (for LANs Bonjour does not cross), then its tailnet addresses.
 */
fun routeCandidates(wifi: Boolean, discovered: List<Discovered>, mac: SavedMac): List<Candidate> {
    val out = mutableListOf<Candidate>()
    if (wifi) {
        discovered.firstOrNull { it.id == mac.hostId }?.let { out += Candidate(RouteKind.LAN, it.addr) }
        mac.lanAddrs.filter { a -> out.none { it.addr == a } }.forEach { out += Candidate(RouteKind.LAN, it) }
    }
    mac.tailnet.forEach { out += Candidate(RouteKind.TAILNET, it) }
    return out
}

/** The port a host listens on for control by default; media uses the next one. Only [launchPort] moves it. */
const val CONTROL_PORT = 47800

/**
 * The control port a launch asks for with `--ei port <n>`, or null to keep [CONTROL_PORT]. Only an
 * `Int` in 1–65534 counts, the range of the host's `--port`, since media takes the next port; a
 * `--es` string or an `--el` long is ignored rather than parsed, so the hook means one thing. It
 * is never saved, yet lasts as long as the task's intent, which a recreated activity gets back.
 */
fun launchPort(present: Boolean, value: Any?): Int? = if (present && value is Int && value in 1..65534) value else null

/** On Wi-Fi, how long Bonjour may take to find a saved Mac before a route away from the LAN is used. */
const val BONJOUR_WAIT_MS = 1500L

/** How long a LAN route may still connect after a tailnet route did: the LAN is the faster one. */
const val LAN_GRACE_MS = 300L

sealed interface RouteDecision {
    data class Pick(val index: Int) : RouteDecision
    /** A route still being tried, or a hold not over yet. */
    data object Wait : RouteDecision
    data object NoRoute : RouteDecision
}

/**
 * Which route to a saved Mac to authenticate on, from TCP connects tried on all of them at once.
 * Only the connects race: the host keeps one session, and a second authentication would replace
 * the first. A LAN route wins whenever it connects; a tailnet route waits [LAN_GRACE_MS] for one,
 * counted from the later of its own connect and the LAN route joining the race, and on Wi-Fi,
 * until Bonjour has had [BONJOUR_WAIT_MS] to find the Mac on this LAN, because a stale LAN
 * address that Bonjour would correct is the common case at home.
 */
class RouteRace(candidates: List<Candidate>, val startMs: Long) {
    private val routes = candidates.toMutableList()
    private val joinedAt = MutableList(routes.size) { startMs }
    private val connectedAt = MutableList<Long?>(routes.size) { null }
    private val gaveUp = MutableList(routes.size) { false }

    val candidates: List<Candidate> get() = routes

    /** A route found while racing; null when it is already in the race. */
    fun add(c: Candidate, atMs: Long): Int? {
        if (routes.any { it.addr == c.addr }) return null
        routes += c
        joinedAt += atMs
        connectedAt += null
        gaveUp += false
        return routes.size - 1
    }

    fun succeeded(i: Int, atMs: Long) {
        connectedAt[i] = atMs
    }

    fun failed(i: Int) {
        gaveUp[i] = true
    }

    /** The routes that connected, in their order: after [RouteDecision.Pick], the ones to try next. */
    fun reachable(): List<Candidate> = routes.indices.filter { connectedAt[it] != null }.map { routes[it] }

    fun decide(nowMs: Long, wifi: Boolean, bonjourSeen: Boolean): RouteDecision {
        firstConnected(RouteKind.LAN)?.let { return RouteDecision.Pick(it) }
        val bonjourHold = wifi && !bonjourSeen && nowMs < startMs + BONJOUR_WAIT_MS
        val tailnet = firstConnected(RouteKind.TAILNET)
        if (tailnet != null) {
            val lanSettled = lanGraceEnds().all { nowMs >= it }
            return if (lanSettled && !bonjourHold) RouteDecision.Pick(tailnet) else RouteDecision.Wait
        }
        if (routes.indices.any(::pending) || bonjourHold) return RouteDecision.Wait
        return RouteDecision.NoRoute
    }

    /** When a hold in force at [nowMs] ends, to decide again then; null when only a connect can change it. */
    fun nextCheckMs(nowMs: Long, wifi: Boolean, bonjourSeen: Boolean): Long? {
        val ends = lanGraceEnds().toMutableList()
        if (wifi && !bonjourSeen) ends += startMs + BONJOUR_WAIT_MS
        return ends.filter { it > nowMs }.minOrNull()
    }

    private fun firstConnected(kind: RouteKind) = routes.indices.firstOrNull { routes[it].kind == kind && connectedAt[it] != null }

    private fun pending(i: Int) = connectedAt[i] == null && !gaveUp[i]

    /** When each LAN route still being tried stops holding up a connected tailnet route; none before one connected. */
    private fun lanGraceEnds(): List<Long> {
        val tailnetAt = routes.indices.filter { routes[it].kind == RouteKind.TAILNET }.mapNotNull { connectedAt[it] }.minOrNull() ?: return emptyList()
        return routes.indices.filter { routes[it].kind == RouteKind.LAN && pending(it) }.map { maxOf(tailnetAt, joinedAt[it]) + LAN_GRACE_MS }
    }
}

enum class DataSaver(val key: String) {
    AUTO("auto"),
    ALWAYS("always"),
    OFF("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** Whether to ask the host for the data-saving bitrate cap. */
fun dataSaverActive(setting: DataSaver, wifi: Boolean) = when (setting) {
    DataSaver.ALWAYS -> true
    DataSaver.OFF -> false
    DataSaver.AUTO -> !wifi
}

const val DATA_SAVER_BPS = 4_000_000

/** Wait before reconnect attempt [attempt] (1-based): the first goes at once, then 0.5, 1, 2, 4, 5 s… */
fun reconnectDelayMs(attempt: Int): Long = when {
    attempt <= 1 -> 0
    attempt >= 6 -> 5000
    else -> 500L shl (attempt - 2)
}

/** Reconnect attempts stop this long after they began: the Mac is not coming back. */
const val RECONNECT_LIMIT_MS = 120_000L

/** A stream that lasts this long ends the run of reconnect attempts; one that drops sooner continues it. */
const val STREAM_SURVIVES_MS = 10_000L

/** A connection left in the background this long is closed, so the Mac's display goes. */
const val BACKGROUND_CLOSE_MS = 60_000L

fun reconnectGaveUp(startedMs: Long, nowMs: Long) = startedMs > 0 && nowMs - startedMs > RECONNECT_LIMIT_MS

/**
 * Why a connection failed. [GAVE_UP] comes from no client: the reconnect attempts ran out without
 * the Mac coming back. Unlike [CONNECT_FAILED] it does not send a connection typed into Add a Mac
 * back to its field, since that address did connect once.
 */
enum class FailReason { CONNECT_FAILED, HANDSHAKE_FAILED, AUTH_UNKNOWN, PAIR_DENIED, REPLACED, LIVENESS, IO, STREAM_FAILED, GAVE_UP }

enum class NextStep {
    /** Try the next route candidate; with none left, wait for the next attempt (or give up). */
    NEXT_CANDIDATE,
    /** Back off, then try again. */
    RETRY_LATER,
    RETRY_NOW,
    STOP,
}

/**
 * What a connection does after [reason]. Until this connection has seen `auth_ok` once, nothing
 * reconnects: a Mac that never accepted us is the user's to look at, not something to retry.
 */
fun nextStep(reason: FailReason, authenticated: Boolean): NextStep = when (reason) {
    FailReason.CONNECT_FAILED -> NextStep.NEXT_CANDIDATE
    FailReason.AUTH_UNKNOWN, FailReason.PAIR_DENIED, FailReason.REPLACED, FailReason.GAVE_UP -> NextStep.STOP
    // The Mac could not keep the video going: while it keeps failing, the waits between attempts grow.
    FailReason.HANDSHAKE_FAILED, FailReason.STREAM_FAILED -> if (authenticated) NextStep.RETRY_LATER else NextStep.STOP
    FailReason.LIVENESS, FailReason.IO -> if (authenticated) NextStep.RETRY_NOW else NextStep.STOP
}
