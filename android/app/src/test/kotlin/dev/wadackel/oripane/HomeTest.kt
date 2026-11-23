package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

class HomeTest {
    private val pairing = Pairing(ByteArray(16) { 1 }, ByteArray(32) { 2 })
    private fun mac(id: String, lastUsed: Long) = SavedMac(hostId = id, name = id, pairing = pairing, lastUsed = lastUsed)

    private val studio = mac("studio", 30)
    private val mini = mac("mini", 20)
    private val pro = mac("pro", 10)
    private val imac = Discovered("iMac", "10.0.2.97", null)
    private val sameAsMini = Discovered("mini", "10.0.2.2", "mini")

    private val portNote = "Leave out the port; Oripane uses 47800."
    private val ipv6Note = "Oripane connects over IPv4; enter an IPv4 address or a name."

    @Test
    fun onlyNamesThatRepeatGetAnAddress() {
        val twin = mac("studio-2", 5).copy(name = "studio")
        val sameNameFound = Discovered("pro", "10.0.2.50", "other-pro")
        assertThat(repeatedNames(listOf(studio, twin, pro), listOf(sameNameFound, imac))).containsExactly("studio", "pro")
        assertThat(repeatedNames(listOf(mini), listOf(sameAsMini))).isEmpty()
    }

    @Test
    fun aRepeatedSavedMacShowsItsLanAddressBeforeItsTailnetOne() {
        assertThat(studio.copy(lanAddrs = listOf("10.0.2.2"), tailnet = listOf("100.64.0.9")).shownAddress).isEqualTo("10.0.2.2")
        assertThat(studio.copy(tailnet = listOf("100.64.0.9")).shownAddress).isEqualTo("100.64.0.9")
    }

    @Test
    fun aPortIsSeenAfterFullWidthCharactersAreNormalized() {
        assertThat(normalizeHost(" １０．０．２．２ ")).isEqualTo("10.0.2.2")
        assertThat(normalizeHost("10。0。2。2")).isEqualTo("10.0.2.2")
        assertThat(addressProblem(normalizeHost("10.0.2.2:47940"))).isEqualTo(portNote)
        assertThat(addressProblem(normalizeHost("10.0.2.2：４７９４０"))).isEqualTo(portNote)
        assertThat(addressProblem(normalizeHost("10.0.2.2"))).isNull()
        assertThat(addressProblem(normalizeHost("mac.local"))).isNull()
        assertThat(addressProblem(normalizeHost("http://mac.local:47800"))).isEqualTo(portNote)
    }

    @Test
    fun anIPv6AddressIsToldItIsNotSupportedRatherThanToLeaveOutAPort() {
        for (typed in listOf("fe80::1", "::1", "2001:db8::1", "[::1]", "[::1]:47800", "fe80::1%wlan0", "ｆｅ８０：：１")) {
            assertThat(addressProblem(normalizeHost(typed))).isEqualTo(ipv6Note)
        }
    }

    @Test
    fun withNothingSavedOrFoundThereAreNoTilesAndNoCard() {
        assertThat(homeEntries(emptyList(), emptyList())).isEmpty()
        assertThat(chosenEntry(emptyList(), null)).isNull()
    }

    @Test
    fun theSavedMacsComeNewestFirstThenTheFoundOnes() {
        assertThat(homeEntries(listOf(pro, studio, mini), listOf(imac)))
            .containsExactly(HomeEntry.Saved(studio), HomeEntry.Saved(mini), HomeEntry.Saved(pro), HomeEntry.Found(imac)).inOrder()
    }

    @Test
    fun aFoundMacWithTheIdOfASavedMacIsNotListedAgain() {
        assertThat(homeEntries(listOf(studio, mini), listOf(sameAsMini, imac)))
            .containsExactly(HomeEntry.Saved(studio), HomeEntry.Saved(mini), HomeEntry.Found(imac)).inOrder()
    }

    @Test
    fun foundMacsKeepTheOrderTheyWereFoundIn() {
        val later = Discovered("Mac Pro", "10.0.2.96", "other")
        assertThat(homeEntries(listOf(studio), listOf(later, imac)))
            .containsExactly(HomeEntry.Saved(studio), HomeEntry.Found(later), HomeEntry.Found(imac)).inOrder()
    }

    @Test
    fun theCardShowsThePickedMacWhileItIsListedAndTheFirstTileOtherwise() {
        val entries = homeEntries(listOf(pro, studio), listOf(imac))
        assertThat(chosenEntry(entries, HomeEntry.Saved(pro).key)).isEqualTo(HomeEntry.Saved(pro))
        assertThat(chosenEntry(entries, HomeEntry.Found(imac).key)).isEqualTo(HomeEntry.Found(imac))
        assertThat(chosenEntry(entries, "saved:gone")).isEqualTo(HomeEntry.Saved(studio))
        assertThat(chosenEntry(entries, null)).isEqualTo(HomeEntry.Saved(studio))
    }

    @Test
    fun aMacsStateComesFromBonjourAndTheLastFailure() {
        val tailnet = pro.copy(tailnet = listOf("100.64.0.3"))
        assertThat(macState(HomeEntry.Saved(mini), listOf(sameAsMini), null)).isEqualTo(MacState.NEARBY)
        assertThat(macState(HomeEntry.Saved(tailnet), listOf(sameAsMini), null)).isEqualTo(MacState.AWAY)
        assertThat(macState(HomeEntry.Found(imac), listOf(imac), null)).isEqualTo(MacState.NOT_ADDED)
        assertThat(macState(HomeEntry.Saved(mini), listOf(sameAsMini), HomeFailure(HomeEntry.Saved(mini).key, FailReason.CONNECT_FAILED, "x"))).isEqualTo(MacState.UNREACHABLE)
        assertThat(macState(HomeEntry.Saved(mini), listOf(sameAsMini), HomeFailure(HomeEntry.Saved(pro).key, FailReason.CONNECT_FAILED, "x"))).isEqualTo(MacState.NEARBY)
        assertThat(macStateText(HomeEntry.Saved(mini), MacState.NEARBY, emptySet())).isEqualTo("Ready · LAN")
        assertThat(macStateText(HomeEntry.Saved(tailnet.copy(lastUsed = 0)), MacState.AWAY, emptySet())).isEqualTo("Tailscale")
        assertThat(macStateText(HomeEntry.Found(imac), MacState.NOT_ADDED, setOf("iMac"))).isEqualTo("Nearby · not added yet · 10.0.2.97")
    }

    @Test
    fun onlyAFailureToReachTheMacSaysNotReachableAndAnyOtherKeepsItsState() {
        val unreached = listOf(FailReason.CONNECT_FAILED, FailReason.GAVE_UP)
        unreached.forEach { reason ->
            assertThat(macState(HomeEntry.Saved(mini), listOf(sameAsMini), HomeFailure(HomeEntry.Saved(mini).key, reason, "x"))).isEqualTo(MacState.UNREACHABLE)
        }
        (FailReason.entries - unreached).forEach { reason ->
            assertThat(macState(HomeEntry.Found(imac), listOf(imac), HomeFailure(HomeEntry.Found(imac).key, reason, "x"))).isEqualTo(MacState.NOT_ADDED)
            assertThat(macState(HomeEntry.Saved(mini), listOf(sameAsMini), HomeFailure(HomeEntry.Saved(mini).key, reason, "x"))).isEqualTo(MacState.NEARBY)
        }
    }

    @Test
    fun aFailureThatNamesTheMacFollowsARenameAndOneThatNamesNoMacStaysAsSaid() {
        val failed = ClientState.Failed.naming(FailReason.CONNECT_FAILED, "Studio") { "Couldn't reach $it." }
        assertThat(failed.message).isEqualTo("Couldn't reach Studio.")
        val named = HomeFailure(HomeEntry.Saved(studio).key, failed.reason, failed.message, failed.naming)
        assertThat(named.messageFor("Desk")).isEqualTo("Couldn't reach Desk.")
        val plain = HomeFailure(HomeEntry.Found(imac).key, FailReason.PAIR_DENIED, "The Mac declined the pairing.")
        assertThat(plain.messageFor("Desk")).isEqualTo("The Mac declined the pairing.")
    }

    @Test
    fun aMacBookTakesTheLaptopIconAndEveryOtherMacTheDisplay() {
        assertThat(macKindIcon("Ada's MacBook Pro")).isEqualTo(R.drawable.ic_laptop)
        assertThat(macKindIcon("Mac Studio")).isEqualTo(R.drawable.ic_mac)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        Calendar.getInstance().apply { clear(); set(year, month, day, hour, minute) }.timeInMillis

    @Test
    fun aTimeOnTheSameDayReadsTodayWithTheClockTime() {
        assertThat(lastUsedText(at(2026, Calendar.OCTOBER, 23, 14, 2), now = at(2026, Calendar.OCTOBER, 23, 18, 30))).isEqualTo("Today 14:02")
    }

    @Test
    fun aTimeOnAnotherDayReadsTheShortMonthDayAndClockTime() {
        assertThat(lastUsedText(at(2026, Calendar.OCTOBER, 21, 22, 40), now = at(2026, Calendar.OCTOBER, 23, 9, 0))).isEqualTo("Oct 21, 22:40")
    }

    @Test
    fun anUnsetTimeReadsEmpty() {
        assertThat(lastUsedText(0)).isEmpty()
        assertThat(lastUsedText(-1)).isEmpty()
    }
}
