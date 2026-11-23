/**
 * Shared parts of the screenshot tests ([PagesScreenshotTest], [StreamScreenshotTest], and the
 * README's pictures in [DocsScreenshotTest]).
 *
 * The baselines are the PNGs in `android/app/src/test/screenshots/`, and for [DocsScreenshotTest]
 * the README's images in `docs/images/`. A local `tools/test` compares every shot with its
 * baseline pixel by pixel and fails on any difference. CI, whose machines render text differently,
 * runs the same tests without capturing: the compositions, clicks and scrolls are exercised there,
 * and so is every shot's check that text stays whole ([assertTextWhole]).
 *
 * After an intended change to how something looks, record the baselines again from `android/` and
 * commit them in the same pull request:
 *
 * ```
 * ./gw :app:testDebugUnitTest --tests 'dev.wadackel.oripane.*ScreenshotTest' --rerun -Proborazzi.test.record=true
 * ```
 *
 * A shot that is removed or renamed leaves its old PNG behind in either directory, which no test
 * notices: delete it in the same pull request.
 */
package dev.wadackel.oripane

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment

/**
 * The owner's Fold's two screens, both 420 dpi: the inner one open (2448x1848) and the cover one
 * (1248x1972). Qualifiers take whole dp, so a shot may be a pixel or two off the real screen.
 * The last two are the same screens at the largest system font size (2.0), the cover one also at
 * about 1.45 times the display size (609 dpi; at 840 nearly every label breaks): they take no
 * picture, and only check that text stays whole.
 */
enum class FoldSize(val qualifiers: String, val fontScale: Float = 1f, val pictures: Boolean = true) {
    INNER("en-rUS-w933dp-h704dp-land-420dpi"),
    COVER("en-rUS-w475dp-h751dp-port-420dpi"),
    INNER_LARGEST_FONT("en-rUS-w933dp-h704dp-land-420dpi", fontScale = 2f, pictures = false),
    COVER_LARGEST("en-rUS-w327dp-h518dp-port-609dpi", fontScale = 2f, pictures = false),
    ;

    companion object {
        /** The two screens at the default sizes, for a test about one layout rather than every text. */
        val screens = listOf(INNER, COVER)
    }
}

/**
 * Sets the screen size before the activity starts, and fixes the time zone and the locale that
 * the pages format dates and numbers with, and sets the font scale; puts all four back afterwards. The qualifiers' locale
 * does not reach `Locale.getDefault()`, which follows the JVM and can be Japanese on the owner's Mac.
 */
class FoldScreenRule(private val size: FoldSize) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val qualifiers = RuntimeEnvironment.getQualifiers()
            val fontScale = RuntimeEnvironment.getFontScale()
            val zone = TimeZone.getDefault()
            val locale = Locale.getDefault()
            RuntimeEnvironment.setQualifiers(size.qualifiers)
            RuntimeEnvironment.setFontScale(size.fontScale)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.US)
            try {
                base.evaluate()
            } finally {
                Locale.setDefault(locale)
                TimeZone.setDefault(zone)
                RuntimeEnvironment.setFontScale(fontScale)
                RuntimeEnvironment.setQualifiers(qualifiers)
            }
        }
    }
}

// The default comparator counts a pixel as the same below a colour distance of 0.007.
@OptIn(ExperimentalRoborazziApi::class)
internal val exact = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(
        imageComparator = SimpleImageComparator(maxDistance = 0f),
        resultValidator = ThresholdValidator(0f),
    ),
)

/**
 * Checks that every text on screen is whole ([assertTextWhole]), then captures the whole screen as
 * `<name>_<size>.png` for a size that takes [FoldSize.pictures]. The screen rather than the compose
 * root, because sheets and dialogs draw in windows of their own.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun ComposeContentTestRule.shot(name: String, size: FoldSize) {
    waitForIdle()
    assertTextWhole("${name}_${size.name.lowercase()}")
    if (!size.pictures) return
    captureScreenRoboImage("src/test/screenshots/${name}_${size.name.lowercase()}.png", roborazziOptions = exact)
}

/** A page as `MainActivity` hosts it. */
@Composable
fun PageFrame(content: @Composable () -> Unit) {
    OripaneTheme {
        Surface(Modifier.fillMaxSize(), color = Palette.bg) { content() }
    }
}

/** The streaming screen's box, which the parts over the video are placed in. */
@Composable
fun StreamFrame(content: @Composable BoxWithConstraintsScope.() -> Unit) {
    OripaneTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { content() }
    }
}

fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

val pairing = Pairing(ByteArray(16), ByteArray(32))

/** Streamed from before, so its home card carries the colour of its last frame. */
val studio = SavedMac(hostId = "studio", name = "Mac Studio", pairing = pairing, lanAddrs = listOf("192.168.1.20"), tailnet = listOf("100.64.0.9"), lastUsed = utc(2026, 10, 21, 22, 40), tint = 0xFF6F8FD0.toInt())

val mbp = SavedMac(hostId = "mbp", name = "MacBook Pro", pairing = pairing, tailnet = listOf("100.64.0.12"), lastUsed = utc(2026, 10, 18, 9, 15))

/** Shares [mbp]'s name, so its card shows its address. */
val mbp2 = SavedMac(hostId = "mbp2", name = "MacBook Pro", pairing = pairing, lanAddrs = listOf("192.168.1.31"), lastUsed = utc(2026, 10, 10, 18, 0))

val iMac = Discovered("iMac", "192.168.1.40", null)

/** [studio] as Bonjour finds it on this network. */
val studioNearby = Discovered("Mac Studio", "192.168.1.20", "studio")

val custom = RadialEntry.Custom(KeyEvent.KEYCODE_S, ModKey.CMD.meta or ModKey.SHIFT.meta, "Save as", RadialLook.Icon(RadialIcon.STAR))

val streaming = ClientState.Streaming(2448, 1848, "extend")

/** The wash colour of a light page with a blue cast, as the video under the plates and the menu sheet. */
val videoTint = Color(0xFF6F8FD0)

val stats = Stats(fps = 60.0, totalMs = 18.4, arrivalMs = 9.1, decodeMs = 3.2, lossPercent = 0.12, mbps = 11.8, recovered = 3, dropped = 0, rttMs = 6.5, decoder = "c2.qti.avc.decoder")
