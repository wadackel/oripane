/**
 * The design tokens: colours by role, the two typefaces and their text styles, the spacing steps and
 * the corner radii. `docs/design.md` gives the rules these values come from.
 */
package dev.wadackel.oripane

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The app's colours by role; the screens never spell out a hex value themselves. */
object Palette {
    val bg = Color(0xFF1A1B1D)
    /** A surface on the ground: a group of rows, a card, a tile, a sheet, a plate over the video. */
    val surface1 = Color(0xFF232427)
    /** A surface inside a surface1, or a round secondary button. */
    val surface2 = Color(0xFF2C2D31)
    val surface3 = Color(0xFF36373C)
    /** The line between rows of one surface. */
    val divider = Color.White.copy(alpha = 0.04f)
    /** The 1 px light along a raised surface's top edge. */
    val highlight = Color.White.copy(alpha = 0.07f)
    val text = Color(0xFFF2F2F3)
    val textMuted = Color(0xFF9D9EA4)
    val textFaint = Color(0xFF6E6F75)
    val icon = Color(0xFFC9CACF)

    /**
     * White leads: the one primary action, the chosen cell, the selection ring, a switch that is on.
     * Text and icons on it take [onLead].
     */
    val lead = text
    val onLead = bg
    /** The ground of a chosen segment: white at 10 %. */
    val chosen = Color.White.copy(alpha = 0.10f)
    /** The recessed track behind segments and the icon square on a tinted card. */
    val well = Color.Black.copy(alpha = 0.26f)

    val danger = Color(0xFFFF7A70)
    val statusGood = Color(0xFF3DD68C)
    val statusWarn = Color(0xFFF2B84B)
    val statusUnknown = textFaint

    /** Keys of the key row over the video. */
    val keyBg = Color.White.copy(alpha = 0.06f)
    val keyBorder = Color(0xFF5E646D).copy(alpha = 0.60f)
    val keyPressed = Color.White.copy(alpha = 0.20f)
}

/** Spacing steps: every gap and padding is one of these. */
object Space {
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
}

/** Corner radii, concentric: a surface, a part inside it, a part inside that. Only pressable round things are circles. */
object Radius {
    val surface = 16.dp
    val inner = 12.dp
    val innermost = 9.dp
    val sheet = 22.dp
}

/** Sizes of the controls every screen shares. */
object Sizes {
    /** The white round primary action, the display-mode control beside it, and any control in a row with them. */
    val primary = 44.dp
    /** A round grey secondary button: back, settings, details. */
    val secondary = 36.dp
    val rowMin = 52.dp
    /** A menu row on the stream's sheet, one line each. */
    val menuRow = 48.dp
}

// One variable font file per family; its default instance is not the weight asked for, so each
// weight sets the axis itself.
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600))

/** Names and titles: page titles, a Mac's name, the wordmark. */
val InterTight = FontFamily(variable(R.font.inter_tight, 500), variable(R.font.inter_tight, 580), variable(R.font.inter_tight, 600))

/** The text styles by role. Body text is [Inter]; names and titles are [InterTight] with tighter tracking. */
object Type {
    val title = TextStyle(fontFamily = InterTight, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
    val sheetTitle = TextStyle(fontFamily = InterTight, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
    /** "Oripane" beside the mark: lighter and wider than a title, so the name reads as a logo. */
    val wordmark = TextStyle(fontFamily = InterTight, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight(580), letterSpacing = 0.03.em)
    val label = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.005).em)
    val value = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.005).em, fontFeatureSettings = "tnum")
    val heading = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val segment = TextStyle(fontFamily = Inter, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp)
    val note = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
}

// The roles Material 3 would otherwise leave at its purple defaults are filled in too. Primary is the
// white lead, so a text field's cursor and a dialog's buttons follow the same rule as the pages.
private val colors = darkColorScheme(
    primary = Palette.lead,
    onPrimary = Palette.onLead,
    primaryContainer = Palette.chosen,
    onPrimaryContainer = Palette.text,
    inversePrimary = Palette.bg,
    secondary = Palette.lead,
    onSecondary = Palette.onLead,
    secondaryContainer = Palette.chosen,
    onSecondaryContainer = Palette.text,
    tertiary = Palette.lead,
    onTertiary = Palette.onLead,
    tertiaryContainer = Palette.chosen,
    onTertiaryContainer = Palette.text,
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.bg,
    onSurface = Palette.text,
    surfaceVariant = Palette.surface2,
    onSurfaceVariant = Palette.textMuted,
    surfaceTint = Palette.bg,
    inverseSurface = Palette.text,
    inverseOnSurface = Palette.bg,
    error = Palette.danger,
    onError = Palette.bg,
    errorContainer = Palette.danger.copy(alpha = 0.12f),
    onErrorContainer = Palette.danger,
    outline = Palette.surface3,
    outlineVariant = Palette.surface2,
    scrim = Color.Black,
    surfaceBright = Palette.surface3,
    surfaceContainer = Palette.surface1,
    surfaceContainerHigh = Palette.surface1,
    surfaceContainerHighest = Palette.surface2,
    surfaceContainerLow = Palette.bg,
    surfaceContainerLowest = Palette.bg,
    surfaceDim = Palette.bg,
)

private fun TextStyle.withInter() = copy(fontFamily = Inter)

private val typography = Typography().run {
    copy(
        displayLarge = displayLarge.withInter(),
        displayMedium = displayMedium.withInter(),
        displaySmall = displaySmall.withInter(),
        headlineLarge = headlineLarge.withInter(),
        headlineMedium = headlineMedium.withInter(),
        headlineSmall = headlineSmall.withInter(),
        titleLarge = titleLarge.withInter(),
        titleMedium = titleMedium.withInter(),
        titleSmall = titleSmall.withInter(),
        bodyLarge = bodyLarge.withInter(),
        bodyMedium = bodyMedium.withInter(),
        bodySmall = bodySmall.withInter(),
        labelLarge = labelLarge.withInter(),
        labelMedium = labelMedium.withInter(),
        labelSmall = labelSmall.withInter(),
    )
}

/** Dark only: no light theme and no colours taken from the wallpaper. */
@Composable
fun OripaneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
