package dev.wadackel.oripane

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pictures in the README, written to `docs/images/`: the line drawing at the top and the
 * social preview from [DocsArt], and the feature pictures rendered from the app's own screens with
 * fixed sample data. The Mac on those is [MacDesktop], drawn in place of the video.
 *
 * Recorded and compared like the other screenshot tests (see `Screenshots.kt`): the command that
 * records them records these too. Each scene starts an activity of its own after setting the
 * screen size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocsScreenshotTest {
    // Fixes the time zone and the locale for the whole test; each scene sets its own size.
    @get:Rule(order = 0)
    val screen = FoldScreenRule(FoldSize.INNER)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    /**
     * [content] on a fresh activity at [size], drawn into a bitmap.
     *
     * The decor view is drawn directly: Compose's captureToImage waits for a frame that never
     * comes on Robolectric's paused looper.
     */
    private fun scene(size: FoldSize, content: @Composable () -> Unit): Bitmap {
        RuntimeEnvironment.setQualifiers(size.qualifiers)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { content() } }
            compose.waitForIdle()
            lateinit var bitmap: Bitmap
            scenario.onActivity {
                val root = it.window.decorView
                bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
            }
            return bitmap
        }
    }

    private fun save(image: Bitmap, name: String) {
        image.captureRoboImage("$IMAGES/$name.png", roborazziOptions = exact)
    }

    /** The Mac streaming, with [over] drawn on top as the streaming screen would. */
    @Composable
    private fun stream(over: @Composable BoxWithConstraintsScope.() -> Unit) {
        StreamFrame {
            MacDesktop(Modifier.fillMaxSize())
            over()
        }
    }

    @Composable
    private fun BoxWithConstraintsScope.radial(selected: Int) {
        val x = constraints.maxWidth / 2f
        val y = constraints.maxHeight / 2f
        val lift = 60 * LocalDensity.current.density
        val state = RadialMenuState(
            RadialEntry.defaults,
            touchX = x, touchY = y, centerX = x, centerY = y,
            fingerX = x, fingerY = y - lift, videoX = x, videoY = y,
            selected = selected, sticky = false,
        )
        RadialMenuOverlay(state, tint = videoTint, onPick = {}, onClose = {})
    }

    @Test
    fun heroShowsTheMacOnTheFoldInEitherTheme() {
        save(DocsArt.hero(DocsArt.light), "hero-light")
        save(DocsArt.hero(DocsArt.dark), "hero-dark")
    }

    @Test
    fun socialPreviewCarriesTheLogoTheTaglineAndTheFold() {
        save(DocsArt.socialPreview(RuntimeEnvironment.getApplication()), "social-preview")
    }

    @Test
    fun radialMenuOpenOverTheMac() {
        val menu = scene(FoldSize.INNER) { stream { radial(selected = 1) } }
        save(DocsImages.scaleToWidth(DocsImages.frame(menu, radius = 40f), 1200), "radial-menu")
    }

    companion object {
        /** From `android/app`, where the tests run. */
        const val IMAGES = "../../docs/images"
    }
}
