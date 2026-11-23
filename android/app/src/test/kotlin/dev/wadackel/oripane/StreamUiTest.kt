package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamUiTest {
    @Test
    fun theAwdlBannerMovesBelowTheCornersOnlyWhereItWouldReachThem() {
        assertThat(awdlBannerBelowCorners(widthDp = 344f)).isTrue()
        assertThat(awdlBannerBelowCorners(widthDp = 671f)).isTrue()
        assertThat(awdlBannerBelowCorners(widthDp = 673f)).isFalse()
    }

    @Test
    fun theMacPageListsEveryActionOnce() {
        val shown = macMenuSections.flatMap { it.rows }.flatMap { it.actions }
        assertThat(shown).containsExactlyElementsIn(MacAction.entries)
        assertThat(shown).containsNoDuplicates()
        assertThat(macMenuSections.map { it.title }).containsExactly("Screen", "Apps", "Media", "Other").inOrder()
    }

    @Test
    fun backWalksTheSheetAndOpensItWhenClosed() {
        assertThat(onBack(MenuState.SETTINGS)).isEqualTo(MenuState.MENU)
        assertThat(onBack(MenuState.MAC)).isEqualTo(MenuState.MENU)
        assertThat(onBack(MenuState.DISPLAY)).isEqualTo(MenuState.MENU)
        assertThat(onBack(MenuState.MENU)).isEqualTo(MenuState.CLOSED)
        assertThat(onBack(MenuState.CLOSED)).isEqualTo(MenuState.MENU)
    }

    @Test
    fun theMenuButtonGoesToTheNearestCornerAndTheDotToTheOpposite() {
        assertThat(MenuCorner.nearest(100f, 100f, 1000f, 2000f)).isEqualTo(MenuCorner.TOP_START)
        assertThat(MenuCorner.nearest(900f, 1900f, 1000f, 2000f)).isEqualTo(MenuCorner.BOTTOM_END)
        assertThat(MenuCorner.nearest(900f, 100f, 1000f, 2000f)).isEqualTo(MenuCorner.TOP_END)
        assertThat(MenuCorner.TOP_START.opposite).isEqualTo(MenuCorner.BOTTOM_END)
        assertThat(MenuCorner.BOTTOM_START.opposite).isEqualTo(MenuCorner.TOP_END)
        assertThat(MenuCorner.of("nowhere")).isEqualTo(MenuCorner.BOTTOM_END)
    }

    @Test
    fun reconnectingIsGreyWhateverTheLastStatsSaid() {
        assertThat(statusColor(null, reconnecting = true)).isEqualTo(StatusColor.GREY)
        assertThat(statusColor(Stats(fps = 60.0, totalMs = 20.0), reconnecting = true)).isEqualTo(StatusColor.GREY)
        assertThat(statusColor(Stats(fps = 60.0, totalMs = 300.0), reconnecting = true)).isEqualTo(StatusColor.GREY)
    }

    @Test
    fun noStatsYetIsGrey() {
        assertThat(statusColor(null, reconnecting = false)).isEqualTo(StatusColor.GREY)
    }

    @Test
    fun latencyLossOrRttAtTheirLimitsAreYellow() {
        assertThat(statusColor(Stats(fps = 60.0, totalMs = 100.0), false)).isEqualTo(StatusColor.YELLOW)
        assertThat(statusColor(Stats(fps = 60.0, totalMs = 20.0, lossPercent = 1.0), false)).isEqualTo(StatusColor.YELLOW)
        assertThat(statusColor(Stats(fps = 60.0, totalMs = 20.0, rttMs = 100.0), false)).isEqualTo(StatusColor.YELLOW)
    }

    @Test
    fun noVideoComingIsYellowEvenWithAQuietLink() {
        assertThat(statusColor(Stats(fps = 0.0, totalMs = 0.0, lossPercent = 0.0, mbps = 0.0, rttMs = 3.0, noMedia = true), false)).isEqualTo(StatusColor.YELLOW)
        assertThat(statusColor(Stats(noMedia = true), reconnecting = true)).isEqualTo(StatusColor.GREY)
    }

    @Test
    fun aGoodLinkIsGreen() {
        assertThat(statusColor(Stats(fps = 60.0, totalMs = 99.0, lossPercent = 0.9, rttMs = 99.0), false)).isEqualTo(StatusColor.GREEN)
    }

    @Test
    fun anIdleScreenIgnoresTheStaleLatency() {
        assertThat(statusColor(Stats(fps = 0.0, totalMs = 500.0), false)).isEqualTo(StatusColor.GREEN)
        assertThat(statusColor(Stats(fps = 0.0, totalMs = 500.0, rttMs = 150.0), false)).isEqualTo(StatusColor.YELLOW)
    }

    @Test
    fun awdlShowsTheMarkAndTheBannerOnlyWhenTheMarkIsTapped() {
        var a = AwdlUi().onWarning(true)
        assertThat(a.banner).isFalse()
        assertThat(a.mark).isTrue()
        a = a.markTapped()
        assertThat(a.banner).isTrue()
        assertThat(a.mark).isFalse()
        a = a.dismissed().onWarning(true)
        assertThat(a.banner).isFalse()
        assertThat(a.mark).isTrue()
    }

    @Test
    fun aChangeOfTheStatusColourOpensTheDotIntoANote() {
        val slow = Stats(fps = 60.0, totalMs = 120.4)
        assertThat(statusNote(StatusColor.GREEN, StatusColor.YELLOW, slow)?.text).isEqualTo("Slow · 120 ms")
        assertThat(statusNote(StatusColor.YELLOW, StatusColor.GREEN, Stats(fps = 60.0, totalMs = 18.4))?.text).isEqualTo("Back to 18 ms")
        assertThat(statusNote(StatusColor.YELLOW, StatusColor.GREEN, Stats(fps = 0.0, totalMs = 300.0))?.text).isEqualTo("Back to normal")
        assertThat(statusNote(StatusColor.GREEN, StatusColor.GREEN, slow)).isNull()
        assertThat(statusNote(StatusColor.GREY, StatusColor.GREEN, slow)).isNull()
        assertThat(statusNote(StatusColor.GREY, StatusColor.YELLOW, slow)?.text).isEqualTo("Slow · 120 ms")
        assertThat(statusNote(StatusColor.YELLOW, StatusColor.YELLOW, slow)).isNull()
        assertThat(slowText(Stats(fps = 60.0, totalMs = 20.0, lossPercent = 2.4))).isEqualTo("Losing packets · 2%")
        assertThat(slowText(Stats(fps = 0.0, totalMs = 500.0, rttMs = 150.0))).isEqualTo("Slow network · 150 ms")
        assertThat(slowText(Stats(noMedia = true))).isEqualTo("No video from the Mac")
    }

    @Test
    fun theReconnectNotesNameWhyOrTheAttemptAndTheRouteBack() {
        assertThat(reconnectingText(null, 2, RouteKind.TAILNET.label)).isEqualTo("Reconnecting… · Attempt 2 · Tailscale")
        assertThat(reconnectingText("The Mac stopped the stream.", 2, RouteKind.LAN.label)).isEqualTo("Reconnecting… · The Mac stopped the stream.")
        assertThat(reconnectingText(null, 0, null)).isEqualTo("Reconnecting…")
        assertThat(backOnNote(RouteKind.LAN.label).text).isEqualTo("Back on LAN")
        assertThat(backOnNote(null).text).isEqualTo("Reconnected")
    }

    @Test
    fun theMenuSheetsStatusLineNamesTheModeTheRouteAndTheFrameRate() {
        assertThat(menuStatus("extend", RouteKind.LAN.label, Stats(fps = 59.6), false)).isEqualTo("Extend · LAN · 60 fps")
        assertThat(menuStatus("test", null, Stats(fps = 0.0), false)).isEqualTo("Test pattern")
        assertThat(menuStatus("mirror", RouteKind.TAILNET.label, null, true)).isEqualTo("Reconnecting…")
    }

    @Test
    fun awdlTurningOffHidesBothAndOnAgainBringsTheMarkOnly() {
        var a = AwdlUi().onWarning(true).onWarning(false)
        assertThat(a.banner).isFalse()
        assertThat(a.mark).isFalse()
        a = a.onWarning(true)
        assertThat(a.banner).isFalse()
        assertThat(a.mark).isTrue()
        assertThat(AwdlUi().markTapped()).isEqualTo(AwdlUi())
    }

    @Test
    fun aNewConnectionStartsWithoutBannerOrMark() {
        val fresh = AwdlUi()
        assertThat(fresh.banner).isFalse()
        assertThat(fresh.mark).isFalse()
        assertThat(fresh.onWarning(false)).isEqualTo(fresh)
    }

    @Test
    fun theViewportStaysInsideTheVideo() {
        val p = Placement(1000f, 800f)
        assertThat(Viewport(8f, 0.9f, -1f).clamped(p)).isEqualTo(Viewport(4f, 0.75f, 0f))
        assertThat(Viewport(0.5f, 0.3f, 0.3f).clamped(p)).isEqualTo(Viewport(1f, 0f, 0f))
        assertThat(Viewport(2f, 0.5f, 0.25f).clamped(p)).isEqualTo(Viewport(2f, 0.5f, 0.25f))
    }

    @Test
    fun theFramesCentreAtZoomTwoIsTheVideosCentre() {
        assertThat(Viewport(2f, 0.25f, 0.25f).toVideo(500f, 400f, Placement(1000f, 800f))).isEqualTo(0.5f to 0.5f)
    }

    @Test
    fun theSurfaceScalesAboutItsCornerAndShiftsByTheViewport() {
        val p = Placement(1000f, 800f)
        assertThat(Viewport(2f, 0.25f, 0.5f).transform(p)).isEqualTo(SurfaceTransform(2f, -500f, -800f))
        assertThat(Viewport().transform(p)).isEqualTo(SurfaceTransform(1f, -0f, -0f))
    }

    @Test
    fun theTransformFollowsANewFrameSize() {
        val v = Viewport(2f, 0.25f, 0.25f)
        assertThat(v.transform(Placement(2000f, 1600f))).isEqualTo(SurfaceTransform(2f, -1000f, -800f))
    }

    /** A 16:10 frame on a square screen: black bars of 187.5 px above and below it at 1x. */
    private val barred = Placement(1000f, 625f, 1000f, 1000f, 0f, 187.5f)

    @Test
    fun atOneTimesTheVideoSitsOnTheFrameAsWithoutBars() {
        assertThat(Viewport().transform(barred)).isEqualTo(Viewport().transform(Placement(1000f, 625f)))
        assertThat(Viewport().transform(barred)).isEqualTo(SurfaceTransform(1f, -0f, -0f))
    }

    @Test
    fun theFillZoomIsTheLargerRatioAndTheLimitStaysFourBelowIt() {
        assertThat(barred.fillZoom).isEqualTo(1.6f)
        assertThat(barred.maxZoom).isEqualTo(4f)
        assertThat(Placement(1000f, 625f).fillZoom).isEqualTo(1f)
    }

    @Test
    fun aZoomedVideoSmallerThanTheScreenIsCentredAndCannotMoveThatWay() {
        val v = Viewport(1.2f, 0.5f, 0.3f).clamped(barred)
        assertThat(v.top).isEqualTo(0f)
        assertThat(v.left).isWithin(1e-6f).of(1f - 1f / 1.2f)
        val t = v.transform(barred)
        // The zoomed video is 750 px tall: (1000 - 750) / 2 above it on screen.
        assertThat(barred.frameY + t.translationY).isWithin(1e-3f).of((1000f - 750f) / 2f)
    }

    @Test
    fun atTheFillZoomTheBarredAxisJustFillsTheScreen() {
        val v = Viewport(1.6f, 0.1f, 0.3f).clamped(barred)
        assertThat(v.top).isEqualTo(0f)
        assertThat(barred.frameY + v.transform(barred).translationY).isWithin(1e-3f).of(0f)
    }

    @Test
    fun aScreenMuchTallerThanTheFrameRaisesTheLimitToItsFillZoom() {
        val tall = Placement(1000f, 200f, 1000f, 1000f, 0f, 400f)
        assertThat(tall.maxZoom).isEqualTo(5f)
        assertThat(Viewport(8f).clamped(tall).zoom).isEqualTo(5f)
    }

    /** The barred frame with the key row's top 900 px down: the keyboard covers only black bar, 100 of its 187.5 px. */
    private val barredKeyboard = barred.copy(visibleH = 900f)

    @Test
    fun aVideoThatFitsAboveTheKeyboardRisesJustClearOfIt() {
        val small = Placement(1000f, 625f, 1000f, 2000f, 0f, 687.5f, visibleH = 1200f)
        // Centred, its bottom would be at 1312.5 px: 112.5 px under the key row.
        assertThat(Viewport().transform(small).translationY).isWithin(1e-3f).of(-112.5f)
        assertThat(Viewport().toVideo(500f, 0f, small).second).isWithin(1e-5f).of(112.5f / 625f)
    }

    @Test
    fun aVideoTallerThanThePartAboveTheKeyboardMovesDownToItsBottomAndNoFurther() {
        assertThat(barredKeyboard.y.ratio).isWithin(1e-6f).of(900f / 625f)
        val full = Placement(1000f, 2000f, visibleH = 1000f)
        assertThat(Viewport(1f, 0f, 0.9f).clamped(full).top).isEqualTo(0.5f)
        assertThat(Viewport(1f, 0f, 0.5f).transform(full).translationY).isEqualTo(-1000f)
        assertThat(full.y.shown(1f, 0.5f)).isEqualTo(0f to 1000f)
    }

    @Test
    fun theKeyboardNeverTakesTheFillZoomBelowOne() {
        assertThat(Placement(1000f, 2000f, visibleH = 1000f).fillZoom).isEqualTo(1f)
        assertThat(barredKeyboard.fillZoom).isWithin(1e-6f).of(900f / 625f)
    }

    @Test
    fun withoutTheKeyboardThePlacementIsTheOneBefore() {
        assertThat(barredKeyboard.withoutKeyboard()).isEqualTo(barred)
        assertThat(Viewport().transform(barredKeyboard.withoutKeyboard())).isEqualTo(SurfaceTransform(1f, -0f, -0f))
    }

    @Test
    fun aScreenSmallerThanTheFrameOrUnknownCountsAsTheFrame() {
        assertThat(Placement(1000f, 2000f, 320f, 470f, 0f, 0f).fillZoom).isEqualTo(1f)
        assertThat(Placement(1000f, 2000f, 0f, 0f, 0f, 0f).maxZoom).isEqualTo(4f)
        assertThat(Viewport(2f, 0.25f, 0.5f).toVideo(10f, 10f, Placement(0f, 0f))).isEqualTo(0.25f to 0.5f)
    }

    @Test
    fun theVideoFitsTheScreenByItsLimitingSide() {
        assertThat(fitInside(2000, 1000, 1000, 2000)).isEqualTo(500 to 1000)
        assertThat(fitInside(1000, 2000, 2000, 1000)).isEqualTo(1000 to 500)
        assertThat(fitInside(1000, 2000, 500, 1000)).isEqualTo(1000 to 2000)
        assertThat(fitInside(2176, 1812, 2880, 1800)).isEqualTo(2176 to 1360)
    }

    @Test
    fun aNewSizeOrModeResetsTheZoomButTheFirstStreamDoesNot() {
        val s = ClientState.Streaming(2208, 1768, "extend")
        assertThat(shouldResetZoom(null, s)).isFalse()
        assertThat(shouldResetZoom(s, s.copy())).isFalse()
        assertThat(shouldResetZoom(s, s.copy(width = 1768, height = 2208))).isTrue()
        assertThat(shouldResetZoom(s, s.copy(mode = "mirror"))).isTrue()
    }

    @Test
    fun theFirstMatchFallbackIsAnnouncedEvenRightAfterConnecting() {
        val mirrored = ClientState.Streaming(2176, 1812, "mirror", fallback = "match")
        assertThat(modeNotice(requested = null, prev = null, next = mirrored)).isEqualTo(ModeNotice.FALLBACK)
        assertThat(modeNotice(requested = "match", prev = ClientState.Streaming(2176, 1812, "extend"), next = mirrored)).isEqualTo(ModeNotice.FALLBACK)
    }

    /** A fold, a rotation or an automatic reconnect sends the same fallback again: once per connection is enough. */
    @Test
    fun aFallbackThatContinuesIsNotAnnouncedAgain() {
        val mirrored = ClientState.Streaming(2176, 1812, "mirror", fallback = "match")
        assertThat(modeNotice(requested = null, prev = mirrored, next = mirrored.copy(width = 1812, height = 2176))).isNull()
    }

    @Test
    fun choosingMatchAgainAfterAFallbackIsAnnouncedAgain() {
        val mirrored = ClientState.Streaming(2176, 1812, "mirror", fallback = "match")
        assertThat(modeNotice(requested = "match", prev = mirrored, next = mirrored)).isEqualTo(ModeNotice.FALLBACK)
    }

    @Test
    fun aModeTheMacFixedIsAnnouncedAsFixed() {
        val extend = ClientState.Streaming(2176, 1812, "extend")
        assertThat(modeNotice(requested = "mirror", prev = extend, next = extend)).isEqualTo(ModeNotice.FIXED)
        assertThat(modeNotice(requested = "match", prev = extend, next = extend)).isEqualTo(ModeNotice.FIXED)
        assertThat(modeNotice(requested = "mirror", prev = extend, next = extend.copy(mode = "mirror"))).isNull()
        assertThat(modeNotice(requested = null, prev = extend, next = extend)).isNull()
    }
}
