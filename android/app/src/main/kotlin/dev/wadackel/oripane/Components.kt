/**
 * What both scenes share: choice and switch rows with the settings' choice lists, the page stack,
 * bottom sheet settings and the confirmation dialog.
 */
package dev.wadackel.oripane

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A labelled row of exclusive choices, with an optional note under the label. */
@Composable
fun <T> Choices(label: String, options: List<Pair<T, String>>, selected: T, note: String? = null, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Column(modifier.padding(top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(label, style = Type.label, color = Palette.text)
            if (note != null) Text(note, style = Type.note, color = Palette.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        SegmentedChoices(options, selected, onSelect = onSelect)
    }
}

/**
 * Exclusive choices as segments in a recessed track, so every value is one tap away: for two or
 * three short options. The chosen one takes a white 10 % ground; no check mark, since on the
 * folded screen three options share about 305 dp.
 */
@Composable
fun <T> SegmentedChoices(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.inner)).background(Palette.well).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (v, text) ->
            val on = v == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(Radius.innermost))
                    .background(if (on) Palette.chosen else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) {
                val color = when {
                    !enabled -> Palette.textFaint
                    on -> Palette.text
                    else -> Palette.textMuted
                }
                Text(text, style = Type.segment, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

/** The switch the pages draw: white when on, with a dark knob; grey when off. */
@Composable
fun SwitchMark(checked: Boolean, modifier: Modifier = Modifier) {
    val knob by animateDpAsState(if (checked) 21.dp else 3.dp, label = "knob")
    Box(modifier.size(44.dp, 26.dp).clip(CircleShape).background(if (checked) Palette.lead else Palette.surface3)) {
        Box(Modifier.offset(x = knob, y = 3.dp).size(20.dp).background(if (checked) Palette.onLead else Palette.textMuted, CircleShape))
    }
}

/**
 * A labelled switch that the whole row toggles, with an optional [note] under the label. The
 * switch takes no taps of its own: it would answer differently by where the row was pressed.
 */
@Composable
fun SwitchRow(label: String, checked: Boolean, contentPadding: PaddingValues, note: String? = null, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(contentPadding)
            .heightIn(min = Sizes.rowMin - 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.label, color = Palette.text)
            if (note != null) Text(note, style = Type.note, color = Palette.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        SwitchMark(checked)
    }
}

fun modeLabel(m: TouchMode) = if (m == TouchMode.DIRECT) "Direct" else "Trackpad"

const val touchModeNote = "Direct presses where you touch."

val speeds = listOf(Speed.SLOW to "Slow", Speed.NORMAL to "Normal", Speed.FAST to "Fast")
val directions = listOf(false to "Standard", true to "Reversed")
val stripSides = listOf(ScrollStrip.RIGHT to "Right", ScrollStrip.LEFT to "Left", ScrollStrip.OFF to "Off")
const val stripNote = "Trackpad mode only. Hold the side band still to move it."
val dataSaverOptions = listOf(DataSaver.AUTO to "Auto", DataSaver.ALWAYS to "Always", DataSaver.OFF to "Off")
val displayModes = listOf("extend" to "Extend", "mirror" to "Mirror", "match" to "Match")

/** How many steps back lead from [s] to the top page. */
fun <S> pageDepth(s: S, parentOf: (S) -> S?): Int = generateSequence(parentOf(s), parentOf).count()

/** The pages on the way back from [from] that are not on the way back from [to], [from] first. */
fun <S : Any> pagesLeft(from: S, to: S, parentOf: (S) -> S?): List<S> {
    val kept = generateSequence(to, parentOf).toSet()
    return generateSequence(from, parentOf).filterNot { it in kept }.toList()
}

/**
 * Shows [current] and moves between pages along a shared X axis: a deeper page comes in from the
 * right. A back swipe drags the page toward [parentOf]'s answer, and [onBack] gets that page once
 * the swipe commits.
 *
 * The pages on the way back keep their saved state, such as a scroll position; a page gone back
 * from loses it. Pages are keyed by `toString()`, so each page's must tell it apart from the others
 * and stay the same across process recreation, as a data class's or an enum's does.
 */
@Composable
fun <S : Any> PageStack(current: S, parentOf: (S) -> S?, onBack: (S) -> Unit, content: @Composable (S) -> Unit) {
    val parent = parentOf(current)
    var swiping by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = parent != null) { events ->
        swiping = true
        progress = 0f
        try {
            events.collect { progress = it.progress }
            log("ui back_gesture result=commit")
            onBack(parent!!)
        } catch (e: CancellationException) {
            log("ui back_gesture result=cancel")
            throw e
        } finally {
            swiping = false
        }
    }
    val state = remember { SeekableTransitionState(current) }
    val transition = rememberTransition(state, label = "page")
    val saved = rememberSaveableStateHolder()
    // Forgetting a page as soon as `current` moves off it would catch it while it still slides out,
    // and a page brought straight back then never saves again, so it waits for the move to settle.
    var settled by remember { mutableStateOf(current) }
    LaunchedEffect(transition.currentState, transition.targetState) {
        if (transition.currentState == transition.targetState) {
            pagesLeft(settled, transition.currentState, parentOf).forEach { saved.removeState(it.toString()) }
            settled = transition.currentState
        }
    }
    if (swiping && parent != null) {
        LaunchedEffect(progress) { state.seekTo(progress, parent) }
    } else {
        LaunchedEffect(current) {
            if (state.currentState != current) {
                state.animateTo(current)
            } else {
                // A cancelled swipe: animateTo(current) would play the page coming back in from
                // the far side, so the seek runs back to zero before settling on the page.
                animate(state.fraction, 0f, animationSpec = tween((state.fraction * PAGE_MS).toInt())) { v, _ ->
                    launch { if (v > 0f) state.seekTo(v) else state.snapTo(current) }
                }
            }
        }
    }
    val offset = with(LocalDensity.current) { 30.dp.roundToPx() }
    transition.AnimatedContent(
        transitionSpec = { sharedAxisX(pageDepth(targetState, parentOf) > pageDepth(initialState, parentOf), offset) },
    ) { page -> saved.SaveableStateProvider(page.toString()) { content(page) } }
}

private const val PAGE_MS = 300

/**
 * A bottom sheet's content insets without Material's top one, which pads the content by the part of
 * the status bar the sheet reaches into. That padding makes a tall sheet taller and moves it further
 * into the bar, and after a fling's overshoot the two feed each other until the sheet bounces without
 * end. The sheets cap their own height to stay below the bar instead.
 */
@Composable
fun sheetInsets(): WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

/**
 * System bars drawn for a dark sheet. Material picks them from the content colour, and the sheets'
 * muted grey counts as dark to it: the light bars it chose made One UI lay a white strip under the
 * gesture bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun sheetProperties(dismissOnBack: Boolean = true) =
    ModalBottomSheetProperties(isAppearanceLightStatusBars = false, isAppearanceLightNavigationBars = false, shouldDismissOnBackPress = dismissOnBack)

/** Material's shared axis X: the old page fades out in the first 90 ms, the new one after it. */
private fun sharedAxisX(forward: Boolean, offset: Int): ContentTransform {
    val sign = if (forward) 1 else -1
    return (slideInHorizontally(tween(PAGE_MS)) { sign * offset } + fadeIn(tween(PAGE_MS - 90, delayMillis = 90))) togetherWith
        (slideOutHorizontally(tween(PAGE_MS)) { -sign * offset } + fadeOut(tween(90)))
}

/** The question before something that cannot be undone: the confirming word is red, Cancel is plain. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface1,
        shape = RoundedCornerShape(Radius.sheet),
        title = { Text(title, style = Type.title, color = Palette.text) },
        text = { Text(body, style = Type.value, color = Palette.textMuted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = Type.label, color = Palette.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Type.label, color = Palette.text) } },
    )
}

/** Every bottom sheet's ground and corners: the grey of a surface, 22 dp at the top. */
val sheetShape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)

/** The bar at the top of every bottom sheet. */
@Composable
fun SheetHandle() {
    Box(Modifier.padding(top = Space.xs, bottom = Space.s).size(36.dp, 4.dp).background(Palette.surface3, CircleShape))
}

/** A bottom sheet's title, 18 sp, left-aligned with its rows. */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.sheetTitle, color = Palette.text, modifier = modifier.padding(start = 24.dp, end = 24.dp, bottom = Space.xs).semantics { heading() })
}
