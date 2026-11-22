package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeysTest {
    private val c = listOf(KeyStroke(true, 31, 0), KeyStroke(false, 31, 0))
    private val a = listOf(KeyStroke(true, 29, 0), KeyStroke(false, 29, 0))
    private val b = listOf(KeyStroke(true, 30, 0), KeyStroke(false, 30, 0))
    private val shiftedA = listOf(KeyStroke(true, 59, 0x41), KeyStroke(true, 29, 0x41), KeyStroke(false, 29, 0x41), KeyStroke(false, 59, 0))
    private val at = listOf(KeyStroke(true, 77, 0), KeyStroke(false, 77, 0))
    private val table = mapOf('c' to c, 'a' to a, 'b' to b, 'A' to shiftedA, '@' to at)
    private val events: (Char) -> List<KeyStroke>? = { table[it] }

    private fun once(k: ModKey) = ModKeys().tapped(k)

    @Test
    fun aModifierStepsThroughOnceAndLockedBackToOff() {
        var m = ModKeys()
        assertThat(m[ModKey.CMD]).isEqualTo(ModKeyState.OFF)
        m = m.tapped(ModKey.CMD)
        assertThat(m[ModKey.CMD]).isEqualTo(ModKeyState.ONCE)
        m = m.tapped(ModKey.CMD)
        assertThat(m[ModKey.CMD]).isEqualTo(ModKeyState.LOCKED)
        m = m.tapped(ModKey.CMD)
        assertThat(m[ModKey.CMD]).isEqualTo(ModKeyState.OFF)
        assertThat(m.anyOn).isFalse()
    }

    @Test
    fun aOnceModifierIsSpentByOneKeyAndALockedOneIsNot() {
        val m = ModKeys().tapped(ModKey.CMD).tapped(ModKey.SHIFT).tapped(ModKey.SHIFT)
        assertThat(m.meta).isEqualTo(0x10001)
        val after = m.afterKey()
        assertThat(after[ModKey.CMD]).isEqualTo(ModKeyState.OFF)
        assertThat(after[ModKey.SHIFT]).isEqualTo(ModKeyState.LOCKED)
    }

    @Test
    fun closingTheRowTurnsEveryModifierOff() {
        val m = ModKeys().tapped(ModKey.CMD).tapped(ModKey.CMD).tapped(ModKey.ALT).tapped(ModKey.SHIFT)
        val closed = m.cleared()
        assertThat(closed.anyOn).isFalse()
        assertThat(closed.meta).isEqualTo(0)
        ModKey.entries.forEach { assertThat(closed[it]).isEqualTo(ModKeyState.OFF) }
    }

    @Test
    fun commandOnceAndCSendsKey31WithTheCommandBit() {
        val p = KeyPlan.forText("c", once(ModKey.CMD), events)
        assertThat(p.sends).containsExactly(KeySend.Key(31, 0x10000))
        assertThat(p.modKeys.anyOn).isFalse()
    }

    @Test
    fun aShiftPressInTheMapGoesIntoTheKeysMetaInsteadOfBeingSent() {
        val p = KeyPlan.forText("A", ModKeys().tapped(ModKey.CMD).tapped(ModKey.CMD), events)
        assertThat(p.sends).containsExactly(KeySend.Key(29, 0x10001))
    }

    @Test
    fun anUnmappedCharacterOrADisallowedKeyGoesAsTextAndCountsAsAKey() {
        val p = KeyPlan.forText("あ@c", ModKeys().tapped(ModKey.CMD).tapped(ModKey.CMD), events)
        assertThat(p.sends).containsExactly(KeySend.Text("あ"), KeySend.Text("@"), KeySend.Key(31, 0x10000)).inOrder()
        assertThat(p.dropped).containsExactly("あ", "@").inOrder()
        val q = KeyPlan.forText("あc", once(ModKey.CMD), events)
        assertThat(q.sends).containsExactly(KeySend.Text("あ"), KeySend.Key(31, 0)).inOrder()
    }

    @Test
    fun anEmojiTypedWithAModifierOnGoesAsOneString() {
        val p = KeyPlan.forText("😀", once(ModKey.CMD), events)
        assertThat(p.sends).containsExactly(KeySend.Text("😀"))
        assertThat(p.dropped).containsExactly("😀")
    }

    @Test
    fun anEmojiBetweenKeysStaysWholeAndTheOnceModifierStillGoesOnTheFirstOnly() {
        val p = KeyPlan.forText("😀c", once(ModKey.CMD), events)
        assertThat(p.sends).containsExactly(KeySend.Text("😀"), KeySend.Key(31, 0)).inOrder()
    }

    @Test
    fun aKeyCodeZeroCountsAsUnmapped() {
        val p = KeyPlan.forText("x", once(ModKey.CMD)) { listOf(KeyStroke(true, 0, 0), KeyStroke(false, 0, 0)) }
        assertThat(p.sends).containsExactly(KeySend.Text("x"))
    }

    @Test
    fun aLineBreakSplitsTheTextAndTheOnceModifierGoesOnTheFirstKeyOnly() {
        val p = KeyPlan.forText("a\nb", once(ModKey.CMD), events)
        assertThat(p.sends).containsExactly(KeySend.Key(29, 0x10000), KeySend.Key(66, 0), KeySend.Key(30, 0)).inOrder()
    }

    @Test
    fun aLockedModifierGoesOnEveryKey() {
        val p = KeyPlan.forText("a\nb", ModKeys().tapped(ModKey.CTRL).tapped(ModKey.CTRL), events)
        assertThat(p.sends).containsExactly(KeySend.Key(29, 0x1000), KeySend.Key(66, 0x1000), KeySend.Key(30, 0x1000)).inOrder()
    }

    @Test
    fun theAllowListHasTheHostsKeysButNotTheModifiers() {
        assertThat(ALLOWED_KEYCODES).containsAtLeast(29, 54, 7, 16, 131, 142, 66, 67, 111, 61, 19, 22, 55, 68)
        assertThat(ALLOWED_KEYCODES).containsNoneOf(57, 58, 59, 60, 113, 114, 115, 116, 117, 118, 77, 0)
    }

    @Test
    fun softKeyboardKeysAreTheVirtualDeviceOrFlagged() {
        assertThat(isSoftKeyboardKey(-1, 0)).isTrue()
        assertThat(isSoftKeyboardKey(5, 0x2)).isTrue()
        assertThat(isSoftKeyboardKey(5, 0)).isFalse()
        assertThat(isSoftKeyboardKey(0, 0x8)).isFalse()
    }
}
