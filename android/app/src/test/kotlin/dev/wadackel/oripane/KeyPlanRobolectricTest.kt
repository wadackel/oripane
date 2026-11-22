package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric's virtual keyboard map is not a device's, so these pin what it gives, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyPlanRobolectricTest {
    private val events = virtualKeyEvents()
    private val cmd = ModKeys().tapped(ModKey.CMD)

    @Test
    fun lowercaseCIsKey31WithOnlyTheModifierBits() {
        assertThat(KeyPlan.forText("c", cmd, events).sends).containsExactly(KeySend.Key(31, 0x10000))
    }

    @Test
    fun uppercaseAIsKey29WithShift() {
        assertThat(KeyPlan.forText("A", cmd, events).sends).containsExactly(KeySend.Key(29, 0x10001))
    }

    @Test
    fun atSignIsKey77HereWhichTheHostLacksSoItGoesAsText() {
        val p = KeyPlan.forText("@", cmd, events)
        assertThat(p.sends).containsExactly(KeySend.Text("@"))
        assertThat(p.dropped).containsExactly("@")
    }

    @Test
    fun japaneseTextGoesAsText() {
        assertThat(KeyPlan.forText("あ", cmd, events).sends).containsExactly(KeySend.Text("あ"))
    }
}
