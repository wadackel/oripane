package dev.wadackel.oripane

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImeBufferTest {
    private val b = ImeBuffer()

    private fun text(sends: List<ImeSend>) = sends.joinToString("") { (it as? ImeSend.Text)?.text.orEmpty() }

    @Test
    fun aWholeConversionSendsNothingUntilItIsCommittedThenOnlyTheResult() {
        for (reading in listOf("n", "に", "にほ", "にほんご", "日本語")) assertThat(b.setComposingText(reading, 1)).isEmpty()
        assertThat(b.commitText("日本語")).containsExactly(ImeSend.Text("日本語"))
        assertThat(b.text).isEmpty()
    }

    @Test
    fun switchingCandidatesSendsNothing() {
        b.setComposingText("にほんご", 1)
        repeat(3) { for (c in listOf("日本語", "にほんご", "二本語")) assertThat(b.setComposingText(c, 1)).isEmpty() }
    }

    @Test
    fun finishingAfterACommitSendsNothingMore() {
        b.setComposingText("日本語", 1)
        assertThat(b.commitText("日本語")).containsExactly(ImeSend.Text("日本語"))
        assertThat(b.finishComposingText()).isEmpty()
    }

    @Test
    fun theConsonantLeftBetweenTwoConversionsIsNotSwallowed() {
        val sends = b.setComposingText("とくていの", 1) +
            b.commitText("特定の") +
            b.setComposingText("ｎ", 1) +
            b.finishComposingText() +
            b.setComposingText("ぱたーんで", 1) +
            b.commitText("パターンで") +
            b.finishComposingText()
        assertThat(text(sends)).isEqualTo("特定のｎパターンで")
    }

    @Test
    fun anEmptyCompositionEndsItAndTheNextDeleteIsABackspace() {
        b.setComposingText("あ", 1)
        b.setComposingText("", 1)
        assertThat(b.isComposing).isFalse()
        assertThat(b.deleteSurroundingText(1, 0)).containsExactly(ImeSend.Key(KeyEvent.KEYCODE_DEL))
    }

    @Test
    fun aDeleteWhileComposingOnlyShortensTheComposition() {
        b.setComposingText("にほん", 1)
        assertThat(b.deleteSurroundingText(1, 0)).isEmpty()
        assertThat(b.text).isEqualTo("にほ")
        assertThat(b.cursor).isEqualTo(2)
    }

    @Test
    fun aDeleteOutsideACompositionIsBackspacesBeforeAndForwardDeletesAfter() {
        assertThat(b.deleteSurroundingText(2, 1)).containsExactly(
            ImeSend.Key(KeyEvent.KEYCODE_DEL),
            ImeSend.Key(KeyEvent.KEYCODE_DEL),
            ImeSend.Key(KeyEvent.KEYCODE_FORWARD_DEL),
        ).inOrder()
    }

    @Test
    fun confirmSendsTheCompositionAndDiscardDropsIt() {
        b.setComposingText("にほんご", 1)
        assertThat(b.confirm()).containsExactly(ImeSend.Text("にほんご"))
        assertThat(b.isComposing).isFalse()
        b.setComposingText("にほんご", 1)
        assertThat(b.discard()).isEmpty()
        assertThat(b.isComposing).isFalse()
        assertThat(b.confirm()).isEmpty()
    }

    @Test
    fun theTextBeforeTheCursorIsTheCompositionOnly() {
        b.commitText("確定")
        assertThat(b.textBeforeCursor(10)).isEmpty()
        b.setComposingText("にほ", 1)
        assertThat(b.textBeforeCursor(10)).isEqualTo("にほ")
        assertThat(b.textBeforeCursor(1)).isEqualTo("ほ")
        assertThat(b.textAfterCursor(10)).isEmpty()
    }

    @Test
    fun aDeleteInCodePointsTakesAWholeEmoji() {
        b.setComposingText("あ😀", 1)
        assertThat(b.deleteSurroundingTextInCodePoints(1, 0)).isEmpty()
        assertThat(b.text).isEqualTo("あ")
    }

    @Test
    fun lineBreaksInCommittedTextAreEnterKeysInPlace() {
        assertThat(b.commitText("a\nb\r\nc")).containsExactly(
            ImeSend.Text("a"),
            ImeSend.Key(KeyEvent.KEYCODE_ENTER),
            ImeSend.Text("b"),
            ImeSend.Key(KeyEvent.KEYCODE_ENTER),
            ImeSend.Text("c"),
        ).inOrder()
    }

    @Test
    fun aSubmittingEditorActionSendsTheCompositionAndEnterAndNextDoesNotAddEnter() {
        b.setComposingText("にほんご", 1)
        assertThat(b.performEditorAction(EditorInfo.IME_ACTION_DONE)).containsExactly(ImeSend.Text("にほんご"), ImeSend.Key(KeyEvent.KEYCODE_ENTER)).inOrder()
        b.setComposingText("にほんご", 1)
        assertThat(b.performEditorAction(EditorInfo.IME_ACTION_NEXT)).containsExactly(ImeSend.Text("にほんご"))
    }

    @Test
    fun theCursorFollowsNewCursorPositionAndSetSelectionWithinTheComposition() {
        b.setComposingText("にほん", 1)
        assertThat(b.cursor).isEqualTo(3)
        b.setComposingText("にほん", 0)
        assertThat(b.cursor).isEqualTo(0)
        b.setSelection(2, 2)
        assertThat(b.cursor).isEqualTo(2)
        b.discard()
        assertThat(b.setSelection(1, 1)).isEmpty()
        assertThat(b.cursor).isEqualTo(0)
    }

    @Test
    fun aComposingRegionNarrowsTheCompositionAndAnEmptyOneEndsIt() {
        b.setComposingText("にほんご", 1)
        b.setComposingRegion(0, 2)
        assertThat(b.text).isEqualTo("にほ")
        b.setComposingRegion(1, 1)
        assertThat(b.isComposing).isFalse()
    }
}
