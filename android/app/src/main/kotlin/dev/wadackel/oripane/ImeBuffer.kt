package dev.wadackel.oripane

/** What the soft keyboard's input turns into for the Mac: committed text, or a key. */
sealed interface ImeSend {
    /** Text the user committed, without line breaks. */
    data class Text(val text: String) : ImeSend

    /** Backspace, Forward delete or Enter. */
    data class Key(val code: Int) : ImeSend
}

/**
 * The text an input method believes it is editing, as in limenty's `ImeEditBuffer`: only the
 * composition ([text], with the [cursor] in it), never what was committed. Committed text goes to
 * the Mac at once and is forgotten, so the input method can neither read it back nor rewrite or
 * commit it again. Every call returns what to send, and nothing while a composition goes on.
 *
 * Plain Kotlin, so the rules are tested on the JVM: key codes and editor actions are Android's
 * constant values, kept here as numbers.
 */
class ImeBuffer {
    var text = ""
        private set
    var cursor = 0
        private set

    val isComposing get() = text.isNotEmpty()

    /**
     * Replaces the composition and sends nothing. An empty text ends it: Gboard erases the last
     * character of a composition this way, and an empty one left open would keep taking the keys.
     */
    fun setComposingText(value: String, newCursorPosition: Int): List<ImeSend> {
        text = value
        // 1 puts the cursor right after the text, 0 right before it; there is nothing around it to reach.
        val raw = if (newCursorPosition > 0) value.length + newCursorPosition - 1 else newCursorPosition
        cursor = raw.coerceIn(0, value.length)
        return emptyList()
    }

    /** Narrows the composition to [start] until [end] of it; there is no other text to take in. */
    fun setComposingRegion(start: Int, end: Int): List<ImeSend> {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        text = text.substring(from, to)
        cursor = text.length
        return emptyList()
    }

    /** Drops the composition and sends [value] alone: the input method chose what replaces it. */
    fun commitText(value: String): List<ImeSend> {
        clear()
        return committed(value)
    }

    /**
     * The input method ends its composition, which stays in the document by the framework's rule:
     * it is sent. Gboard parks a romaji consonant as a composition of its own and ends it, and
     * dropping it would cost the next syllable its first letter.
     */
    fun finishComposingText(): List<ImeSend> = confirm()

    /** Inside a composition, edits it; outside one, the keys that delete around the Mac's cursor. */
    fun deleteSurroundingText(before: Int, after: Int): List<ImeSend> {
        if (before < 0 || after < 0) return emptyList()
        if (isComposing) {
            val from = (cursor - before).coerceAtLeast(0)
            val to = (cursor + after).coerceAtMost(text.length)
            text = text.removeRange(from, to)
            cursor = if (text.isEmpty()) 0 else from
            return emptyList()
        }
        return List(before) { ImeSend.Key(KEYCODE_DEL) } + List(after) { ImeSend.Key(KEYCODE_FORWARD_DEL) }
    }

    /** As [deleteSurroundingText] in code points, so that half of a surrogate pair is never left. */
    fun deleteSurroundingTextInCodePoints(before: Int, after: Int): List<ImeSend> {
        if (before < 0 || after < 0) return emptyList()
        if (!isComposing) return deleteSurroundingText(before, after)
        val from = text.offsetByCodePoints(cursor, -minOf(before, text.codePointCount(0, cursor)))
        val to = text.offsetByCodePoints(cursor, minOf(after, text.codePointCount(cursor, text.length)))
        return deleteSurroundingText(cursor - from, to - cursor)
    }

    /** Moves the cursor within the composition; there is nothing to select outside one. */
    fun setSelection(start: Int, end: Int): List<ImeSend> {
        if (isComposing) cursor = end.coerceIn(0, text.length)
        return emptyList()
    }

    /** Sends the composition, and Enter for the actions that mean "submit", not for moving to another field. */
    fun performEditorAction(action: Int): List<ImeSend> {
        val submits = action in SUBMIT_ACTIONS
        return confirm() + if (submits) listOf(ImeSend.Key(KEYCODE_ENTER)) else emptyList()
    }

    fun textBeforeCursor(length: Int): String = if (length <= 0) "" else text.substring((cursor - length).coerceAtLeast(0), cursor)

    fun textAfterCursor(length: Int): String = if (length <= 0) "" else text.substring(cursor, (cursor + length).coerceAtMost(text.length))

    /** Ends the composition and sends it. */
    fun confirm(): List<ImeSend> {
        val pending = text
        clear()
        return committed(pending)
    }

    /** Ends the composition and sends nothing. */
    fun discard(): List<ImeSend> {
        clear()
        return emptyList()
    }

    private fun clear() {
        text = ""
        cursor = 0
    }

    /** Committed text, its line breaks (`\n`, `\r`, `\r\n`) turned into Enter keys where they stand. */
    private fun committed(value: String): List<ImeSend> {
        if (value.isEmpty()) return emptyList()
        val sends = mutableListOf<ImeSend>()
        val pending = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\n' || c == '\r') {
                if (pending.isNotEmpty()) sends += ImeSend.Text(pending.toString()).also { pending.clear() }
                sends += ImeSend.Key(KEYCODE_ENTER)
                i += if (c == '\r' && value.getOrNull(i + 1) == '\n') 2 else 1
            } else {
                pending.append(c)
                i++
            }
        }
        if (pending.isNotEmpty()) sends += ImeSend.Text(pending.toString())
        return sends
    }

    companion object {
        /** `KeyEvent.KEYCODE_DEL`, `KEYCODE_FORWARD_DEL` and `KEYCODE_ENTER`. */
        const val KEYCODE_DEL = 67
        const val KEYCODE_FORWARD_DEL = 112
        const val KEYCODE_ENTER = 66

        /** `EditorInfo.IME_ACTION_UNSPECIFIED`, `GO`, `SEARCH`, `SEND` and `DONE`. */
        private val SUBMIT_ACTIONS = setOf(0, 2, 3, 4, 6)
    }
}
