/**
 * About, the last section of Settings: which build this is, where its source is, Oripane's own
 * licence, and the notices for what the APK bundles. The notices are read from what the build
 * writes into the APK, so the list follows the dependencies without being kept by hand.
 */
package dev.wadackel.oripane

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** A licence as About shows it: a short label at a row's end, the full name and text in its sheet. */
data class NoticeLicense(val label: String, val name: String, val text: String)

/**
 * One row of the open-source list: a bundled font, or the artifacts of one Maven group together.
 * A group rather than an artifact, because the build pulls in seventy artifacts and a row for each
 * reads as noise; the artifacts are named in the row's sheet.
 */
data class OpenSourceNotice(
    val name: String,
    val detail: String,
    val parts: List<String>,
    val licenses: List<NoticeLicense>,
    val font: FontFamily? = null,
) {
    /** What the row shows at its end: every licence of the group, usually one. */
    val label get() = licenses.joinToString(" / ") { it.label }.ifEmpty { "?" }
}

/** The version and, for a build `tools/phone` made, the commit it came from (`0.1.0+95cd251b3e6a`). */
data class BuildInfo(val version: String, val commit: String?) {
    val line get() = if (commit == null) version else "$version · $commit"

    companion object {
        fun parse(versionName: String?): BuildInfo {
            val name = versionName.orEmpty()
            return BuildInfo(name.substringBefore('+'), name.substringAfter('+', "").ifEmpty { null })
        }
    }
}

const val SOURCE_URL = "https://github.com/wadackel/oripane"

private val ListItem = Regex("""^(\s|[-*#]|\d+[.)]|\([a-z0-9]+\))""")

private fun isHeading(line: String): Boolean {
    val rule = line.all { it == '-' || it == '=' || it.isWhitespace() }
    val capitals = line.length <= 40 && !line.endsWith(".") && line.any(Char::isLetter) && line == line.uppercase()
    return line.startsWith("#") || rule || capitals
}

/**
 * Joins the lines of each paragraph so the text wraps to the screen rather than at the column the
 * file was written for: licence files break near 80 columns, and the cover screen holds about
 * sixty, so left alone every line wrapped once more into a ragged column. Headings, rules and list
 * items keep their lines; a heading is a short line in capitals, since the warranty paragraphs are
 * capitals too.
 */
fun reflowLicense(text: String): String {
    val lines = text.replace("\r\n", "\n").lines()
    val out = StringBuilder()
    lines.forEachIndexed { index, line ->
        out.append(line.trimEnd())
        val next = lines.getOrNull(index + 1) ?: return@forEachIndexed
        val joins = line.isNotBlank() && next.isNotBlank() && !isHeading(line.trim()) &&
            !isHeading(next.trim()) && !ListItem.containsMatchIn(next)
        out.append(if (joins) ' ' else '\n')
    }
    return out.toString().trim()
}

/** Names for groups whose id says little to a reader; the rest are named by rule in [groupName]. */
private val GroupNames = mapOf(
    "com.google.guava" to "Guava",
    "org.jetbrains" to "JetBrains Annotations",
    "org.jetbrains.kotlin" to "Kotlin",
    "org.jetbrains.kotlinx" to "Kotlin Coroutines",
    "org.jspecify" to "JSpecify",
)

private fun titled(segments: String) = segments.split('.').joinToString(" ") { if (it == "ui") "UI" else it.replaceFirstChar(Char::uppercaseChar) }

/** A group added later and missing from [GroupNames] falls back to its id: plainer, still correct. */
fun groupName(group: String, artifactNames: List<String>): String = GroupNames[group] ?: when {
    group.startsWith("androidx.compose.") -> "Compose " + titled(group.removePrefix("androidx.compose."))
    group.startsWith("androidx.") -> "AndroidX " + titled(group.removePrefix("androidx."))
    artifactNames.size == 1 -> artifactNames.single()
    else -> group
}

/** Short names for the row's end; a licence missing here shows its SPDX id. */
private val LicenseLabels = mapOf("Apache-2.0" to "Apache 2.0", "MIT" to "MIT", "OFL-1.1" to "OFL 1.1")

/**
 * Reads the definitions the AboutLibraries Gradle plugin writes into the APK, without its runtime
 * library: that would add a dependency to read three plain fields per entry. Unknown keys are
 * ignored, since the plugin adds fields between releases.
 */
fun parseLibraryDefinitions(json: String): List<OpenSourceNotice> {
    val root = JSONObject(json)
    val licenseObjects = root.getJSONObject("licenses")
    val licenses = licenseObjects.keys().asSequence().associateWith { key ->
        val license = licenseObjects.getJSONObject(key)
        NoticeLicense(LicenseLabels[key] ?: key, license.optString("name", key), reflowLicense(license.optString("content")))
    }
    val libraries = root.getJSONArray("libraries")
    data class Artifact(val group: String, val id: String, val version: String, val name: String, val licenses: List<String>)
    val artifacts = (0 until libraries.length()).map { i ->
        val library = libraries.getJSONObject(i)
        val (group, id) = library.getString("uniqueId").split(":", limit = 2)
        val keys = library.optJSONArray("licenses")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
        Artifact(group, id, library.optString("artifactVersion"), library.optString("name", id), keys)
    }
    // A key without a text still makes an entry, empty, so the test that every notice has its text fails.
    val notice = { key: String -> licenses[key] ?: NoticeLicense(key, key, "") }
    return artifacts.groupBy { it.group }.map { (group, members) ->
        OpenSourceNotice(
            name = groupName(group, members.map { it.name }),
            detail = group,
            parts = members.map { if (it.version.isEmpty()) it.id else "${it.id} ${it.version}" },
            licenses = members.flatMap { it.licenses }.distinct().map(notice),
        )
    }
}

/** A font in `res/font`, with its licence copied into the APK's assets by the build. */
private class BundledFont(val name: String, val asset: String, val family: FontFamily)

private val BundledFonts = listOf(
    BundledFont("Inter", "licenses/Inter-OFL.txt", Inter),
    BundledFont("Inter Tight", "licenses/InterTight-OFL.txt", InterTight),
)

/** Everything About reads from the APK: Oripane's licence and the list, fonts first, then by name. */
class AboutContent(val ownLicense: NoticeLicense, val notices: List<OpenSourceNotice>)

/** Copied from the repository's LICENSE by the build, so the two cannot disagree. */
private const val OWN_LICENSE_ASSET = "licenses/Oripane-LICENSE.txt"

fun loadAboutContent(context: Context): AboutContent {
    val asset = { path: String -> context.assets.open(path).bufferedReader().use { it.readText() } }
    val definitions = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
    val ofl = { path: String -> NoticeLicense(LicenseLabels.getValue("OFL-1.1"), "SIL Open Font License 1.1", reflowLicense(asset(path))) }
    val fonts = BundledFonts.map { OpenSourceNotice(it.name, "Font", emptyList(), listOf(ofl(it.asset)), it.family) }
    return AboutContent(
        ownLicense = NoticeLicense("MIT", "MIT License", reflowLicense(asset(OWN_LICENSE_ASSET))),
        notices = fonts + parseLibraryDefinitions(definitions).sortedBy { it.name.lowercase() },
    )
}

/** The version this app was installed as. */
@Composable
fun rememberBuildInfo(): BuildInfo {
    val context = LocalContext.current
    return remember(context) { BuildInfo.parse(context.packageManager.getPackageInfo(context.packageName, 0).versionName) }
}

/** What a sheet of About shows: Oripane's own licence, or one row of the list. */
sealed interface AboutSheet {
    data class Own(val license: NoticeLicense) : AboutSheet
    data class Notice(val notice: OpenSourceNotice) : AboutSheet
}

/**
 * About's page body: the mark, name and build in the middle, then the source and the licence on
 * one surface and the open-source list on another. A row asks [onSheet] to open its notice, which
 * [AboutNoticeSheet] shows. Read on the main thread: the definitions are about 25 KB and the
 * reading takes a few milliseconds.
 */
@Composable
fun AboutBody(color: androidx.compose.ui.graphics.Color = Palette.surface1, onSheet: (AboutSheet) -> Unit) {
    val context = LocalContext.current
    val content = remember(context) { loadAboutContent(context) }
    val build = rememberBuildInfo()
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(top = Space.s, bottom = Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.oripane_symbol), contentDescription = null, modifier = Modifier.size(64.dp))
        Text("Oripane", style = Type.title, color = Palette.text, modifier = Modifier.padding(top = Space.s))
        Text(build.line, style = Type.value, color = Palette.textMuted, modifier = Modifier.padding(top = 2.dp))
    }
    SettingsCard(
        listOf(
            { p -> ExternalRow("Source code", SOURCE_URL.removePrefix("https://"), p) { runCatching { uri.openUri(SOURCE_URL) }.onFailure { log("about source_link failed=${it.javaClass.simpleName}") } } },
            { p -> LinkRow("License", p, value = content.ownLicense.label) { onSheet(AboutSheet.Own(content.ownLicense)) } },
        ),
        color,
    )
    SectionLabel("Open source · ${content.notices.size}")
    SettingsCard(content.notices.map { n -> { p: PaddingValues -> LinkRow(n.name, p, value = n.label) { onSheet(AboutSheet.Notice(n)) } } }, color)
}

/** The sheet a row of About opened. */
@Composable
fun AboutNoticeSheet(sheet: AboutSheet, onDismiss: () -> Unit) {
    when (sheet) {
        is AboutSheet.Own -> NoticeSheet("Oripane", sheet.license.name, emptyList(), null, listOf(sheet.license), onDismiss)
        is AboutSheet.Notice -> NoticeSheet(sheet.notice.name, "${sheet.notice.detail} · ${sheet.notice.licenses.joinToString(" / ") { it.name }}", sheet.notice.parts, sheet.notice.font, sheet.notice.licenses, onDismiss)
    }
}

/** A row that leaves the app: a link-out icon where a page row has its chevron. */
@Composable
private fun ExternalRow(text: String, sub: String, contentPadding: PaddingValues, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(contentPadding).heightIn(min = Sizes.rowMin - 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = Type.label, color = Palette.text)
            Text(sub, style = Type.note, color = Palette.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        RowIcon(R.drawable.ic_external, Palette.textFaint)
    }
}

/** The widest a sheet of licence text grows: the page's width, so its lines stay short enough to read. */
private val NoticeSheetMaxWidth = 560.dp

/**
 * One notice in full: its name, where it comes from, the artifacts of a group, a line set in the
 * font for a font, and the licence text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoticeSheet(title: String, subtitle: String, parts: List<String>, font: FontFamily?, licenses: List<NoticeLicense>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = NoticeSheetMaxWidth,
        containerColor = Palette.surface1,
        shape = sheetShape,
        dragHandle = { SheetHandle() },
        contentWindowInsets = { sheetInsets() },
        properties = sheetProperties(),
    ) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(bottom = Space.l)) {
            SheetTitle(title)
            Text(subtitle, style = Type.note, color = Palette.textMuted, modifier = Modifier.padding(horizontal = 24.dp))
            if (parts.isNotEmpty()) {
                Text(parts.joinToString(" · "), style = Type.body, color = Palette.text, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = Space.m))
            }
            if (font != null) {
                Text("Aa Bb 0123 → Oripane", style = Type.title.copy(fontFamily = font), color = Palette.text, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = Space.m))
            }
            licenses.forEach { license ->
                if (licenses.size > 1) Text(license.name, style = Type.heading, color = Palette.textMuted, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = Space.l))
                Text(license.text, style = Type.body, color = Palette.textMuted, textAlign = TextAlign.Start, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = Space.m))
            }
        }
    }
}
