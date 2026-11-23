package dev.wadackel.oripane

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's card at system text and display sizes beyond the default, on the cover screen. Native
 * graphics, since the legacy mode measures no real text and every name would fit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLayoutTest {
    @get:Rule(order = 0)
    val screen = FoldScreenRule(FoldSize.COVER)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val studio = SavedMac(hostId = "studio", name = "Studio", pairing = Pairing(ByteArray(16), ByteArray(32)), lastUsed = 1)

    private fun homeAt(displayScale: Float, fontScale: Float) = compose.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density * displayScale, fontScale)) {
            PageFrame {
                HomeScreen(
                    saved = listOf(studio), discovered = emptyList(), selected = null, mode = "extend", failure = null,
                    onSelect = {}, onMode = {}, onConnect = {}, onDetail = {}, onAdd = {}, onSettings = {},
                )
            }
        }
    }

    private val SemanticsNode.top get() = positionInRoot.y
    private val SemanticsNode.bottom get() = positionInRoot.y + size.height

    private fun assertModeNamesWhole(case: String) {
        for (name in displayModes.map { it.second }) {
            val text = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            text.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            val layout = layouts.single()
            assertWithMessage("$case: $name on one line").that(layout.lineCount).isEqualTo(1)
            assertWithMessage("$case: $name not cut short").that(layout.isLineEllipsized(0)).isFalse()
            val segment = compose.onNode(isSelectable() and hasText(name)).fetchSemanticsNode()
            assertWithMessage("$case: $name inside its segment").that(text.top >= segment.top && text.bottom <= segment.bottom).isTrue()
        }
    }

    @Test
    fun theModeNamesStayWholeAtTheLargestFontSize() {
        homeAt(displayScale = 1f, fontScale = 2f)
        assertModeNamesWhole("font 2.0")
    }

    @Test
    fun theModeNamesStayWholeAtTwiceTheDisplaySize() {
        homeAt(displayScale = 2f, fontScale = 1f)
        assertModeNamesWhole("display 2x")
    }

    @Test
    fun theModeNamesStayWholeAtTheLargestFontAndTwiceTheDisplaySize() {
        homeAt(displayScale = 2f, fontScale = 2f)
        assertModeNamesWhole("display 2x, font 2.0")
    }

    @Test
    fun theModeNamesFitAtTheDefaultSizes() {
        homeAt(displayScale = 1f, fontScale = 1f)
        assertModeNamesWhole("default")
    }
}
