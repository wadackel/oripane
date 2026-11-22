/**
 * The building blocks every page shown while not connected shares: its layout and header, its
 * buttons, the grey surfaces that group rows, and the rows themselves. `docs/design.md` says which
 * part a setting or an action takes.
 */
package dev.wadackel.oripane

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val surfaceShape = RoundedCornerShape(Radius.surface)

/**
 * A screen of one column, at most 560 dp wide and centred. The window draws edge to edge, so the
 * column keeps clear of the system bars itself.
 */
@Composable
internal fun Page(title: String?, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.m, end = Space.m, bottom = 32.dp)) {
            if (title != null) PageHeader(title, onBack, Modifier.offset(x = -(Sizes.primary - Sizes.secondary) / 2 - (Space.m - Space.s)))
            content()
        }
    }
}

/** A page's 56 dp header: a round back button when there is somewhere to go back to, the title, and [actions] at the end. */
@Composable
internal fun PageHeader(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (onBack != null) RoundButton(R.drawable.ic_back, "Back", onClick = onBack)
        Text(title, style = Type.title, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
        actions()
    }
}

/**
 * A round icon button: grey and 36 dp for a secondary action (back, settings, details), or white
 * and 44 dp for the one primary action of a screen ([primary]).
 */
@Composable
internal fun RoundButton(
    @DrawableRes icon: Int,
    description: String?,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    background: Color = if (primary) Palette.lead else Palette.surface2,
    tint: Color = if (primary) Palette.onLead else Palette.icon,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val size = if (primary) Sizes.primary else Sizes.secondary
    // The 36 dp button keeps a 44 dp touch target around it.
    Box(modifier.size(Sizes.primary).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, tint = tint.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(if (primary) 20.dp else 18.dp))
        }
    }
}

/** An icon-only button without a ground, with a 44 dp round touch target. */
@Composable
internal fun IconSpot(@DrawableRes icon: Int, description: String?, tint: Color = Palette.icon, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(Sizes.primary).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The heading above a surface; a screen with a single surface has none. */
@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = Type.heading, color = Palette.textMuted, modifier = Modifier.padding(start = 4.dp, top = Space.m, bottom = Space.xs).semantics { heading() })
}

/** A grey surface that groups what is on it. */
@Composable
internal fun Card(modifier: Modifier = Modifier, color: Color = Palette.surface1, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(surfaceShape).background(color).then(modifier), content = content)
}

/** A sentence that explains what the controls below or above it cannot say themselves. */
@Composable
internal fun Help(text: String, modifier: Modifier = Modifier) {
    Text(text, fontFamily = Inter, fontSize = 12.5.sp, lineHeight = 18.sp, color = Palette.textMuted, modifier = modifier.padding(horizontal = 4.dp))
}

@Composable
private fun RowScope.ButtonLabel(text: String, @DrawableRes icon: Int?, color: Color) {
    if (icon != null) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.xs))
    }
    Text(text, style = Type.label, color = color)
}

@Composable
private fun PillButton(text: String, @DrawableRes icon: Int?, ground: Color, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(Sizes.primary)
            .clip(CircleShape)
            .background(if (enabled) ground else Palette.surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) { ButtonLabel(text, icon, if (enabled) color else Palette.textFaint) }
}

/** The page's one primary action as words, white and as wide as the column; on a page with a form it is the action the form exists for. */
@Composable
internal fun PrimaryButton(text: String, @DrawableRes icon: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    PillButton(text, icon, Palette.lead, Palette.onLead, enabled, Modifier.fillMaxWidth(), onClick)
}

/** A grey pill: an action beside or below the primary one. */
@Composable
internal fun SecondaryButton(text: String, @DrawableRes icon: Int? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    PillButton(text, icon, Palette.surface2, Palette.text, enabled, modifier, onClick)
}

/** Something that cannot be undone: a surface of its own at the end of the page, red words, and a confirmation on press. */
@Composable
internal fun DangerButton(text: String, @DrawableRes icon: Int = R.drawable.ic_delete, onClick: () -> Unit) {
    Card {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.rowMin).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = Palette.danger, modifier = Modifier.size(20.dp))
            Text(text, style = Type.label, color = Palette.danger)
        }
    }
}

@Composable
internal fun Tag(text: String, background: Color = Palette.surface2) {
    Text(
        text,
        fontFamily = Inter,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = Palette.textMuted,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** A 1 px line across the top of what follows, between two rows of one surface. */
internal fun Modifier.rowDivider(show: Boolean) = if (!show) this else drawBehind { drawLine(Palette.divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }

/**
 * A surface of rows with a faint line between them. Each row gets the surface's padding and applies
 * it itself: a row that is one control puts it inside its click area, so the press reaches the
 * surface's edges.
 */
@Composable
internal fun SettingsCard(rows: List<@Composable (PaddingValues) -> Unit>, color: Color = Palette.surface1) {
    Card(Modifier.padding(vertical = 4.dp), color = color) {
        rows.forEachIndexed { i, row ->
            Box(Modifier.rowDivider(i > 0)) { row(rowPadding) }
        }
    }
}

internal val rowPadding = PaddingValues(horizontal = Space.m, vertical = Space.xs)

/** A row's icon at its start: 20 dp in the icon grey. */
@Composable
internal fun RowIcon(@DrawableRes icon: Int, tint: Color = Palette.icon) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
}

@Composable
internal fun Chevron(tint: Color = Palette.textFaint, size: Dp = 16.dp) {
    Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/**
 * A row that opens a page or a list of choices, showing the current [value] when it has one, or a
 * [sub] line under the label (the index of Settings). [selected] rings it: the pane beside it shows it.
 */
@Composable
internal fun LinkRow(
    text: String,
    contentPadding: PaddingValues,
    value: String? = null,
    @DrawableRes icon: Int? = null,
    sub: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val ring = if (selected) Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(Radius.inner)).background(Color.White.copy(alpha = 0.06f)).border(1.5.dp, Palette.lead, RoundedCornerShape(Radius.inner)) else Modifier
    Row(
        Modifier.fillMaxWidth().then(ring).clickable(onClick = onClick).padding(contentPadding).heightIn(min = Sizes.rowMin - 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(text, style = Type.label, color = Palette.text)
            if (sub != null) Text(sub, style = Type.note, color = Palette.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        // The label wraps on a narrow screen rather than the value: the value is what the row is read for.
        if (value != null) Text(value, style = Type.value, color = Palette.textMuted, maxLines = 1)
        Chevron()
    }
}
