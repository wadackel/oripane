package dev.wadackel.oripane

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/** Arrows, Enter and backspace: the keys the soft keyboard may send as key events. */
private val FIELD_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL,
)

/**
 * The field the soft keyboard types into, a bare [View] with an [InputConnection] of its own as in
 * limenty, not an `EditText`: an `EditText` keeps the composition and the committed text in one
 * document, and telling them apart again went wrong with Gboard. Only committed text reaches
 * [Callbacks.onText], once; the composition lives in an [ImeBuffer] shared by every connection the
 * input method opens, and shows through [Callbacks.onPreedit].
 *
 * A physical key reaches the activity only after the input method, which takes the presses for its
 * own composition, so the field hands it on before that.
 */
@SuppressLint("ViewConstructor")
class ImeField(context: Context, private val cb: Callbacks) : View(context) {
    class Callbacks(
        /** Committed text, without line breaks. */
        val onText: (String) -> Unit,
        /** Backspace, Forward delete, Enter, an arrow, or another key the host maps. */
        val onKey: (Int) -> Unit,
        /** The composition and its cursor; empty when there is none. */
        val onPreedit: (text: String, cursor: Int) -> Unit,
        /** Where the cursor and the composition settled, for `InputMethodManager.updateSelection`. */
        val onSelectionSettled: (cursor: Int, composingStart: Int, composingEnd: Int) -> Unit,
        val modifiersOn: () -> Boolean,
        /** Whether the keyboard is asked not to learn from what is typed. */
        val incognito: () -> Boolean,
        val onPhysicalKey: (KeyEvent) -> Boolean,
        /** Whether a composition left when the input method lets go is sent rather than dropped. */
        val keepOnDetach: () -> Boolean,
        val log: (String) -> Unit,
    )

    private val buffer = ImeBuffer()
    private val main = Handler(Looper.getMainLooper())

    val isComposing get() = buffer.isComposing

    init {
        // Without them requestFocus fails in touch mode and the keyboard never shows.
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    override fun onCheckIsTextEditor() = true

    /**
     * With a modifier on, a password layout: no composition, so each character commits on its own.
     * No suggestions either way, or Gboard would hold English words back as compositions too.
     * `MULTI_LINE` keeps Enter a key: a single-line field turns it into an action that closes the keyboard.
     * Incognito is a flag rather than a password field, which would stop Japanese input as well as learning.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val incognito = cb.incognito()
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            if (cb.modifiersOn()) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else 0
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            if (incognito) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0
        cb.log("ime editor incognito=$incognito")
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        outAttrs.initialCapsMode = 0
        return Connection()
    }

    /** Sends the composition, as the user moved on from it; the caller restarts the input. */
    fun confirm(reason: String) {
        if (!buffer.isComposing) return
        val len = codePoints(buffer.text)
        deliver(buffer.confirm())
        cb.log("ime confirm len=$len reason=$reason")
        cb.onPreedit("", 0)
    }

    /** Drops the composition; the caller restarts the input. */
    fun discard(reason: String) {
        if (!buffer.isComposing) return
        val len = codePoints(buffer.text)
        buffer.discard()
        cb.log("ime discard len=$len reason=$reason")
        cb.onPreedit("", 0)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean =
        (!isSoftKeyboardKey(event.deviceId, event.flags) && cb.onPhysicalKey(event)) || super.onKeyPreIme(keyCode, event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = key(event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = key(event)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val characters = event.characters
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN || characters.isNullOrEmpty()) return super.onKeyMultiple(keyCode, repeatCount, event)
        confirmForKey()
        // Through a buffer of its own, so its line breaks become Enter keys like committed text's.
        send(ImeBuffer().commitText(characters), "key")
        return true
    }

    /**
     * A key from the keyboard, through the connection or the view: sent as text when it makes a
     * character, as a key when the host maps it. A key that sends nothing (a modifier, Back, a
     * system key) is left to the activity, its release too.
     */
    private fun key(event: KeyEvent): Boolean {
        val send = classify(event) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return event.action == KeyEvent.ACTION_UP
        confirmForKey()
        send(listOf(send), "key")
        return true
    }

    private fun classify(event: KeyEvent): ImeSend? {
        val code = event.keyCode
        // Before the character: Enter's is a line break.
        if (code in FIELD_KEYS) return ImeSend.Key(code)
        val c = event.unicodeChar
        if (c != 0 && c and KeyCharacterMap.COMBINING_ACCENT == 0) return ImeSend.Text(String(Character.toChars(c)))
        return if (code in ALLOWED_KEYCODES) ImeSend.Key(code) else null
    }

    /**
     * Gboard sends no plain keys while composing, but one that comes must not lose the text. The
     * restart waits for the call in progress to return, and confirms again first: the keyboard may
     * have set its composition back in between, which the restart's own close would drop.
     */
    private fun confirmForKey() {
        if (!buffer.isComposing) return
        confirm("key")
        // The main looper rather than View.post, which holds the runnable while the view is detached.
        main.post {
            confirm("key")
            context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
        }
    }

    private fun deliver(sends: List<ImeSend>) = sends.forEach {
        when (it) {
            is ImeSend.Text -> cb.onText(it.text)
            is ImeSend.Key -> cb.onKey(it.code)
        }
    }

    /** Delivers what a call produced, logging its length, never the text: it may be a password. */
    private fun send(sends: List<ImeSend>, via: String) {
        val len = sends.sumOf { (it as? ImeSend.Text)?.let { t -> codePoints(t.text) } ?: 0 }
        if (len > 0) cb.log("ime send len=$len via=$via")
        deliver(sends)
    }

    private fun codePoints(s: String) = s.codePointCount(0, s.length)

    /**
     * The input method's view of the field. Every call is answered here rather than by
     * [BaseInputConnection], which would expose a document and let the keyboard read back, and
     * rewrite, text the Mac already has.
     */
    private inner class Connection : BaseInputConnection(this@ImeField, true) {
        private val empty: Editable = SpannableStringBuilder()
        private var batch = 0
        private var moved = false

        override fun getEditable(): Editable = empty

        /** [via] names the path in the `ime send` log, for the calls that can send text. */
        private fun emit(sends: List<ImeSend>, via: String = "") {
            cb.onPreedit(buffer.text, buffer.cursor)
            moved = true
            if (batch == 0) settle()
            send(sends, via)
        }

        /**
         * Reported once the batch closes, not per call: Gboard commits and composes again in one
         * batch, and hearing "not composing" in between, it spills the consonant it holds as text.
         */
        private fun settle() {
            if (!moved) return
            moved = false
            val composing = buffer.isComposing
            cb.onSelectionSettled(buffer.cursor, if (composing) 0 else -1, if (composing) buffer.text.length else -1)
        }

        override fun beginBatchEdit(): Boolean {
            batch++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batch > 0) batch--
            if (batch == 0) settle()
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            emit(buffer.setComposingText(text?.toString().orEmpty(), newCursorPosition))
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            emit(buffer.setComposingRegion(start, end))
            return true
        }

        // Also called from super.closeConnection, after the composition was already sent or dropped.
        override fun finishComposingText(): Boolean {
            if (buffer.isComposing) emit(buffer.finishComposingText(), "finish")
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            emit(buffer.commitText(text?.toString().orEmpty()), "commit")
            return true
        }

        override fun commitCompletion(text: CompletionInfo?): Boolean {
            emit(buffer.commitText(text?.text?.toString().orEmpty()), "commit")
            return true
        }

        override fun commitCorrection(correctionInfo: CorrectionInfo?) = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            emit(buffer.deleteSurroundingText(beforeLength, afterLength))
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            emit(buffer.deleteSurroundingTextInCodePoints(beforeLength, afterLength))
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            emit(buffer.setSelection(start, end))
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            emit(buffer.performEditorAction(editorAction), "editor_action")
            return true
        }

        // A key it does not send is declined, as in limenty: Gboard sends no Back or modifier this way.
        override fun sendKeyEvent(event: KeyEvent?) = event != null && key(event)

        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = buffer.textBeforeCursor(length)

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = buffer.textAfterCursor(length)

        override fun getSelectedText(flags: Int): CharSequence = ""

        override fun getCursorCapsMode(reqModes: Int) = 0

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

        override fun requestCursorUpdates(cursorUpdateMode: Int) = false

        override fun performContextMenuAction(id: Int) = false

        override fun clearMetaKeyStates(states: Int) = true

        override fun reportFullscreenMode(enabled: Boolean) = false

        /**
         * The keyboard let go: on a switch to the stream menu or to the background the composition
         * is sent, as the user would expect; when there is nowhere to send it, it is dropped. Not
         * super first: its finishComposingText would send it either way.
         */
        override fun closeConnection() {
            if (cb.keepOnDetach()) confirm("detach") else discard("detach")
            super.closeConnection()
        }
    }
}
