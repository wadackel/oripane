package dev.wadackel.oripane.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LossCounterTest {
    private fun count(vararg seqs: Long) = LossCounter().apply { seqs.forEach(::onPacket) }.take()

    @Test
    fun aReorderedPacketIsNotALoss() {
        assertThat(count(1, 2, 4, 3, 5)).isEqualTo(5L to 0L)
    }

    @Test
    fun aRealGapIsLost() {
        assertThat(count(1, 2, 5)).isEqualTo(3L to 2L)
    }

    @Test
    fun aDuplicateCountsOnce() {
        assertThat(count(1, 2, 2, 3)).isEqualTo(3L to 0L)
    }

    @Test
    fun aPacketLaterThanTheWindowStaysLost() {
        assertThat(count(1, 70, 2)).isEqualTo(2L to 68L)
    }

    @Test
    fun takeStartsAFreshWindowAndNeverGoesNegative() {
        val c = LossCounter()
        c.onPacket(1); c.onPacket(3)
        assertThat(c.take()).isEqualTo(2L to 1L)
        c.onPacket(2)
        assertThat(c.take()).isEqualTo(1L to 0L)
    }

    @Test
    fun aLatePacketReportedLostEarlierDoesNotHideANewLoss() {
        val c = LossCounter()
        c.onPacket(1); c.onPacket(3)
        assertThat(c.take()).isEqualTo(2L to 1L)
        c.onPacket(2); c.onPacket(4); c.onPacket(6)
        assertThat(c.take()).isEqualTo(3L to 1L)
    }
}
