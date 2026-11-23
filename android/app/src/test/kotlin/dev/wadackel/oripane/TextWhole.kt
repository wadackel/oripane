package dev.wadackel.oripane

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDate

/**
 * Fails when any text on screen, in any window, is not whole: a line that breaks inside a word, text
 * cut off by its own box with no "…" to say so, or text cut off by a box around it or by the
 * screen's edge. A text with "…" was cut on purpose. What a scrolling container leaves out of view
 * is only out of view, and a text field scrolls its own text. [shot] runs this for every screen and sheet the screenshot tests draw, at
 * every [FoldSize], the largest font and display sizes included: text cut at those sizes was found
 * on the phone again and again, one screen at a time.
 */
fun ComposeContentTestRule.assertTextWhole(name: String) {
    val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
    val found = texts.mapNotNull { node ->
        if (node.config.getOrNull(SemanticsActions.SetText) != null) return@mapNotNull null
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.firstOrNull() ?: return@mapNotNull null
        problem(node, layout)?.let { layout.layoutInput.text.text to it }
    }
    val entries = knownText.filter { it.shots.matches(name) }
    val excused = found.filter { (text, _) -> entries.any { it.text.matches(text) && it.stillAllowed() } }
    assertWithMessage("$name: text not whole").that((found - excused.toSet()).map { (text, p) -> "\"$text\": $p" }).isEmpty()
    val unneeded = entries.filter { e -> found.none { (text, _) -> e.text.matches(text) } }
    assertWithMessage("$name: known texts that are whole now; remove them from knownText").that(unneeded.map { it.text.pattern }).isEmpty()
}

/**
 * Texts known not to be whole, in the shots whose names match: each with why, the day after which
 * it fails again, and who agreed to it. Fixing one makes its entry fail as unneeded.
 */
private class KnownText(val shots: Regex, val text: Regex, val why: String, val until: LocalDate?, val approved: String) {
    fun stillAllowed(): Boolean {
        val end = until ?: return true
        assertWithMessage("\"${text.pattern}\" in ${shots.pattern} was allowed until $end ($why)").that(LocalDate.now()).isAtMost(end)
        return true
    }
}

/**
 * A known defect other than text, in a test's own check whose result is [holds]: allowed until
 * [end], approved as [knownText]'s entries are. It fails after the date, and once the check holds,
 * so that the exception goes with the fix.
 */
fun knownBroken(holds: Boolean, end: LocalDate, why: String) {
    assertWithMessage("fixed now, remove the exception: $why").that(holds).isFalse()
    assertWithMessage("allowed until $end: $why").that(LocalDate.now()).isAtMost(end)
}

private val issue45 = LocalDate.of(2026, 12, 31)
private const val OWNER = "the owner, 2026-11-24"

private val knownText = listOf(
    KnownText(Regex("key_row_(inner_largest_font|cover_largest)"), Regex("Tab"), "Issue #45: the 48 dp key is too narrow at font 2.0", issue45, OWNER),
    KnownText(
        Regex("map_(choice_sheet|direct|direct_end|direct_long_shortcut|entry_sheet|entry_sheet_end|entry_sheet_custom|trackpad|trackpad_end)_cover_largest"),
        Regex(".*finger.*|Screenshot"),
        "Issue #45: gesture and item names in a narrow column",
        issue45,
        OWNER,
    ),
    KnownText(Regex("settings_gestures_cover_largest"), Regex("(Direct|Trackpad) gestures"), "Issue #45", issue45, OWNER),
    KnownText(Regex("menu_sheet(_long_name|_reconnecting|_test)?_cover_largest"), Regex("Display mode"), "Issue #45: the row's label beside its value", issue45, OWNER),
    KnownText(Regex("radial_overlay_sticky(_edge)?_cover_largest"), Regex("Tap an item, or outside to close"), "Issue #45: the hint's box is one line tall", issue45, OWNER),
    KnownText(Regex("radial_overlay_selected_edge_cover_largest"), Regex("Copy screenshot of area|[⌃⇧⌘4]"), "Issue #45: the tag at the screen's edge squeezes its name and keys", issue45, OWNER),
    KnownText(
        Regex("radial_menu(_end|_entry_sheet|_largest_font|_empty_cells|_restore_confirm)?_cover_largest"),
        Regex("(Top|Bottom) right  ·  .*"),
        "Issue #45: a cell's direction and keys on one line, cut with no \"…\"",
        issue45,
        OWNER,
    ),
    KnownText(Regex("detail(_remove_confirm)?_cover_largest"), Regex("192\\.168\\.1\\.20"), "Issue #45: the address column", issue45, OWNER),
    KnownText(Regex("settings_about(_font_sheet|_library_sheet)?_cover_largest"), Regex("(AndroidX|Compose|Kotlin|JetBrains|JSpecify) .*|JSpecify"), "Issue #45: library names behind the sheet", issue45, OWNER),
    KnownText(
        Regex("settings_about(_font_sheet|_library_sheet)?_inner_largest_font"),
        Regex("AndroidX Localbroadcastmanager"),
        "one word longer than the row at font 2.0: it has to break",
        null,
        OWNER,
    ),
)

private fun problem(node: SemanticsNode, layout: TextLayoutResult): String? {
    val text = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount - 1) {
        if (layout.isLineEllipsized(line)) break
        val end = layout.getLineEnd(line)
        if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
            return "breaks inside a word: \"${text.substring(layout.getLineStart(line), end)}\" / \"${text.substring(end, layout.getLineEnd(line + 1))}\""
        }
    }
    // A text that ends in "…" was cut on purpose; only its own box may cut it that way.
    if ((0 until layout.lineCount).none(layout::isLineEllipsized)) {
        // Lines past maxLines are not counted in lineCount at all, so they would pass every line's check.
        if (layout.multiParagraph.didExceedMaxLines) return "cut off after line ${layout.lineCount}, with no \"…\""
        // Not `hasVisualOverflow`: it compares the text's width with the width it was offered, so any
        // text narrower than its row reads as overflowing. A line's own width, not its right end, as a
        // centred line is placed in the width offered. A line counts as cut when its baseline is: the
        // line box is taller than the glyphs, and a key or a dot sits a glyph in a box a little shorter.
        for (line in 0 until layout.lineCount) {
            val width = layout.getLineRight(line) - layout.getLineLeft(line)
            if (width > layout.size.width + SLACK_PX) return "cut off by its own width: a line of %.0f px in %d".format(width, layout.size.width)
            if (layout.getLineBaseline(line) > layout.size.height + SLACK_PX) return "cut off by its own height: line ${line + 1} of ${layout.lineCount} below its ${layout.size.height} px"
        }
    }
    // In the window's coordinates, which the window's edge cuts too: the text may show all of itself
    // but what a scrolling container around it leaves out of view.
    val whole = Rect(node.positionInWindow, node.size.toSize())
    val inView = node.scroller()?.let { whole.intersect(it.boundsInWindow) } ?: whole
    if (inView.width <= 0f || inView.height <= 0f) return null
    val shown = node.boundsInWindow
    val cut = shown.width < inView.width - SLACK_PX || shown.height < inView.height - SLACK_PX
    return if (cut) "cut off by a box around it or the screen's edge: %.0fx%.0f in view shows %.0fx%.0f".format(inView.width, inView.height, shown.width, shown.height) else null
}

private fun SemanticsNode.scroller(): SemanticsNode? {
    var n: SemanticsNode? = parent
    while (n != null) {
        if (SemanticsProperties.VerticalScrollAxisRange in n.config || SemanticsProperties.HorizontalScrollAxisRange in n.config) return n
        n = n.parent
    }
    return null
}

/** Layout rounds a box to whole pixels; a text a pixel past its box is not visibly cut. */
private const val SLACK_PX = 1.5f
