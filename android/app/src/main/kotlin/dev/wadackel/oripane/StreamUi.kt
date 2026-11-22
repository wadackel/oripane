package dev.wadackel.oripane

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The streaming screen's sheet: closed, the menu page, or one of its pages (live settings, the Mac actions, the display mode). */
enum class MenuState { CLOSED, MENU, SETTINGS, MAC, DISPLAY }

/** Back on the streaming screen never disconnects: it walks the sheet's pages and opens it when closed. */
fun onBack(menu: MenuState) = when (menu) {
    MenuState.SETTINGS, MenuState.MAC, MenuState.DISPLAY -> MenuState.MENU
    MenuState.MENU -> MenuState.CLOSED
    MenuState.CLOSED -> MenuState.MENU
}

/** The sheet page back slides to; back on the menu page closes the sheet instead. */
fun MenuState.parentPage() = if (this == MenuState.SETTINGS || this == MenuState.MAC || this == MenuState.DISPLAY) MenuState.MENU else null

/** A corner of the screen for the menu button; the status dot takes the opposite one. */
enum class MenuCorner(val key: String, val top: Boolean, val start: Boolean) {
    TOP_START("topStart", true, true),
    TOP_END("topEnd", true, false),
    BOTTOM_START("bottomStart", false, true),
    BOTTOM_END("bottomEnd", false, false);

    val opposite get() = entries.first { it.top != top && it.start != start }

    companion object {
        const val KEY = "ui.menuCorner"

        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BOTTOM_END

        /** The corner nearest to a point on a screen of [width] by [height]. */
        fun nearest(x: Float, y: Float, width: Float, height: Float) =
            entries.first { it.top == (y < height / 2) && it.start == (x < width / 2) }
    }
}

enum class StatusColor(val logName: String) { GREY("grey"), RED("red"), YELLOW("yellow"), GREEN("green") }

/** The status dot's colour; the first matching row of the spec's table wins. */
fun statusColor(stats: Stats?, reconnecting: Boolean): StatusColor = when {
    reconnecting -> StatusColor.RED
    stats == null -> StatusColor.GREY
    // An idle screen sends no frames, so the latency is stale there and only loss, RTT and whether anything arrives count.
    (stats.fps > 0 && stats.totalMs >= 100) || stats.lossPercent >= 1 || stats.rttMs >= 100 || stats.noMedia -> StatusColor.YELLOW
    else -> StatusColor.GREEN
}

/**
 * A short message the status dot opens into for [ms] milliseconds, in the status colour [color].
 * An [always] note shows with Show latency off too: a reconnect and AWDL matter whether or not the
 * latency is shown. [id] tells two notes with the same words apart, so the second one is shown for its full time.
 */
data class StatusNote(val text: String, val color: StatusColor, val ms: Long, val always: Boolean = false, val id: Long = 0)

/** The note a change of the status colour from [prev] to [next] brings, or null when it brings none. */
fun statusNote(prev: StatusColor, next: StatusColor, stats: Stats?): StatusNote? = when {
    stats == null -> null
    // From grey too: a connection that is slow from its first stats is told so as well.
    prev != StatusColor.YELLOW && next == StatusColor.YELLOW -> StatusNote(slowText(stats), StatusColor.YELLOW, SLOW_NOTE_MS)
    prev == StatusColor.YELLOW && next == StatusColor.GREEN ->
        StatusNote(if (stats.fps > 0) "Back to ${stats.totalMs.roundToInt()} ms" else "Back to normal", StatusColor.GREEN, BACK_NOTE_MS)
    else -> null
}

/** Why the dot turned yellow, in the words of the first row of [statusColor]'s table that matched. */
fun slowText(st: Stats) = when {
    st.noMedia -> "No video from the Mac"
    st.fps > 0 && st.totalMs >= 100 -> "Slow · ${st.totalMs.roundToInt()} ms"
    st.lossPercent >= 1 -> "Losing packets · ${st.lossPercent.roundToInt()}%"
    else -> "Slow network · ${st.rttMs.roundToInt()} ms"
}

/** The note while a dropped connection is being re-made: why, when the Mac said, else the attempt and route. */
fun reconnectingText(message: String?, attempt: Int, route: String?) = listOfNotNull("Reconnecting…", message ?: reconnectDetail(attempt, route)).joinToString(" · ")

/** The note once the stream is back, naming the route it came back on. */
fun backOnNote(route: String?) = StatusNote(
    when (route) {
        RouteKind.LAN.label -> "Back on LAN"
        RouteKind.TAILNET.label -> "Back on Tailscale"
        else -> "Reconnected"
    },
    StatusColor.GREEN,
    BACK_NOTE_MS,
    always = true,
)

val awdlNote = StatusNote("AWDL is on · tap AWDL for help", StatusColor.YELLOW, AWDL_NOTE_MS, always = true)

const val SLOW_NOTE_MS = 3200L
const val BACK_NOTE_MS = 2000L
const val AWDL_NOTE_MS = 4000L

/**
 * The AWDL mark and its help for one connection: the mark shows while AWDL is on, and a tap on it
 * opens the help banner in its place. AWDL turning on is told by the status dot's note, not the
 * banner, so the banner only comes when asked for.
 */
data class AwdlUi(val active: Boolean = false, val banner: Boolean = false) {
    val mark get() = active && !banner

    fun onWarning(on: Boolean) = when {
        on && !active -> AwdlUi(active = true)
        !on -> copy(active = false, banner = false)
        else -> this
    }

    fun dismissed() = copy(banner = false)

    fun markTapped() = if (active) copy(banner = true) else this
}

/**
 * The part of the video on screen. [zoom] is relative to the frame, the video at 1x fitted inside
 * the screen: the zoomed video is `zoom` times the frame. ([left], [top]) is the top-left corner of
 * the part of the video the screen shows, in normalized video coordinates; on an axis where the
 * zoomed video fits the screen, the whole axis shows and the corner is 0. Every function takes the
 * [Placement] of the frame on the screen, so the same viewport stays valid as the screen changes.
 */
data class Viewport(val zoom: Float = 1f, val left: Float = 0f, val top: Float = 0f) {
    /** Within 1..[Placement.maxZoom], and never showing anything past the video's edges. */
    fun clamped(p: Placement): Viewport {
        val z = zoom.coerceIn(1f, p.maxZoom)
        return Viewport(z, left.coerceIn(0f, 1f - p.x.visible(z)), top.coerceIn(0f, 1f - p.y.visible(z)))
    }

    /**
     * A point in pixels from the frame's top-left, as fingers are measured, to the video under it.
     * A point off the video on screen, on a black bar, goes to the nearest point that shows.
     */
    fun toVideo(x: Float, y: Float, p: Placement) = p.x.toVideo(x, zoom, left) to p.y.toVideo(y, zoom, top)

    /** Scale about the top-left and shift, for a view filling the frame. */
    fun transform(p: Placement) = SurfaceTransform(zoom, p.x.translation(zoom, left), p.y.translation(zoom, top))
}

/**
 * The frame, [frameW] by [frameH] pixels, at ([frameX], [frameY]) on a screen of [screenW] by
 * [screenH]. The frame's own position is kept rather than half the difference of the sizes:
 * `FrameLayout` centres by integer division, half a pixel off.
 */
data class Placement(
    val frameW: Float,
    val frameH: Float,
    val screenW: Float = frameW,
    val screenH: Float = frameH,
    val frameX: Float = 0f,
    val frameY: Float = 0f,
) {
    val x get() = PlacementAxis(frameW, screenW, frameX)
    val y get() = PlacementAxis(frameH, screenH, frameY)

    /** The zoom at which the video just covers the screen, its black bars gone; 1 with no bars. */
    val fillZoom get() = max(x.ratio, y.ratio)

    /** 4, or more where filling the screen needs more. */
    val maxZoom get() = max(MAX_ZOOM, fillZoom)

    companion object {
        const val MAX_ZOOM = 4f
    }
}

/**
 * One axis of a [Placement]. An axis whose screen is unknown, or smaller than the frame, or whose
 * frame is empty, counts as a screen the size of the frame.
 */
class PlacementAxis(val frame: Float, screen: Float, offset: Float) {
    private val measured = frame > 0f && screen >= frame
    val screen = if (measured) screen else frame
    val offset = if (measured) offset else 0f
    val ratio = if (measured) screen / frame else 1f

    /** The share of the video the screen shows at [zoom]: all of it while the zoomed video fits. */
    fun visible(zoom: Float) = min(1f, ratio / zoom)

    /**
     * Where the zoomed video starts, from the frame's start. Once it is larger than the screen its
     * edges stay off screen; until then it is centred on the frame. Both are -0 at 1x, as the
     * frame-only transform always was.
     */
    fun translation(zoom: Float, start: Float) = if (zoom >= ratio) -start * zoom * frame - offset else -(frame * (zoom - 1f)) / 2f

    /** The pixels, from the frame's start, where the video shows on screen. */
    fun shown(zoom: Float, start: Float): Pair<Float, Float> {
        val from = translation(zoom, start) + offset
        return max(0f, from) - offset to min(screen, from + zoom * frame) - offset
    }

    fun toVideo(px: Float, zoom: Float, start: Float): Float {
        if (frame <= 0f) return start
        val (lo, hi) = shown(zoom, start)
        return (px.coerceIn(lo, hi) - translation(zoom, start)) / (zoom * frame)
    }

    /** The pixel, from the frame's start, where the video point [v] is. */
    fun toPixel(v: Float, zoom: Float, start: Float) = translation(zoom, start) + v * zoom * frame

    /** The start that puts the video point [v] at the pixel [px], before clamping. */
    fun startFor(v: Float, px: Float, zoom: Float): Float {
        if (frame <= 0f) return 0f
        return v - (px + offset).coerceIn(0f, screen) / (zoom * frame)
    }
}

data class SurfaceTransform(val scale: Float, val translationX: Float, val translationY: Float)

/** The largest size, in pixels, of a [videoW] by [videoH] video that fits [areaW] by [areaH]. */
fun fitInside(areaW: Int, areaH: Int, videoW: Int, videoH: Int): Pair<Int, Int> =
    if (areaW.toLong() * videoH > areaH.toLong() * videoW) {
        (areaH.toFloat() * videoW / videoH).roundToInt() to areaH
    } else {
        areaW to (areaW.toFloat() * videoH / videoW).roundToInt()
    }

/** What the phone says when the stream's display mode is not the one asked for. */
enum class ModeNotice(val text: String) {
    FALLBACK("This Mac can't use Match right now. Showing Mirror."),
    FIXED("The Mac has fixed the display mode."),
}

/**
 * What to tell the user about a new stream, given the mode they just picked ([requested], null when
 * they picked none) and the stream before it. A Match fallback is told once per connection, and
 * again whenever Match is picked anew; the same fallback after a fold or an automatic reconnect is not.
 */
fun modeNotice(requested: String?, prev: ClientState.Streaming?, next: ClientState.Streaming): ModeNotice? = when {
    next.fallback == "match" -> if (prev?.fallback != "match" || requested == "match") ModeNotice.FALLBACK else null
    requested != null && next.mode != requested -> ModeNotice.FIXED
    else -> null
}

/**
 * The AWDL banner, 560 dp at most and centred at the top, reaches the menu button or the status
 * dot in a top corner unless both corners' 56 dp fit beside it; it then sits below them instead.
 * Leaving the corners free beside it would wrap its text to three lines and more on a cover screen.
 */
fun awdlBannerBelowCorners(widthDp: Float) = widthDp < AWDL_BANNER_MAX_DP + 2 * CORNER_DP

const val AWDL_BANNER_MAX_DP = 560
const val CORNER_DP = 56

/** A new size or display mode is a different picture, so the zoom goes back to 1. */
fun shouldResetZoom(prev: ClientState.Streaming?, next: ClientState.Streaming) = prev != null && prev != next

/** The line under the Mac's name on the menu sheet, as "Extend · LAN · 60 fps". */
fun menuStatus(mode: String, route: String?, stats: Stats?, reconnecting: Boolean): String {
    if (reconnecting) return "Reconnecting…"
    val modeName = if (mode == "test") "Test pattern" else displayModes.firstOrNull { it.first == mode }?.second
    val routeName = when (route) {
        RouteKind.LAN.label -> "LAN"
        RouteKind.TAILNET.label -> "Tailscale"
        else -> null
    }
    val fps = stats?.takeIf { it.fps > 0 }?.let { "${it.fps.roundToInt()} fps" }
    return listOfNotNull(modeName, routeName, fps).joinToString(" · ")
}

/** Which attempt this is and over which route, as "Attempt 3 · Tailscale". */
fun reconnectDetail(attempt: Int, route: String?): String? {
    if (attempt <= 0) return null
    val name = when (route) {
        RouteKind.LAN.label -> "LAN"
        RouteKind.TAILNET.label -> "Tailscale"
        else -> null
    }
    return "Attempt $attempt" + (name?.let { " · $it" } ?: "")
}
