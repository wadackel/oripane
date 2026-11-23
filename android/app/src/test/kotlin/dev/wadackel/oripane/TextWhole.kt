package dev.wadackel.oripane

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDate

/**
 * Fails when any text on screen, in any window, is not whole: a line that breaks inside a word, or
 * text cut off by its own box or by a box around it with no "…" to say so. A text with "…" was cut
 * on purpose. Text that a scrolling container cuts off is only out of view, and a text field
 * scrolls its own text. [shot] runs this for every screen and sheet the screenshot tests draw, at
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

/** A known defect other than text, in a test's own check: true until [end], approved as [knownText]'s entries are, failing after. */
fun knownUntil(end: LocalDate, why: String): Boolean {
    assertWithMessage("allowed until $end: $why").that(LocalDate.now()).isAtMost(end)
    return true
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
    if ((0 until layout.lineCount).any(layout::isLineEllipsized)) return null
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
            return "breaks inside a word: \"${text.substring(layout.getLineStart(line), end)}\" / \"${text.substring(end, layout.getLineEnd(line + 1))}\""
        }
    }
    // Not `hasVisualOverflow`: it compares the text's width with the width it was offered, so any
    // text narrower than its row reads as overflowing. A line's own width, not its right end, as a
    // centred line is placed in the width offered. A line counts as cut when its baseline is: the
    // line box is taller than the glyphs, and a key or a dot sits a glyph in a box a little shorter.
    for (line in 0 until layout.lineCount) {
        val width = layout.getLineRight(line) - layout.getLineLeft(line)
        if (width > layout.size.width + SLACK_PX) return "cut off by its own width: a line of %.0f px in %d".format(width, layout.size.width)
        if (layout.getLineBaseline(line) > layout.size.height + SLACK_PX) return "cut off by its own height: line ${line + 1} of ${layout.lineCount} below its ${layout.size.height} px"
    }
    if (node.inScroll()) return null
    val shown = node.boundsInRoot
    val clipped = shown.width < node.size.width - SLACK_PX || shown.height < node.size.height - SLACK_PX
    return if (clipped) "cut off by a box around it: ${node.size.width}x${node.size.height} shows ${shown.width.toInt()}x${shown.height.toInt()}" else null
}

private fun SemanticsNode.inScroll(): Boolean {
    var n: SemanticsNode? = parent
    while (n != null) {
        if (SemanticsProperties.VerticalScrollAxisRange in n.config || SemanticsProperties.HorizontalScrollAxisRange in n.config) return true
        n = n.parent
    }
    return false
}

/** Layout rounds a box to whole pixels; a text a pixel past its box is not visibly cut. */
private const val SLACK_PX = 1.5f
