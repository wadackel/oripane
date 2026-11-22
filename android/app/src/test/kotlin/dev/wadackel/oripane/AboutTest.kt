package dev.wadackel.oripane

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AboutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aBuildFromToolsPhoneShowsItsCommitAndAnyOtherOnlyItsVersion() {
        assertThat(BuildInfo.parse("0.1.0+95cd251b3e6a").line).isEqualTo("0.1.0 · 95cd251b3e6a")
        assertThat(BuildInfo.parse("0.1.0").line).isEqualTo("0.1.0")
        assertThat(BuildInfo.parse("0.1.0").commit).isNull()
        assertThat(BuildInfo.parse(null).version).isEmpty()
    }

    @Test
    fun everyNoticeTheBuildShipsHasItsLicenceText() {
        val content = loadAboutContent(context)
        assertThat(content.ownLicense.text).startsWith("MIT License")
        assertThat(content.notices).isNotEmpty()
        for (n in content.notices) {
            assertThat(n.licenses).isNotEmpty()
            n.licenses.forEach { assertThat(it.text).isNotEmpty() }
        }
    }

    @Test
    fun fontsComeFirstThenTheGroupsByName() {
        val names = loadAboutContent(context).notices.map { it.name }
        assertThat(names.take(2)).containsExactly("Inter", "Inter Tight").inOrder()
        val rest = names.drop(2)
        assertThat(rest).isInOrder(compareBy<String> { it.lowercase() })
        assertThat(rest).contains("AndroidX Lifecycle")
        assertThat(rest).doesNotContain("Compose BOM")
    }

    @Test
    fun aGroupKeepsEveryLicenceItsArtifactsCarry() {
        val json = """{"licenses":{"MIT":{"name":"MIT License","content":"m"},"Apache-2.0":{"name":"Apache License 2.0","content":"a"}},
            "libraries":[{"uniqueId":"g.x:one","licenses":["Apache-2.0"]},{"uniqueId":"g.x:two","licenses":["MIT","Apache-2.0"]},{"uniqueId":"g.x:three","licenses":[]}]}"""
        val n = parseLibraryDefinitions(json).single()
        assertThat(n.licenses.map { it.name }).containsExactly("Apache License 2.0", "MIT License").inOrder()
        assertThat(n.label).isEqualTo("Apache 2.0 / MIT")
    }

    @Test
    fun aParagraphJoinsWhileHeadingsAndListItemsKeepTheirLines() {
        val text = "TERMS AND CONDITIONS\n\nThe first line of a paragraph\ngoes on here.\n- an item\n- another"
        assertThat(reflowLicense(text)).isEqualTo("TERMS AND CONDITIONS\n\nThe first line of a paragraph goes on here.\n- an item\n- another")
    }

    @Test
    fun groupsAreNamedForReaders() {
        assertThat(groupName("androidx.compose.ui", listOf("UI"))).isEqualTo("Compose UI")
        assertThat(groupName("androidx.arch.core", listOf("Core"))).isEqualTo("AndroidX Arch Core")
        assertThat(groupName("org.jetbrains.kotlinx", listOf("a", "b"))).isEqualTo("Kotlin Coroutines")
        assertThat(groupName("com.example.thing", listOf("Thing"))).isEqualTo("Thing")
    }
}
