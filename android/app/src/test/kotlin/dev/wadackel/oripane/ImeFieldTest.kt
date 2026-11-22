package dev.wadackel.oripane

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Robolectric's input method manager cannot report `updateSelection`, so the settles are counted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImeFieldTest {
    private val texts = mutableListOf<String>()
    private val keys = mutableListOf<Int>()
    /** Texts and keys in the order they went out. */
    private val sent = mutableListOf<String>()
    private val preedits = mutableListOf<String>()
    private var settles = 0
    private var modifiers = false
    private var incognito = false
    private var keep = false
    private val logs = mutableListOf<String>()

    private val field = ImeField(
        RuntimeEnvironment.getApplication(),
        ImeField.Callbacks(
            onText = { texts += it; sent += "text:$it" },
            onKey = { keys += it; sent += "key:$it" },
            onPreedit = { text, _ -> preedits += text },
            onSelectionSettled = { _, _, _ -> settles++ },
            modifiersOn = { modifiers },
            incognito = { incognito },
            onPhysicalKey = { false },
            keepOnDetach = { keep },
            log = { logs += it },
        ),
    )

    private fun connect(info: EditorInfo = EditorInfo()) = field.onCreateInputConnection(info)

    @Test
    fun theEditorTakesTextWithoutSuggestionsAndAPasswordLayoutWhileAModifierIsOn() {
        val plain = EditorInfo().also { connect(it) }
        assertThat(plain.inputType).isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        modifiers = true
        val modified = EditorInfo().also { connect(it) }
        assertThat(modified.inputType).isEqualTo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
        for (info in listOf(plain, modified)) {
            assertThat(info.imeOptions).isEqualTo(EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI)
            assertThat(listOf(info.initialSelStart, info.initialSelEnd, info.initialCapsMode)).containsExactly(0, 0, 0)
        }
    }

    @Test
    fun theKeyboardLearnsNothingWhileIncognitoIsOnAndEachEditorSaysWhich() {
        val plain = EditorInfo().also { connect(it) }
        incognito = true
        val secret = EditorInfo().also { connect(it) }
        val base = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertThat(plain.imeOptions).isEqualTo(base)
        assertThat(secret.imeOptions).isEqualTo(base or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertThat(secret.inputType).isEqualTo(plain.inputType)
        assertThat(logs).containsExactly("ime editor incognito=false", "ime editor incognito=true").inOrder()
    }

    @Test
    fun aBatchSettlesTheSelectionOnceWhenItCloses() {
        val c = connect()
        c.beginBatchEdit()
        c.commitText("特定の", 1)
        c.setComposingText("ｎ", 1)
        assertThat(settles).isEqualTo(0)
        c.endBatchEdit()
        assertThat(settles).isEqualTo(1)
        assertThat(texts).containsExactly("特定の")
    }

    @Test
    fun aChangeOutsideABatchSettlesAtOnce() {
        connect().setComposingText("に", 1)
        assertThat(settles).isEqualTo(1)
        assertThat(texts).isEmpty()
    }

    @Test
    fun everyConnectionSeesTheSameComposition() {
        val first = connect()
        val second = connect()
        first.setComposingText("にほ", 1)
        assertThat(second.getTextBeforeCursor(10, 0).toString()).isEqualTo("にほ")
        assertThat(field.isComposing).isTrue()
    }

    @Test
    fun closingTheConnectionDropsTheCompositionUnlessItIsKept() {
        for (kept in listOf(false, true)) {
            texts.clear()
            preedits.clear()
            keep = kept
            val c = connect()
            c.setComposingText("にほんご", 1)
            c.closeConnection()
            assertThat(texts).isEqualTo(if (kept) listOf("にほんご") else emptyList<String>())
            assertThat(preedits.last()).isEmpty()
            c.finishComposingText()
            assertThat(texts).isEqualTo(if (kept) listOf("にほんご") else emptyList<String>())
        }
    }

    @Test
    fun aKeyEventSendsItsCharacterOrItsKeyAndTheReleaseSendsNothing() {
        val c = connect()
        assertThat(c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))).isTrue()
        assertThat(c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1))).isTrue()
        assertThat(c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))).isTrue()
        assertThat(c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))).isTrue()
        assertThat(texts).containsExactly("1")
        assertThat(keys).containsExactly(KeyEvent.KEYCODE_DPAD_LEFT)
        // Enter has a character, '\n', but goes as the key: the field keys are looked at first.
        c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertThat(keys.last()).isEqualTo(KeyEvent.KEYCODE_ENTER)
        assertThat(c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))).isFalse()
        assertThat(c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))).isFalse()
    }

    @Test
    fun aKeyWhileComposingSendsTheCompositionFirst() {
        val c = connect()
        c.setComposingText("にほんご", 1)
        c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertThat(sent).containsExactly("text:にほんご", "key:${KeyEvent.KEYCODE_DPAD_LEFT}").inOrder()
        assertThat(field.isComposing).isFalse()
        // The keyboard sets its composition again before the posted restart: it is sent, not lost.
        c.setComposingText("にほんご", 1)
        ShadowLooper.idleMainLooper()
        assertThat(sent.last()).isEqualTo("text:にほんご")
        assertThat(field.isComposing).isFalse()
    }

    @Test
    fun aStringKeyEventsLineBreaksBecomeEnterKeys() {
        field.onKeyMultiple(KeyEvent.KEYCODE_UNKNOWN, 1, KeyEvent(0L, "a\nb", 0, 0))
        assertThat(sent).containsExactly("text:a", "key:${KeyEvent.KEYCODE_ENTER}", "text:b").inOrder()
    }

    @Test
    fun theFieldTakesTheFocusInTouchMode() {
        assertThat(field.isFocusableInTouchMode).isTrue()
        assertThat(field.onCheckIsTextEditor()).isTrue()
    }

    @Test
    fun confirmAndDiscardActOnlyOnAComposition() {
        field.confirm("key")
        field.discard("esc")
        assertThat(preedits).isEmpty()
        connect().setComposingText("あ", 1)
        field.confirm("key")
        assertThat(texts).containsExactly("あ")
        connect().setComposingText("い", 1)
        field.discard("esc")
        assertThat(texts).containsExactly("あ")
        assertThat(preedits.last()).isEmpty()
    }
}
