package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedMacJsonTest {
    private val mac = SavedMac(
        hostId = "aGVsbG8gaG9zdCBpZCE=",
        name = "alice's Mac mini",
        pairing = Pairing(ByteArray(16) { it.toByte() }, ByteArray(32) { (it * 7).toByte() }),
        lanAddrs = listOf("192.168.1.11", "10.0.2.2"),
        tailnet = listOf("100.101.102.103"),
        lastUsed = 1_792_649_179_000,
    )

    @Test
    fun savedMacsSurviveTheJsonRoundTrip() {
        val back = SavedMacs.decode(SavedMacs.encode(listOf(mac, mac.copy(hostId = "other", tailnet = emptyList())))).first()
        assertThat(back.copy(pairing = mac.pairing)).isEqualTo(mac)
        assertThat(back.pairing.clientId).isEqualTo(mac.pairing.clientId)
        assertThat(back.pairing.pairKey).isEqualTo(mac.pairing.pairKey)
    }

    @Test
    fun prefsKeepTheListAndTheDataSaverSetting() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        assertThat(prefs.savedMacs).isEmpty()
        assertThat(prefs.dataSaver).isEqualTo(DataSaver.AUTO)
        prefs.savedMacs = listOf(mac)
        prefs.dataSaver = DataSaver.OFF
        val again = Prefs(RuntimeEnvironment.getApplication())
        assertThat(again.savedMacs.single().hostId).isEqualTo(mac.hostId)
        assertThat(again.dataSaver).isEqualTo(DataSaver.OFF)
    }

    @Test
    fun aCustomNameSurvivesTheJsonRoundTrip() {
        val back = SavedMacs.decode(SavedMacs.encode(listOf(mac.copy(customName = "desk"), mac.copy(hostId = "H2"))))
        assertThat(back.map { it.customName }).containsExactly("desk", null).inOrder()
    }

    @Test
    fun aMacsCardColourSurvivesTheJsonRoundTripAndAnOldRecordHasNone() {
        val tinted = mac.copy(tint = 0xFF6F8FD0.toInt())
        assertThat(SavedMacs.decode(SavedMacs.encode(listOf(tinted))).single().tint).isEqualTo(0xFF6F8FD0.toInt())
        assertThat(SavedMacs.decode(SavedMacs.encode(listOf(tinted.copy(tint = null)))).single().tint).isNull()
    }

    @Test
    fun aNewAuthenticationKeepsTheCardColour() {
        val tinted = mac.copy(tint = 0xFF6F8FD0.toInt())
        assertThat(tinted.updated(tinted.hostId, tinted.name, tinted.pairing, "192.168.1.9", emptyList(), 99).tint).isEqualTo(tinted.tint)
    }
}
