package dev.wadackel.oripane

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BandTintTest {
    private val video = StripArea(0f, 0f, 100f, 100f)

    /** A 4 by 4 copy: the left half white, the right half a dark blue. */
    private val pixels = IntArray(16) { i -> if (i % 4 < 2) 0xffffffff.toInt() else 0xff102040.toInt() }

    @Test
    fun aLightRegionGetsDarkerDotsOfItsOwnHue() {
        val c = dotColorFor(200f, 180f, 160f)
        assertThat(c.red).isLessThan(0.4f)
        assertThat(c.red).isGreaterThan(c.blue)
    }

    @Test
    fun aDarkRegionGetsLighterDots() {
        val c = dotColorFor(16f, 32f, 64f)
        assertThat(c.blue).isGreaterThan(64f / 255f)
        assertThat(c.blue).isGreaterThan(c.red)
    }

    @Test
    fun theAverageIsTakenFromThePartOfTheCopyUnderTheRegion() {
        assertThat(averageUnder(pixels, 4, 4, video, StripArea(0f, 0f, 40f, 100f))).isEqualTo(Triple(255f, 255f, 255f))
        assertThat(averageUnder(pixels, 4, 4, video, StripArea(60f, 0f, 100f, 100f))).isEqualTo(Triple(16f, 32f, 64f))
    }

    @Test
    fun aRegionOverTheBlackBarsCountsAsBlack() {
        assertThat(averageUnder(pixels, 4, 4, video, StripArea(120f, 0f, 160f, 100f))).isEqualTo(Triple(0f, 0f, 0f))
        // Half on the video's white, half on the bar beside it.
        val (r, _, _) = averageUnder(pixels, 4, 4, StripArea(0f, 0f, 40f, 100f), StripArea(-20f, 0f, 20f, 100f))
        assertThat(r).isWithin(0.5f).of(127.5f)
    }

    @Test
    fun theNeutralTintIsAGrey() {
        val c = BandTint.neutral.vertical
        assertThat(c).isEqualTo(Color(c.red, c.red, c.red))
    }

    @Test
    fun aColouredFrameGivesItsHueAtASetLightnessAndAGreyOneNone() {
        val blue = washColorFor(200f, 214f, 240f)!!
        assertThat(blue.blue).isGreaterThan(blue.red)
        assertThat(blue.blue).isGreaterThan(blue.green)
        val violet = washColorFor(40f, 30f, 70f)!!
        assertThat(violet.blue).isGreaterThan(violet.green)
        assertThat(washColorFor(128f, 128f, 128f)).isNull()
        assertThat(washColorFor(252f, 252f, 252f)).isNull()
        assertThat(washColorFor(2f, 2f, 3f)).isNull()
    }
}
