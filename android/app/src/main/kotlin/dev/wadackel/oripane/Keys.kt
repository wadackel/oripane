package dev.wadackel.oripane

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** The on-screen modifier keys, with the Android meta bit the host maps to a Mac flag. [spoken]: what TalkBack reads. */
enum class ModKey(val meta: Int, val label: String, val logName: String, val spoken: String) {
    CMD(0x10000, "⌘", "cmd", "Command"),
    ALT(0x2, "⌥", "alt", "Option"),
    CTRL(0x1000, "⌃", "ctrl", "Control"),
    SHIFT(0x1, "⇧", "shift", "Shift"),
}

enum class ModKeyState(val logName: String) { OFF("off"), ONCE("once"), LOCKED("locked") }

/** The state of every on-screen modifier: a tap steps one key off → once → locked → off. */
data class ModKeys(val states: Map<ModKey, ModKeyState> = emptyMap()) {
    operator fun get(k: ModKey) = states[k] ?: ModKeyState.OFF

    val anyOn get() = states.values.any { it != ModKeyState.OFF }

    val meta get() = ModKey.entries.filter { this[it] != ModKeyState.OFF }.fold(0) { m, k -> m or k.meta }

    fun tapped(k: ModKey) = ModKeys(
        states + (k to when (this[k]) {
            ModKeyState.OFF -> ModKeyState.ONCE
            ModKeyState.ONCE -> ModKeyState.LOCKED
            ModKeyState.LOCKED -> ModKeyState.OFF
        }),
    )

    /** One key went out: the once-only modifiers are spent. */
    fun afterKey() = ModKeys(states.mapValues { (_, s) -> if (s == ModKeyState.ONCE) ModKeyState.OFF else s })

    /** The row closed: every modifier, locked ones too, goes off. */
    fun cleared() = ModKeys()
}

/** One key event from a character map. */
data class KeyStroke(val down: Boolean, val code: Int, val meta: Int)

sealed interface KeySend {
    data class Text(val text: String) : KeySend
    /** A press and a release. */
    data class Key(val code: Int, val meta: Int) : KeySend
}

/** What typed text becomes while a modifier is on, the characters sent without them, and the modifiers left. */
data class KeyPlan(val sends: List<KeySend>, val dropped: List<String>, val modKeys: ModKeys) {
    companion object {
        /**
         * Text the field received while [modKeys] has a modifier on. Each character, and each line
         * break, is one key: a once-only modifier goes on the first only. Characters are code points,
         * not `Char`s: half of a surrogate pair maps to no key and turns into `?` on the way out.
         */
        fun forText(text: String, modKeys: ModKeys, events: (Char) -> List<KeyStroke>?): KeyPlan {
            val sends = mutableListOf<KeySend>()
            val dropped = mutableListOf<String>()
            var mods = modKeys
            for (cp in text.codePoints()) {
                val c = String(Character.toChars(cp))
                val key = when {
                    cp == '\n'.code -> KeyEvent.KEYCODE_ENTER to 0
                    Character.isBmpCodePoint(cp) -> events(cp.toChar())?.let(::body)
                    else -> null
                }
                if (key != null && key.first in ALLOWED_KEYCODES) {
                    sends += KeySend.Key(key.first, key.second or mods.meta)
                } else {
                    sends += KeySend.Text(c)
                    dropped += c
                }
                mods = mods.afterKey()
            }
            return KeyPlan(sends, dropped, mods)
        }

        /** The one non-modifier key of [strokes], with the modifiers pressed around it folded into its meta. */
        private fun body(strokes: List<KeyStroke>): Pair<Int, Int>? {
            val held = strokes.mapNotNull { MODIFIER_META[it.code] }.fold(0, Int::or)
            val key = strokes.firstOrNull { it.down && it.code !in MODIFIER_META } ?: return null
            return key.code to ((key.meta and META_MASK) or held)
        }
    }
}

/** The meta bits the host reads (`Keymap.flags`); a character map also sets left/right bits. */
private const val META_MASK = 0x1 or 0x2 or 0x1000 or 0x10000

/** Shift, Alt, Ctrl and Meta, left and right, with the meta bit each stands for. */
private val MODIFIER_META = mapOf(
    59 to 0x1, 60 to 0x1,
    57 to 0x2, 58 to 0x2,
    113 to 0x1000, 114 to 0x1000,
    117 to 0x10000, 118 to 0x10000,
)

/**
 * The keycodes the host's `Keymap` turns into Mac keys, less the modifier keys themselves: a
 * character whose key is not here would be dropped by the host, so it goes as text instead.
 */
val ALLOWED_KEYCODES: Set<Int> = (29..54).toSet() + (7..16) + (131..142) + setOf(
    66, 160, 67, 112, 61, 62, 111, 19, 20, 21, 22, 122, 123, 92, 93,
    55, 56, 69, 70, 71, 72, 73, 74, 75, 76, 68,
)

/** A key the soft keyboard sent, as opposed to a physical keyboard. */
fun isSoftKeyboardKey(deviceId: Int, flags: Int) =
    deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0

/** [KeyPlan.forText]'s `events` on the device: the virtual keyboard's character map. */
fun virtualKeyEvents(): (Char) -> List<KeyStroke>? {
    val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    return { c -> map.getEvents(charArrayOf(c))?.map { KeyStroke(it.action == KeyEvent.ACTION_DOWN, it.keyCode, it.metaState) } }
}
