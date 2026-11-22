package dev.wadackel.oripane.protocol

/**
 * Packets received and lost by media seq, for the feedback the host's bitrate control reads.
 * A seq that arrives late, within [WINDOW] of the newest, takes back the loss its gap counted:
 * Wi-Fi and WireGuard reorder packets, and a reorder taken for a loss lowers the bitrate for
 * nothing. Not thread-safe.
 */
class LossCounter {
    private var newest = -1L
    /** Bit i: newest − i has arrived. */
    private var seen = 0L
    private var received = 0L
    private var lost = 0L
    /** The newest seq when the last window was taken: a gap at or below it was reported in an earlier window. */
    private var reportedThrough = -1L

    fun onPacket(seq: Long) {
        if (newest < 0) {
            newest = seq
            seen = 1L
            received++
            return
        }
        if (seq > newest) {
            val gap = seq - newest
            lost += gap - 1
            seen = if (gap >= WINDOW) 1L else (seen shl gap.toInt()) or 1L
            newest = seq
            received++
            return
        }
        val back = newest - seq
        if (back >= WINDOW) return
        val bit = 1L shl back.toInt()
        if (seen and bit != 0L) return
        seen = seen or bit
        received++
        if (seq > reportedThrough) lost--
    }

    /** Received and lost since the last call; a late packet whose loss an earlier call reported counts as received only. */
    fun take(): Pair<Long, Long> {
        val r = received to lost
        received = 0
        lost = 0
        reportedThrough = newest
        return r
    }

    companion object {
        const val WINDOW = 64
    }
}
