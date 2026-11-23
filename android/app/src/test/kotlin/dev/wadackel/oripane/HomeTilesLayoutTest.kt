package dev.wadackel.oripane

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's tiles on both of the Fold's screens, with a Mac name of ordinary length. Native graphics,
 * since the legacy mode measures no real text and every name would fit.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTilesLayoutTest(private val size: FoldSize) {
    @get:Rule(order = 0)
    val screen = FoldScreenRule(size)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pairing = Pairing(ByteArray(16), ByteArray(32))
    private val studio = SavedMac(hostId = "studio", name = "Studio", pairing = pairing, lastUsed = 2)
    private val mini = SavedMac(hostId = "mini", name = "Margaret’s Mac Mini", pairing = pairing, lastUsed = 1)
    private val pro = SavedMac(hostId = "pro", name = "MacBook Pro", pairing = pairing, lastUsed = 0)

    @Test
    fun aMacNameOfOrdinaryLengthFitsOnItsTile() {
        compose.setContent {
            PageFrame {
                // Studio on the card, so the long name is drawn only on its tile.
                HomeScreen(
                    saved = listOf(studio, mini, pro), discovered = emptyList(), selected = HomeEntry.Saved(studio).key,
                    mode = "extend", failure = null,
                    onSelect = {}, onMode = {}, onConnect = {}, onDetail = {}, onAdd = {}, onSettings = {},
                )
            }
        }
        val text = compose.onNodeWithText(mini.name, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        assertWithMessage("${mini.name} cut short on the $size screen").that(layouts.single().isLineEllipsized(0)).isFalse()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun sizes() = FoldSize.entries.map { arrayOf<Any>(it) }
    }
}
