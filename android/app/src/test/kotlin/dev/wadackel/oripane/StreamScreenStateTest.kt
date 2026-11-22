package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamScreenStateTest {
    private val lines = mutableListOf<String>()
    private val state = StreamScreenState { lines += it }

    private fun open(entries: List<RadialEntry?> = Settings.defaults.radialMenu) =
        state.openRadial(RadialMenuState.opened(entries, 500f, 500f, 0.3f, 0.4f, 1000f, 1000f, 120f))

    private fun streaming(width: Int = 1000, height: Int = 800, mode: String = "extend", fallback: String? = null) =
        ClientState.Streaming(width, height, mode, fallback)

    @Test
    fun aNewConnectionClosesTheSheetAndForgetsTheLastConnectionsStatus() {
        state.onStats(Stats())
        state.toggleStats()
        state.onAwdlWarning(true)
        state.setMenu(MenuState.MENU, "button")
        lines.clear()

        state.newConnection()

        assertThat(state.stats).isNull()
        assertThat(state.statsExpanded).isFalse()
        assertThat(state.awdl).isEqualTo(AwdlUi())
        assertThat(state.menu).isEqualTo(MenuState.CLOSED)
        assertThat(lines).isEmpty()
    }

    @Test
    fun theSheetLogsItsOpeningEachPageAndItsClosing() {
        state.setMenu(MenuState.MENU, "button")
        assertThat(lines).containsExactly("ui menu open reason=button")
        state.setMenu(MenuState.MAC, "item")
        assertThat(lines.last()).isEqualTo("ui menu page=mac")
        state.setMenu(MenuState.MAC, "item")
        assertThat(lines).hasSize(2)
        state.setMenu(MenuState.CLOSED, "back")
        assertThat(lines.last()).isEqualTo("ui menu close reason=back")
        assertThat(state.menu).isEqualTo(MenuState.CLOSED)
    }

    @Test
    fun aModePickedFromTheMenuIsToldWhenTheMacKeepsAnother() {
        state.modePicked("mirror")
        assertThat(lines).containsExactly("ui mode select=mirror")

        val stream = streaming(mode = "extend")
        assertThat(state.onStream(stream).notice).isEqualTo(ModeNotice.FIXED)
        assertThat(lines.last()).isEqualTo("ui mode_fixed requested=mirror got=extend")
        assertThat(state.streamMode).isEqualTo("extend")

        assertThat(state.onStream(stream).notice).isNull()
    }

    @Test
    fun aMatchFallbackIsToldOncePerConnectionAndAgainWhenMatchIsPickedAnew() {
        val stream = streaming(mode = "mirror", fallback = "match")
        assertThat(state.onStream(stream).notice).isEqualTo(ModeNotice.FALLBACK)
        assertThat(lines).containsExactly("ui mode_fallback requested=match got=mirror")
        assertThat(state.onStream(stream).notice).isNull()

        state.modePicked("match")
        assertThat(state.onStream(stream).notice).isEqualTo(ModeNotice.FALLBACK)

        state.newConnection()
        assertThat(state.onStream(stream).notice).isEqualTo(ModeNotice.FALLBACK)
    }

    @Test
    fun aNewPictureResetsTheZoomButTheFirstStreamDoesNot() {
        assertThat(state.onStream(streaming()).resetZoom).isFalse()
        val larger = streaming(width = 2000, height = 1600)
        assertThat(state.onStream(larger).resetZoom).isTrue()
        assertThat(state.onStream(larger).resetZoom).isFalse()
    }

    @Test
    fun theStatsDetailOpensOnlyOnceStatsArrived() {
        state.toggleStats()
        assertThat(state.statsExpanded).isFalse()
        assertThat(lines).isEmpty()

        state.onStats(Stats())
        state.toggleStats()
        assertThat(state.statsExpanded).isTrue()
        assertThat(lines).containsExactly("ui stats_detail=true")
        state.toggleStats()
        assertThat(state.statsExpanded).isFalse()
        assertThat(lines.last()).isEqualTo("ui stats_detail=false")
    }

    @Test
    fun awdlTurningOnShowsTheMarkAndANoteAndTheBannerComesFromTheMark() {
        state.onAwdlWarning(true)
        assertThat(lines).containsExactly("ui awdl_mark shown", "ui status_note text=\"${awdlNote.text}\"").inOrder()
        assertThat(state.note?.text).isEqualTo(awdlNote.text)
        lines.clear()
        state.tapAwdlMark()
        assertThat(lines).containsExactly("ui awdl_banner shown", "ui awdl_mark hidden").inOrder()
        lines.clear()
        state.dismissAwdlBanner()
        assertThat(lines).containsExactly("ui awdl_banner dismissed", "ui awdl_mark shown").inOrder()
    }

    @Test
    fun aNoteGoesOnlyWhenItsOwnTimeIsUp() {
        state.onStats(Stats(fps = 60.0, totalMs = 18.0))
        assertThat(state.note).isNull()
        state.onStats(Stats(fps = 60.0, totalMs = 140.0))
        val slow = state.note!!
        assertThat(slow.text).isEqualTo("Slow · 140 ms")
        state.onStats(Stats(fps = 60.0, totalMs = 20.0))
        val back = state.note!!
        assertThat(back.text).isEqualTo("Back to 20 ms")
        state.noteShown(slow.id)
        assertThat(state.note).isEqualTo(back)
        state.noteShown(back.id)
        assertThat(state.note).isNull()
    }

    @Test
    fun aLiftWithoutAPickLeavesTheRadialMenuOpenForATap() {
        open()
        assertThat(state.releaseRadial(500f, 500f, 24f)).isNull()
        assertThat(state.radial?.sticky).isTrue()
    }

    @Test
    fun aLiftOnAPlacePicksItsEntryAndClosesTheMenu() {
        open()
        assertThat(state.releaseRadial(500f, 380f, 24f)).isEqualTo(0)
        assertThat(state.pick(0)).isEqualTo(RadialChoice(RadialEntry.Builtin(RadialItem.MISSION_CONTROL), 0.3f, 0.4f))
        assertThat(state.radial).isNull()
    }

    @Test
    fun aMacKeyPressedAgainAndAgainLeavesTheMenuOpenWithNothingSelected() {
        open(listOf(RadialEntry.Builtin(RadialItem.VOLUME_UP), RadialEntry.Builtin(RadialItem.MISSION_CONTROL)))
        state.moveRadial(500f, 380f, 24f)

        assertThat(state.pick(0)?.entry).isEqualTo(RadialEntry.Builtin(RadialItem.VOLUME_UP))
        val ring = state.radial
        assertThat(ring).isNotNull()
        assertThat(ring?.sticky).isTrue()
        assertThat(ring?.selected).isNull()
    }

    @Test
    fun slidingOntoANewPlaceAsksForOneTick() {
        open()
        assertThat(state.moveRadial(505f, 505f, 24f)).isFalse()
        assertThat(state.moveRadial(500f, 380f, 24f)).isTrue()
        assertThat(state.moveRadial(505f, 370f, 24f)).isFalse()
        state.closeRadial("back")
        assertThat(state.moveRadial(500f, 380f, 24f)).isFalse()
    }

    @Test
    fun closingTheRadialMenuIsLoggedOnlyWhenItWasOpen() {
        state.closeRadial("menu")
        assertThat(lines).isEmpty()
        open()
        state.closeRadial("back")
        assertThat(lines).containsExactly("ui radial close reason=back")
        assertThat(state.radial).isNull()
    }

    @Test
    fun theSameEntryTwiceShowsTheToastTwice() {
        val entry = RadialEntry.Builtin(RadialItem.VOLUME_UP)
        state.showToast(entry)
        val first = state.radialToast
        state.showToast(entry)
        assertThat(state.radialToast).isNotEqualTo(first)
        assertThat(state.radialToast?.first).isEqualTo(entry)
        state.clearToast()
        assertThat(state.radialToast).isNull()
    }

    @Test
    fun theLockQuestionRemembersWhereItWasAskedFrom() {
        state.askLock("gesture")
        assertThat(state.lockQuestion).isTrue()
        assertThat(state.lockConfirmed()).isEqualTo("gesture")
        assertThat(state.lockQuestion).isFalse()
    }

    @Test
    fun closingTheOverlaysAlsoDropsTheLockQuestion() {
        open()
        state.askLock("radial")
        state.closeOverlays("resize")
        assertThat(state.radial).isNull()
        assertThat(state.lockQuestion).isFalse()
        assertThat(lines).containsExactly("ui radial close reason=resize")

        lines.clear()
        state.askLock("radial")
        state.closeOverlays("reconnect")
        assertThat(state.lockQuestion).isFalse()
        assertThat(lines).isEmpty()
    }

    @Test
    fun closingTheKeyRowTurnsEveryModifierOffBeforeLoggingTheClose() {
        state.openKeyboard()
        assertThat(state.updateModKeys(ModKeys().tapped(ModKey.CMD))).isTrue()
        assertThat(lines).containsExactly("ui modifier cmd=once")
        assertThat(state.updateModKeys(state.modKeys.tapped(ModKey.ALT))).isFalse()
        state.setPreedit("か", 1)

        state.closeKeyboard("close")

        assertThat(state.showKeyboard).isFalse()
        assertThat(state.preedit).isEqualTo("" to 0)
        assertThat(state.modKeys).isEqualTo(ModKeys())
        assertThat(lines.takeLast(3)).containsExactly("ui modifier cmd=off", "ui modifier alt=off", "ui keyboard close").inOrder()

        state.openKeyboard()
        lines.clear()
        state.closeKeyboard(null)
        assertThat(state.showKeyboard).isFalse()
        assertThat(lines.filter { it.startsWith("ui keyboard") }).isEmpty()
    }

    @Test
    fun theBandTouchFollowsTheFingerAndTheSideBandGivesItsNewPlaceWithinTheRange() {
        assertThat(state.onStrip(GestureEvent.StripRelocated(10f, 600f))).isNull()

        val lane = StripArea(0f, 100f, 50f, 1100f)
        state.stripAreaChanged(ScrollBands(ScrollStrip.LEFT, lane, lane.copy(top = 500f, bottom = 700f), StripArea(300f, 1900f, 700f, 2000f)))
        state.onStrip(GestureEvent.StripTouched(10f, 300f))
        assertThat(state.bandTouch).isEqualTo(BandTouch(false, 10f, 300f))
        state.onStrip(GestureEvent.StripMoved(12f, 320f))
        assertThat(state.bandTouch).isEqualTo(BandTouch(false, 12f, 320f))
        state.onStrip(GestureEvent.StripHeld)
        assertThat(state.stripRelocating).isTrue()
        assertThat(state.onStrip(GestureEvent.StripRelocated(10f, 600f))).isEqualTo(0.5f)
        assertThat(state.onStrip(GestureEvent.StripRelocated(10f, 0f))).isEqualTo(0.05f)
        state.onStrip(GestureEvent.StripReleased)
        assertThat(state.bandTouch).isNull()
        assertThat(state.stripRelocating).isFalse()

        state.onStrip(GestureEvent.StripTouched(400f, 1950f, horizontal = true))
        assertThat(state.bandTouch).isEqualTo(BandTouch(true, 400f, 1950f))
        state.onStrip(GestureEvent.StripHeld)
        state.resetStrip()
        assertThat(state.bands).isNull()
        assertThat(state.bandTouch).isNull()
        assertThat(state.stripRelocating).isFalse()
    }
}
