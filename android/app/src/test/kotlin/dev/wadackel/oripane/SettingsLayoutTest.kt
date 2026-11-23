package dev.wadackel.oripane

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings across a change of layout: a fold, an unfold, a split-screen window or a new display
 * size keeps the section and the open sheet, and Back from the side-by-side layout leaves Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsLayoutTest {
    @get:Rule(order = 0)
    val screen = FoldScreenRule(FoldSize.INNER)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var width by mutableStateOf(900.dp)
    private var backs by mutableIntStateOf(0)

    private fun settingsIn(width: () -> Dp) = compose.setContent {
        PageFrame {
            // The frame's Surface stretches its child to the screen; a Box of its own does not.
            Box {
                Box(Modifier.width(width()).fillMaxHeight()) {
                    SettingsScreen(Settings.defaults, onChange = {}, onReset = {}, onMap = {}, onRadialMenu = {}, onBack = { backs++ })
                }
            }
        }
    }

    /** Video shows, beside the index or as its page: its note is on no other page. */
    private fun showsVideo() = compose.onAllNodesWithText("Changes apply from the next connection.").fetchSemanticsNodes().isNotEmpty()

    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun aSectionPickedSideBySideOpensAsItsPageOnTheNarrowScreen() {
        settingsIn { width }
        compose.onNodeWithText("Scrolling").performClick()
        width = 475.dp
        compose.onNodeWithText("Finger").assertExists()
        compose.onNodeWithText("Touch & pointer").assertDoesNotExist()
        systemBack()
        compose.onNodeWithText("Touch & pointer").assertExists()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun theIndexStaysTheIndexWhenNoSectionWasPicked() {
        settingsIn { width }
        assertThat(showsVideo()).isTrue()
        width = 475.dp
        compose.onNodeWithText("Touch & pointer").assertExists()
        assertThat(showsVideo()).isFalse()
    }

    @Test
    fun aPageOpenWhenUnfoldingStaysInThePaneAndBackLeavesSettingsInOneStep() {
        width = 475.dp
        settingsIn { width }
        compose.onNodeWithText("Scrolling").performClick()
        width = 900.dp
        compose.onNodeWithText("Finger").assertExists()
        compose.onNodeWithText("Touch & pointer").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun aLicenceSheetStaysOpenAcrossTheFold() {
        settingsIn { width }
        compose.onNodeWithText("About").performClick()
        compose.onNodeWithText("Inter Tight").performScrollTo().performClick()
        compose.onNodeWithText("Aa Bb 0123 → Oripane").assertExists()
        width = 475.dp
        compose.onNodeWithText("Aa Bb 0123 → Oripane").assertExists()
        width = 900.dp
        compose.onNodeWithText("Aa Bb 0123 → Oripane").assertExists()
    }

    @Test
    fun aWindowTooNarrowForTheSegmentsShowsTheIndexAlone() {
        width = 640.dp
        settingsIn { width }
        assertThat(showsVideo()).isFalse()
        width = 704.dp
        assertThat(showsVideo()).isTrue()
    }

    @Test
    fun aNewDisplaySizeSwitchesTheLayoutWithoutLeavingSettings() {
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                PageFrame { SettingsScreen(Settings.defaults, onChange = {}, onReset = {}, onMap = {}, onRadialMenu = {}, onBack = {}) }
            }
        }
        assertThat(showsVideo()).isTrue()
        scale = 2f
        compose.waitForIdle()
        assertThat(showsVideo()).isFalse()
        scale = 1f
        compose.waitForIdle()
        assertThat(showsVideo()).isTrue()
    }
}
