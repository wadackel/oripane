/**
 * What the stream screen shows over the video, for one activity: the menu sheet, the radial menu
 * with its toast and the Lock screen question, the status corner's details, the AWDL banner, the
 * key row and the scroll bands' touch and tint. Main thread only. MainActivity keeps the views, the touch
 * wiring, the viewport and everything sent to the Mac.
 */
package dev.wadackel.oripane

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What a new stream asks of the activity: the zoom back to 1, and what to tell the user. */
data class StreamChange(val resetZoom: Boolean, val notice: ModeNotice?)

/** The entry picked on the ring, with the point on the video it acts at. */
data class RadialChoice(val entry: RadialEntry, val videoX: Float, val videoY: Float)

class StreamScreenState(private val log: (String) -> Unit) {
    var stats by mutableStateOf<Stats?>(null)
        private set
    var awdl by mutableStateOf(AwdlUi())

    /** What the status dot is saying for a moment, if anything. */
    var note by mutableStateOf<StatusNote?>(null)
        private set
    var menu by mutableStateOf(MenuState.CLOSED)
        private set
    var statsExpanded by mutableStateOf(false)
        private set
    var streamMode by mutableStateOf("extend")
        private set
    var dataSaverOn by mutableStateOf(false)
        private set
    var showKeyboard by mutableStateOf(false)
        private set
    var modKeys by mutableStateOf(ModKeys())
        private set
    var radial by mutableStateOf<RadialMenuState?>(null)
        private set
    /** The entry the radial menu last sent, with a count, so the same entry twice shows twice. */
    var radialToast by mutableStateOf<Pair<RadialEntry, Int>?>(null)
        private set
    /** Lock screen was picked on the radial menu: the stream screen asks before sending it. */
    var lockQuestion by mutableStateOf(false)
        private set
    /** The scroll bands in the stream screen's pixels, null when there are none, with the finger on one while touched. */
    var bands by mutableStateOf<ScrollBands?>(null)
        private set
    var bandTouch by mutableStateOf<BandTouch?>(null)
        private set
    /** The side band was held still and the finger moves it. */
    var stripRelocating by mutableStateOf(false)
        private set
    var bandTint by mutableStateOf(BandTint.neutral)
        private set
    /** The soft keyboard's composition and cursor, shown over the row. */
    var preedit by mutableStateOf("" to 0)
        private set

    /** Where the Lock screen being confirmed came from, for the log. */
    private var lockSource = "radial"
    private var lastStream: ClientState.Streaming? = null
    /** The display mode picked from the menu, until the first `stream` after it says what the Mac did. */
    private var pendingMode: String? = null
    private var toastCount = 0
    private var noteCount = 0L

    // MARK: connection

    /** What the streaming screen shows belongs to one connection; a reconnect keeps it. */
    fun newConnection() {
        stats = null
        awdl = AwdlUi()
        note = null
        bandTint = BandTint.neutral
        menu = MenuState.CLOSED
        statsExpanded = false
        lastStream = null
        pendingMode = null
    }

    fun onStream(stream: ClientState.Streaming): StreamChange {
        val change = StreamChange(shouldResetZoom(lastStream, stream), modeNotice(pendingMode, lastStream, stream))
        change.notice?.let {
            log(if (it == ModeNotice.FALLBACK) "ui mode_fallback requested=match got=${stream.mode}" else "ui mode_fixed requested=$pendingMode got=${stream.mode}")
        }
        lastStream = stream
        streamMode = stream.mode
        pendingMode = null
        return change
    }

    fun modePicked(mode: String) {
        pendingMode = mode
        log("ui mode select=$mode")
    }

    fun onStats(stats: Stats) {
        val prev = statusColor(this.stats, false)
        this.stats = stats
        statusNote(prev, statusColor(stats, false), stats)?.let(::say)
    }

    /** Opens the status dot into [n] until [noteShown] is told it has been up for its time. */
    fun say(n: StatusNote) {
        note = n.copy(id = ++noteCount)
        log("ui status_note text=\"${n.text}\"")
    }

    /** The note [id] has been shown for its time; a newer one stays. */
    fun noteShown(id: Long) {
        if (note?.id == id) note = null
    }

    fun onDataSaver(active: Boolean) {
        dataSaverOn = active
    }

    // MARK: status corner and AWDL

    fun toggleStats() {
        if (stats == null) return
        statsExpanded = !statsExpanded
        log("ui stats_detail=$statsExpanded")
    }

    fun onAwdlWarning(on: Boolean) {
        val was = awdl.active
        updateAwdl(awdl.onWarning(on))
        if (on && !was) say(awdlNote)
    }

    fun dismissAwdlBanner() = updateAwdl(awdl.dismissed())

    fun tapAwdlMark() = updateAwdl(awdl.markTapped())

    private fun updateAwdl(next: AwdlUi) {
        val prev = awdl
        awdl = next
        if (next.banner != prev.banner) log("ui awdl_banner ${if (next.banner) "shown" else "dismissed"}")
        if (next.mark != prev.mark) log("ui awdl_mark ${if (next.mark) "shown" else "hidden"}")
    }

    // MARK: menu sheet

    fun setMenu(next: MenuState, reason: String) {
        val prev = menu
        if (next == prev) return
        menu = next
        when {
            prev == MenuState.CLOSED -> log("ui menu open reason=$reason")
            next == MenuState.CLOSED -> log("ui menu close reason=$reason")
            else -> log("ui menu page=${next.name.lowercase()}")
        }
    }

    // MARK: radial menu

    fun openRadial(ring: RadialMenuState) {
        radial = ring
    }

    /** True when the finger slid onto a new cell, which the activity answers with a tick. */
    fun moveRadial(x: Float, y: Float, deadZone: Float): Boolean {
        val s = radial ?: return false
        val next = s.moved(x, y, deadZone)
        radial = next
        return next.selected != null && next.selected != s.selected
    }

    /** The cell the finger lifted on, to [pick]; a lift on none leaves the ring open for a tap. */
    fun releaseRadial(x: Float, y: Float, deadZone: Float): Int? {
        val s = radial?.moved(x, y, deadZone) ?: return null
        val cell = s.selected
        if (cell == null) radial = s.copy(sticky = true)
        return cell
    }

    /**
     * The entry in [cell] of the open ring. An action pressed again and again
     * ([MacAction.keepsMenu]) leaves the ring open, around the same centre, for the next tap.
     */
    fun pick(cell: Int): RadialChoice? {
        val s = radial ?: return null
        val entry = s.entries.getOrNull(cell) ?: return null
        val send = entry.send
        radial = if (send is RadialSend.Mac && send.action.keepsMenu) s.copy(sticky = true, selected = null) else null
        return RadialChoice(entry, s.videoX, s.videoY)
    }

    fun closeRadial(reason: String) {
        if (radial == null) return
        radial = null
        log("ui radial close reason=$reason")
    }

    fun showToast(entry: RadialEntry) {
        radialToast = entry to ++toastCount
    }

    fun clearToast() {
        radialToast = null
    }

    // MARK: Lock screen question

    fun askLock(source: String) {
        lockSource = source
        lockQuestion = true
    }

    /** Closes the question and gives where it was asked from. */
    fun lockConfirmed(): String {
        lockQuestion = false
        return lockSource
    }

    fun dropLockQuestion() {
        lockQuestion = false
    }

    /**
     * Closes the ring and the lock question. closeRadial returns at once with no ring, so the lock
     * question is closed here itself: Lock screen picked on the ring closes the ring before asking.
     */
    fun closeOverlays(reason: String) {
        closeRadial(reason)
        dropLockQuestion()
    }

    // MARK: key row

    fun openKeyboard() {
        showKeyboard = true
    }

    fun setPreedit(text: String, cursor: Int) {
        preedit = text to cursor
    }

    /** Logs each modifier that changed; true when whether any is on changed. */
    fun updateModKeys(next: ModKeys): Boolean {
        val prev = modKeys
        if (next == prev) return false
        modKeys = next
        ModKey.entries.filter { prev[it] != next[it] }.forEach { log("ui modifier ${it.logName}=${next[it].logName}") }
        return prev.anyOn != next.anyOn
    }

    /** Hides the row, with every modifier off before the close is logged; [reason] null logs nothing. */
    fun closeKeyboard(reason: String?) {
        showKeyboard = false
        preedit = "" to 0
        updateModKeys(modKeys.cleared())
        if (reason != null) log("ui keyboard $reason")
    }

    // MARK: scroll bands

    fun resetStrip() {
        bands = null
        bandTouch = null
        stripRelocating = false
    }

    fun stripAreaChanged(b: ScrollBands?) {
        bands = b
    }

    fun tintChanged(t: BandTint) {
        bandTint = t
    }

    /** The bands' touches, in the screen's pixels, for their dots; a relocation gives the side band's new place. */
    fun onStrip(e: GestureEvent): Float? {
        when (e) {
            is GestureEvent.StripTouched -> bandTouch = BandTouch(e.horizontal, e.x, e.y)
            is GestureEvent.StripMoved -> bandTouch = bandTouch?.copy(x = e.x, y = e.y)
            GestureEvent.StripHeld -> stripRelocating = true
            is GestureEvent.StripRelocated -> return bands?.lane?.let { a -> ((e.y - a.top) / (a.bottom - a.top)).coerceIn(Settings.stripKnobRange) }
            GestureEvent.StripReleased -> {
                bandTouch = null
                stripRelocating = false
            }
            else -> {}
        }
        return null
    }
}
