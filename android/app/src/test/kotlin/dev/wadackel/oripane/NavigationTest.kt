package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavigationTest {
    @Test
    fun everyScreenButTheGestureMapGoesBackToTheConnections() {
        assertThat(Screen.Connections.parent()).isNull()
        assertThat(Screen.Add.parent()).isEqualTo(Screen.Connections)
        assertThat(Screen.Detail("studio").parent()).isEqualTo(Screen.Connections)
        assertThat(Screen.Settings.parent()).isEqualTo(Screen.Connections)
    }

    @Test
    fun theGestureMapGoesBackToSettingsWhichKeepsTheGesturesSection() {
        assertThat(Screen.Map(TouchMode.DIRECT).parent()).isEqualTo(Screen.Settings)
        assertThat(Screen.Map(TouchMode.TRACKPAD).parent()).isEqualTo(Screen.Settings)
    }

    @Test
    fun theRadialMenuEditorGoesBackToSettingsAndAShortcutToTheEditor() {
        assertThat(Screen.RadialMenu.parent()).isEqualTo(Screen.Settings)
        assertThat(Screen.RadialShortcut(2, false).parent()).isEqualTo(Screen.RadialMenu)
        assertThat(Screen.RadialShortcut(2, true).parent()).isEqualTo(Screen.RadialMenu)
        // PageStack tells screens apart by their string.
        assertThat(Screen.RadialShortcut(2, false).toString()).isNotEqualTo(Screen.RadialShortcut(2, true).toString())
    }

    @Test
    fun aGestureShortcutGoesBackToItsModesGestures() {
        for (mode in TouchMode.entries) {
            assertThat(Screen.GestureShortcut(mode, Gesture.FOUR_FINGER_PINCH, false).parent()).isEqualTo(Screen.Map(mode))
        }
        assertThat(pageDepth(Screen.GestureShortcut(TouchMode.TRACKPAD, Gesture.TWO_FINGER_LONG_PRESS, true), Screen::parent)).isEqualTo(3)
        assertThat(Screen.GestureShortcut(TouchMode.TRACKPAD, Gesture.TWO_FINGER_LONG_PRESS, false).toString())
            .isNotEqualTo(Screen.GestureShortcut(TouchMode.DIRECT, Gesture.TWO_FINGER_LONG_PRESS, false).toString())
    }

    @Test
    fun depthCountsTheStepsBackToTheTop() {
        assertThat(pageDepth(Screen.Connections, Screen::parent)).isEqualTo(0)
        assertThat(pageDepth(Screen.Settings, Screen::parent)).isEqualTo(1)
        assertThat(pageDepth(Screen.Map(TouchMode.DIRECT), Screen::parent)).isEqualTo(2)
    }

    @Test
    fun goingBackLeavesOnlyThePageGoneBackFrom() {
        assertThat(pagesLeft(Screen.Map(TouchMode.DIRECT), Screen.Settings, Screen::parent))
            .containsExactly(Screen.Map(TouchMode.DIRECT))
    }

    @Test
    fun goingBackTwoStepsLeavesBothPagesOnTheWay() {
        assertThat(pagesLeft(Screen.Map(TouchMode.DIRECT), Screen.Connections, Screen::parent))
            .containsExactly(Screen.Map(TouchMode.DIRECT), Screen.Settings)
    }

    @Test
    fun goingDeeperOrStayingLeavesNothing() {
        assertThat(pagesLeft(Screen.Settings, Screen.Map(TouchMode.DIRECT), Screen::parent)).isEmpty()
        assertThat(pagesLeft(Screen.Settings, Screen.Settings, Screen::parent)).isEmpty()
    }

    @Test
    fun movingToAnotherBranchLeavesThePagesOffItsPath() {
        assertThat(pagesLeft(Screen.Map(TouchMode.DIRECT), Screen.Detail("studio"), Screen::parent))
            .containsExactly(Screen.Map(TouchMode.DIRECT), Screen.Settings)
    }

    @Test
    fun onlyTheLiveSettingsMacAndDisplayModePagesHaveAPageToGoBackTo() {
        assertThat(MenuState.SETTINGS.parentPage()).isEqualTo(MenuState.MENU)
        assertThat(MenuState.MAC.parentPage()).isEqualTo(MenuState.MENU)
        assertThat(MenuState.DISPLAY.parentPage()).isEqualTo(MenuState.MENU)
        assertThat(MenuState.MENU.parentPage()).isNull()
        assertThat(MenuState.CLOSED.parentPage()).isNull()
    }

    @Test
    fun streamingAndReconnectingShareOneScene() {
        val streaming = ClientState.Streaming(1920, 1080, "extend")
        assertThat(sceneKey(streaming)).isEqualTo(Scene.STREAM)
        assertThat(sceneKey(ClientState.Reconnecting(streaming))).isEqualTo(Scene.STREAM)
    }

    @Test
    fun notConnectedFailedAndClosedAreAllTheHomeScene() {
        assertThat(sceneKey(null)).isEqualTo(Scene.HOME)
        assertThat(sceneKey(ClientState.Failed(FailReason.CONNECT_FAILED, "unreachable"))).isEqualTo(Scene.HOME)
        assertThat(sceneKey(ClientState.Closed)).isEqualTo(Scene.HOME)
    }

    @Test
    fun connectingAndPairingAreScenesOfTheirOwn() {
        assertThat(sceneKey(ClientState.Connecting)).isEqualTo(Scene.CONNECTING)
        assertThat(sceneKey(ClientState.Pairing("123456"))).isEqualTo(Scene.PAIRING)
    }
}
