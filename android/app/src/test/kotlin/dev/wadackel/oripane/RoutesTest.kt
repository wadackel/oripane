package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoutesTest {
    private val pairing = Pairing(ByteArray(16) { 1 }, ByteArray(32) { 2 })
    private val mac = SavedMac(
        hostId = "H1",
        name = "mini",
        pairing = pairing,
        lanAddrs = listOf("192.168.1.11", "10.0.2.2"),
        tailnet = listOf("100.101.102.103"),
        lastUsed = 10,
    )

    private fun lan(addr: String) = Candidate(RouteKind.LAN, addr)
    private fun tailnet(addr: String) = Candidate(RouteKind.TAILNET, addr)

    @Test
    fun wifiWithAMatchingServiceTriesItFirstThenTheOtherLanAddressesThenTheTailnet() {
        val found = listOf(Discovered("other", "192.168.1.99", "H2"), Discovered("mini", "10.0.2.2", "H1"))
        assertThat(routeCandidates(wifi = true, discovered = found, mac = mac))
            .containsExactly(lan("10.0.2.2"), lan("192.168.1.11"), tailnet("100.101.102.103")).inOrder()
    }

    @Test
    fun withoutWifiOnlyTheTailnetIsTried() {
        val found = listOf(Discovered("mini", "10.0.2.2", "H1"))
        assertThat(routeCandidates(wifi = false, discovered = found, mac = mac)).containsExactly(tailnet("100.101.102.103"))
    }

    @Test
    fun wifiWithoutAMatchingServiceTriesTheKnownLanAddressesThenTheTailnet() {
        val found = listOf(Discovered("other", "192.168.1.99", "H2"), Discovered("legacy", "192.168.1.11", null))
        assertThat(routeCandidates(wifi = true, discovered = found, mac = mac))
            .containsExactly(lan("192.168.1.11"), lan("10.0.2.2"), tailnet("100.101.102.103")).inOrder()
    }

    @Test
    fun withoutWifiOrTailnetThereIsNothingToTry() {
        assertThat(routeCandidates(wifi = false, discovered = emptyList(), mac = mac.copy(tailnet = emptyList()))).isEmpty()
    }

    @Test
    fun candidateTimeoutsFollowTheRouteKind() {
        assertThat(lan("10.0.2.2").timeoutMs).isEqualTo(2000)
        assertThat(tailnet("100.64.0.1").timeoutMs).isEqualTo(5000)
    }

    @Test
    fun tailnetRangeIsOneHundredSixtyFourSlashTen() {
        assertThat(isTailnet("100.64.0.1")).isTrue()
        assertThat(isTailnet("100.127.255.255")).isTrue()
        assertThat(isTailnet("100.63.255.255")).isFalse()
        assertThat(isTailnet("100.128.0.1")).isFalse()
        assertThat(isTailnet("192.168.1.11")).isFalse()
        assertThat(isTailnet("mini.tail1234.ts.net")).isFalse()
        assertThat(isTailnet("100.300.0.1")).isFalse()
    }

    @Test
    fun dataSaverIsOnWhenAlwaysOrWhenAutomaticOffWifi() {
        assertThat(dataSaverActive(DataSaver.AUTO, wifi = true)).isFalse()
        assertThat(dataSaverActive(DataSaver.AUTO, wifi = false)).isTrue()
        assertThat(dataSaverActive(DataSaver.ALWAYS, wifi = true)).isTrue()
        assertThat(dataSaverActive(DataSaver.ALWAYS, wifi = false)).isTrue()
        assertThat(dataSaverActive(DataSaver.OFF, wifi = true)).isFalse()
        assertThat(dataSaverActive(DataSaver.OFF, wifi = false)).isFalse()
        assertThat(DataSaver.of("bogus")).isEqualTo(DataSaver.AUTO)
        assertThat(DataSaver.of(null)).isEqualTo(DataSaver.AUTO)
    }

    @Test
    fun reconnectsStartAtOnceThenBackOffToFiveSeconds() {
        assertThat((1..8).map(::reconnectDelayMs)).containsExactly(0L, 500L, 1000L, 2000L, 4000L, 5000L, 5000L, 5000L).inOrder()
    }

    private fun race() = RouteRace(listOf(lan("10.255.255.1"), lan("10.0.2.2"), tailnet("100.101.102.103")), startMs = 1_000)

    @Test
    fun aLanThatConnectsWithinTheGraceWinsOverATailnetThatConnectedFirst() {
        val r = race()
        r.succeeded(2, atMs = 1_100)
        assertThat(r.decide(nowMs = 1_100, wifi = false, bonjourSeen = false)).isEqualTo(RouteDecision.Wait)
        r.succeeded(1, atMs = 1_350)
        assertThat(r.decide(nowMs = 1_350, wifi = false, bonjourSeen = false)).isEqualTo(RouteDecision.Pick(1))
    }

    @Test
    fun theTailnetIsPickedOnceEveryLanFailedOrTheGracePassed() {
        val failed = race()
        failed.succeeded(2, atMs = 1_100)
        failed.failed(0)
        failed.failed(1)
        assertThat(failed.decide(nowMs = 1_150, wifi = false, bonjourSeen = false)).isEqualTo(RouteDecision.Pick(2))

        val slow = race()
        slow.succeeded(2, atMs = 1_100)
        assertThat(slow.nextCheckMs(nowMs = 1_100, wifi = false, bonjourSeen = false)).isEqualTo(1_400)
        assertThat(slow.decide(nowMs = 1_399, wifi = false, bonjourSeen = false)).isEqualTo(RouteDecision.Wait)
        assertThat(slow.decide(nowMs = 1_400, wifi = false, bonjourSeen = false)).isEqualTo(RouteDecision.Pick(2))
    }

    @Test
    fun nothingIsPickedWhenEveryRouteFailed() {
        val r = race()
        (0..2).forEach(r::failed)
        assertThat(r.decide(nowMs = 2_000, wifi = false, bonjourSeen = false)).isEqualTo(RouteDecision.NoRoute)
    }

    @Test
    fun onWifiTheTailnetWaitsForBonjourUntilItsWindowEnds() {
        val r = race()
        r.succeeded(2, atMs = 1_100)
        r.failed(0)
        r.failed(1)
        assertThat(r.decide(nowMs = 1_100, wifi = true, bonjourSeen = false)).isEqualTo(RouteDecision.Wait)
        assertThat(r.nextCheckMs(nowMs = 1_100, wifi = true, bonjourSeen = false)).isEqualTo(2_500)
        assertThat(r.decide(nowMs = 2_499, wifi = true, bonjourSeen = false)).isEqualTo(RouteDecision.Wait)
        assertThat(r.decide(nowMs = 2_500, wifi = true, bonjourSeen = false)).isEqualTo(RouteDecision.Pick(2))
        assertThat(r.decide(nowMs = 1_100, wifi = true, bonjourSeen = true)).isEqualTo(RouteDecision.Pick(2))
    }

    @Test
    fun onWifiEveryRouteFailingStillWaitsOutTheBonjourWindow() {
        val r = race()
        (0..2).forEach(r::failed)
        assertThat(r.decide(nowMs = 1_200, wifi = true, bonjourSeen = false)).isEqualTo(RouteDecision.Wait)
        assertThat(r.decide(nowMs = 2_500, wifi = true, bonjourSeen = false)).isEqualTo(RouteDecision.NoRoute)
    }

    @Test
    fun aLanAddressBonjourFindsDuringTheRaceJoinsItOnce() {
        val r = race()
        assertThat(r.add(lan("10.0.2.50"), atMs = 1_500)).isEqualTo(3)
        assertThat(r.add(lan("10.0.2.2"), atMs = 1_500)).isNull()
        (0..2).forEach(r::failed)
        r.succeeded(3, atMs = 1_600)
        assertThat(r.decide(nowMs = 1_600, wifi = true, bonjourSeen = true)).isEqualTo(RouteDecision.Pick(3))
    }

    @Test
    fun aLanAddressBonjourFindsAfterTheTailnetConnectedStillGetsItsGrace() {
        val r = race()
        r.succeeded(2, atMs = 1_100)
        (0..1).forEach(r::failed)
        val found = r.add(lan("10.0.2.50"), atMs = 1_800)!!
        assertThat(r.decide(nowMs = 1_800, wifi = true, bonjourSeen = true)).isEqualTo(RouteDecision.Wait)
        assertThat(r.nextCheckMs(nowMs = 1_800, wifi = true, bonjourSeen = true)).isEqualTo(2_100)
        r.succeeded(found, atMs = 1_900)
        assertThat(r.decide(nowMs = 1_900, wifi = true, bonjourSeen = true)).isEqualTo(RouteDecision.Pick(found))
    }

    @Test
    fun theReachableRoutesAfterThePickKeepTheirOrder() {
        val r = race()
        r.succeeded(2, atMs = 1_050)
        r.succeeded(1, atMs = 1_100)
        assertThat(r.reachable()).containsExactly(lan("10.0.2.2"), tailnet("100.101.102.103")).inOrder()
    }

    @Test
    fun reconnectingStopsTwoMinutesAfterItBegan() {
        assertThat(reconnectGaveUp(startedMs = 1_000, nowMs = 1_000 + 120_000)).isFalse()
        assertThat(reconnectGaveUp(startedMs = 1_000, nowMs = 1_000 + 120_001)).isTrue()
        assertThat(reconnectGaveUp(startedMs = 0, nowMs = 1_000_000)).isFalse()
    }

    @Test
    fun theBadgeNamesTheAttemptAndItsRoute() {
        assertThat(reconnectDetail(3, "tailnet")).isEqualTo("Attempt 3 · Tailscale")
        assertThat(reconnectDetail(1, "lan")).isEqualTo("Attempt 1 · LAN")
        assertThat(reconnectDetail(2, null)).isEqualTo("Attempt 2")
        assertThat(reconnectDetail(0, "lan")).isNull()
    }

    @Test
    fun failuresAfterAuthenticationReconnectByReason() {
        assertThat(nextStep(FailReason.CONNECT_FAILED, authenticated = true)).isEqualTo(NextStep.NEXT_CANDIDATE)
        assertThat(nextStep(FailReason.HANDSHAKE_FAILED, authenticated = true)).isEqualTo(NextStep.RETRY_LATER)
        assertThat(nextStep(FailReason.AUTH_UNKNOWN, authenticated = true)).isEqualTo(NextStep.STOP)
        assertThat(nextStep(FailReason.PAIR_DENIED, authenticated = true)).isEqualTo(NextStep.STOP)
        assertThat(nextStep(FailReason.REPLACED, authenticated = true)).isEqualTo(NextStep.STOP)
        assertThat(nextStep(FailReason.LIVENESS, authenticated = true)).isEqualTo(NextStep.RETRY_NOW)
        assertThat(nextStep(FailReason.IO, authenticated = true)).isEqualTo(NextStep.RETRY_NOW)
        assertThat(nextStep(FailReason.STREAM_FAILED, authenticated = true)).isEqualTo(NextStep.RETRY_LATER)
        assertThat(nextStep(FailReason.GAVE_UP, authenticated = true)).isEqualTo(NextStep.STOP)
    }

    @Test
    fun nothingReconnectsBeforeTheFirstAuthentication() {
        for (reason in FailReason.entries - FailReason.CONNECT_FAILED) {
            assertThat(nextStep(reason, authenticated = false)).isEqualTo(NextStep.STOP)
        }
        assertThat(nextStep(FailReason.CONNECT_FAILED, authenticated = false)).isEqualTo(NextStep.NEXT_CANDIDATE)
    }

    @Test
    fun anEmptyTailnetReportKeepsThePreviousOne() {
        val next = mac.updated("H1", "mini", pairing, "192.168.1.11", emptyList(), now = 20)
        assertThat(next.tailnet).containsExactly("100.101.102.103")
        assertThat(mac.updated("H1", "mini", pairing, "192.168.1.11", listOf("100.64.0.9"), now = 20).tailnet).containsExactly("100.64.0.9")
    }

    @Test
    fun tailnetAddressesNeverJoinTheLanList() {
        val next = mac.updated("H1", "mini", pairing, "100.101.102.103", listOf("100.101.102.103"), now = 20)
        assertThat(next.lanAddrs).containsExactly("192.168.1.11", "10.0.2.2").inOrder()
    }

    @Test
    fun lanAddressesStayUniqueNewestFirstAndKeepFour() {
        var m: SavedMac? = null
        for (addr in listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.1", "10.0.0.4")) m = m.updated("H1", "mini", pairing, addr, emptyList(), now = 1)
        assertThat(m!!.lanAddrs).containsExactly("10.0.0.4", "10.0.0.1", "10.0.0.3", "10.0.0.2").inOrder()
        m = m.updated("H1", "mini", pairing, "10.0.0.5", emptyList(), now = 2)
        assertThat(m.lanAddrs).containsExactly("10.0.0.5", "10.0.0.4", "10.0.0.1", "10.0.0.3").inOrder()
    }

    @Test
    fun anUpdateReplacesNameMaterialAndLastUsed() {
        val fresh = Pairing(ByteArray(16) { 9 }, ByteArray(32) { 9 })
        val next = mac.updated("H1", "renamed", fresh, "10.0.2.2", emptyList(), now = 99)
        assertThat(next.name).isEqualTo("renamed")
        assertThat(next.pairing).isSameInstanceAs(fresh)
        assertThat(next.lastUsed).isEqualTo(99)
        assertThat(next.lanAddrs).containsExactly("10.0.2.2", "192.168.1.11").inOrder()
    }

    @Test
    fun anAddressPicksTheMostRecentlyUsedRecordThatKnowsIt() {
        val older = mac.copy(hostId = "H0", lastUsed = 5)
        val other = mac.copy(hostId = "H2", lanAddrs = listOf("192.168.9.9"), tailnet = emptyList(), lastUsed = 50)
        val all = listOf(older, mac, other)
        assertThat(all.forAddress("10.0.2.2")?.hostId).isEqualTo("H1")
        assertThat(all.forAddress("100.101.102.103")?.hostId).isEqualTo("H1")
        assertThat(all.forAddress("192.168.9.9")?.hostId).isEqualTo("H2")
        assertThat(all.forAddress("192.168.1.1")).isNull()
    }

    @Test
    fun upsertReplacesTheRecordWithTheSameHostId() {
        val all = listOf(mac, mac.copy(hostId = "H2")).upsert(mac.copy(name = "new"))
        assertThat(all.map { it.hostId to it.name }).containsExactly("H2" to "mini", "H1" to "new")
    }

    @Test
    fun aSavedRecordIsUpdatedOnlyWithItsOwnMaterialOrItsRepairing() {
        val saved = listOf(mac, mac.copy(hostId = "H2", pairing = Pairing(ByteArray(16) { 7 }, ByteArray(32) { 7 })))
        val fresh = Pairing(ByteArray(16) { 5 }, ByteArray(32) { 5 })
        assertThat(mayUpdate("H1", pairing, repairOf = null, saved = saved)).isTrue()
        assertThat(mayUpdate("H1", fresh, repairOf = "H1", saved = saved)).isTrue()
        assertThat(mayUpdate("H1", fresh, repairOf = null, saved = saved)).isFalse()
        assertThat(mayUpdate("H2", pairing, repairOf = null, saved = saved)).isFalse()
        assertThat(mayUpdate("H2", fresh, repairOf = "H1", saved = saved)).isFalse()
        assertThat(mayUpdate("H9", fresh, repairOf = null, saved = saved)).isTrue()
        // A host can hand out a saved record's client id at pairing; only the key proves the record.
        val sameIdNewKey = Pairing(pairing.clientId.copyOf(), ByteArray(32) { 6 })
        assertThat(mayUpdate("H1", sameIdNewKey, repairOf = null, saved = saved)).isFalse()
    }

    @Test
    fun aCustomNameOutlivesTheMacsOwnName() {
        val named = mac.copy(customName = "desk")
        val next = named.updated("H1", "renamed on the Mac", pairing, "10.0.2.2", emptyList(), now = 30)
        assertThat(next.customName).isEqualTo("desk")
        assertThat(next.displayName).isEqualTo("desk")
        assertThat(mac.displayName).isEqualTo("mini")
    }

    @Test
    fun aLaunchWithoutAPortExtraKeepsTheDefault() {
        assertThat(launchPort(present = false, value = null)).isNull()
    }

    @Test
    fun anIntPortWithRoomForTheMediaPortIsUsed() {
        assertThat(launchPort(present = true, value = 1)).isEqualTo(1)
        assertThat(launchPort(present = true, value = 47940)).isEqualTo(47940)
        assertThat(launchPort(present = true, value = 65534)).isEqualTo(65534)
    }

    @Test
    fun aPortOutOfRangeOrOfAnotherTypeIsIgnored() {
        assertThat(launchPort(present = true, value = 0)).isNull()
        assertThat(launchPort(present = true, value = 65535)).isNull()
        assertThat(launchPort(present = true, value = "47940")).isNull()
        assertThat(launchPort(present = true, value = 47940L)).isNull()
    }
}
