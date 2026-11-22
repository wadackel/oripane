/**
 * The bar above the soft keyboard: the composition being typed, and the modifier and special keys.
 */
package dev.wadackel.oripane

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** A key the row sends, with its Android keycode; [repeats] while held, as on a keyboard. */
private class SpecialKey(val label: String, val code: Int, val spoken: String, val repeats: Boolean = false)

private val specialKeys = listOf(
    SpecialKey("Esc", 111, "Escape"),
    SpecialKey("Tab", 61, "Tab"),
    SpecialKey("←", 21, "Left arrow", repeats = true),
    SpecialKey("↑", 19, "Up arrow", repeats = true),
    SpecialKey("↓", 20, "Down arrow", repeats = true),
    SpecialKey("→", 22, "Right arrow", repeats = true),
    SpecialKey("⌫", 67, "Delete", repeats = true),
)

private const val KEY_REPEAT_AFTER_MS = 400L
private const val KEY_REPEAT_EVERY_MS = 50L

/**
 * The keyboard's composition, over the key row while it lasts: none of it reaches the Mac until it
 * is committed, and the Mac's cursor cannot be found from here, so it shows on the phone alone. It
 * scrolls to keep the cursor in sight, and takes touches without acting on them.
 */
@Composable
fun PreeditBar(text: String, cursor: Int, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val pad = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(layout, cursor) {
        val l = layout ?: return@LaunchedEffect
        val x = l.getHorizontalPosition(cursor.coerceIn(0, text.length), true) + pad
        val view = scroll.viewportSize
        when {
            x > scroll.value + view - pad -> scroll.scrollTo((x - view + pad).roundToInt())
            x < scroll.value + pad -> scroll.scrollTo((x - pad).roundToInt().coerceAtLeast(0))
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .blocksTouches()
            .background(Palette.surface1)
            .drawBehind {
                val w = 1.dp.toPx()
                drawLine(Palette.highlight, Offset(0f, w / 2), Offset(size.width, w / 2), w)
            }
            .horizontalScroll(scroll, enabled = false)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            color = Palette.text,
            fontSize = 15.sp,
            maxLines = 1,
            softWrap = false,
            textDecoration = TextDecoration.Underline,
            onTextLayout = { layout = it },
            modifier = Modifier.drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                val i = cursor.coerceIn(0, text.length)
                val x = l.getHorizontalPosition(i, true)
                val line = l.getLineForOffset(i)
                drawLine(Palette.text, Offset(x, l.getLineTop(line)), Offset(x, l.getLineBottom(line)), 1.5.dp.toPx())
            },
        )
    }
}

/**
 * The modifier and special keys above the soft keyboard. The keys take taps without taking focus:
 * the field losing focus would close the keyboard.
 */
@Composable
fun KeyRow(
    mods: ModKeys,
    onMod: (ModKey) -> Unit,
    /** A key code, and whether it is one of the sends of a held key. */
    onKey: (Int, Boolean) -> Unit,
    /** A held key was let go. */
    onKeyReleased: () -> Unit,
    field: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Palette.surface1)
            // Only the top edge has a border, drawn outside the scroll so it does not move with the keys.
            .drawBehind {
                val w = 1.dp.toPx()
                drawLine(Palette.highlight, Offset(0f, w / 2), Offset(size.width, w / 2), w)
            }
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        field()
        ModKey.entries.forEach { k ->
            val state = mods[k]
            val spokenState = when (state) {
                ModKeyState.OFF -> "Off"
                ModKeyState.ONCE -> "On for the next key"
                ModKeyState.LOCKED -> "Locked"
            }
            RowKey(k.label, k.spoken, spokenState, filled = state != ModKeyState.OFF, locked = state == ModKeyState.LOCKED, onTap = { onMod(k) })
        }
        specialKeys.forEach { k ->
            RowKey(k.label, k.spoken, null, filled = false, locked = false, repeats = k.repeats, onTap = { held -> onKey(k.code, held) }, onReleased = onKeyReleased)
        }
    }
}

/**
 * One key of the row. It acts when released, not when touched: the row scrolls sideways on a
 * cover screen, and a scroll begun on ⌫ would otherwise delete a character. Held still for
 * [KEY_REPEAT_AFTER_MS], a [repeats] key sends at once and then every [KEY_REPEAT_EVERY_MS], each
 * send marked held, and [onReleased] follows the last.
 * Not `clickable`, which can take the focus from the text field and close the keyboard;
 * TalkBack presses it through its semantics instead.
 */
@Composable
private fun RowKey(
    label: String,
    spoken: String,
    spokenState: String?,
    filled: Boolean,
    locked: Boolean,
    repeats: Boolean = false,
    onTap: (held: Boolean) -> Unit,
    onReleased: () -> Unit = {},
) {
    val shape = RoundedCornerShape(8.dp)
    var pressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val release by rememberUpdatedState(onReleased)
    Box(
        Modifier
            .size(width = 48.dp, height = 40.dp)
            .background(
                when {
                    filled -> if (pressed) Palette.lead.copy(alpha = 0.75f) else Palette.lead
                    pressed -> Palette.keyPressed
                    else -> Palette.keyBg
                },
                shape,
            )
            .border(if (locked) 2.dp else 1.dp, if (locked) Palette.text else if (filled) Palette.lead else Palette.keyBorder, shape)
            // Cleared: the glyph would be read as well, "⌘" or a symbol name, after the key's name.
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = spoken
                spokenState?.let { stateDescription = it }
                onClick { tap(false); true }
            }
            .pointerInput(repeats) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var held = false
                    try {
                        var cancelled = false
                        // True once the finger lifted within [ms]. A timeout and the row's scroll taking
                        // the touch both give null here; [cancelled] tells them apart.
                        suspend fun AwaitPointerEventScope.released(ms: Long): Boolean {
                            val up = withTimeoutOrNull(ms) { waitForUpOrCancellation().also { if (it == null) cancelled = true } }
                            return up != null
                        }
                        if (released(KEY_REPEAT_AFTER_MS)) {
                            tap(false)
                        } else if (!cancelled) {
                            if (repeats) {
                                held = true
                                tap(true)
                                while (!released(KEY_REPEAT_EVERY_MS) && !cancelled) tap(true)
                            } else if (waitForUpOrCancellation() != null) {
                                tap(false)
                            }
                        }
                    } finally {
                        pressed = false
                        // Here too when the gesture is cut off, as by the keyboard closing mid-hold.
                        if (held) release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) Palette.onLead else Palette.text, fontSize = 15.sp)
    }
}
