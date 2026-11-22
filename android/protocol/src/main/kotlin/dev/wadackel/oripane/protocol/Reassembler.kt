package dev.wadackel.oripane.protocol

/**
 * Collects the packets of frames and yields each frame once it is whole, repairing one missing
 * fragment per parity group. Frames older than the newest completed one are discarded, and so are
 * frames that stay incomplete too long or pile up: a loss is only seen when a newer frame
 * completes, and on a still screen none comes. Not thread-safe.
 */
class Reassembler(private val clock: () -> Long = { System.nanoTime() / 1000 }) {
    class Frame(val info: FrameInfo, val data: ByteArray, val recovered: Boolean)

    private class Pending(val info: FrameInfo, val firstUs: Long) {
        val fragments = arrayOfNulls<ByteArray>(info.fragCount)
        var have = 0
        val parities = HashMap<Int, VideoParity>()
        var recovered = false
    }

    private val pending = HashMap<Long, Pending>()
    var lastDelivered: Long? = null
        private set
    var framesDropped = 0L
        private set
    var framesRecovered = 0L
        private set
    /**
     * Frames given up on before they completed. Kept apart from [framesDropped], which counts the
     * same frames again as a gap once a newer one completes.
     */
    var framesExpired = 0L
        private set
    val pendingCount get() = pending.size

    fun add(packet: FramePacket): Frame? {
        val info = packet.info
        lastDelivered?.let { if (!isNewer(info.frameId, it)) return null }
        val entry = pending.getOrPut(info.frameId) { Pending(info, clock()) }
        while (pending.size > MAX_PENDING) {
            val oldest = pending.keys.reduce { a, b -> if (isNewer(a, b)) b else a }
            pending.remove(oldest)
            framesExpired++
        }
        if (pending[info.frameId] !== entry) return null
        when (packet) {
            is VideoFragment -> {
                val i = packet.fragIndex
                if (i >= entry.fragments.size || entry.fragments[i] != null) return null
                entry.fragments[i] = packet.payload
                entry.have++
            }
            is VideoParity -> entry.parities[packet.groupStart] = packet
        }
        repair(entry)
        if (entry.have != entry.fragments.size) return null
        return deliver(entry)
    }

    private fun repair(entry: Pending) {
        for ((start, p) in entry.parities) {
            val hi = minOf(entry.fragments.size, start + p.groupCount)
            val missing = (start until hi).filter { entry.fragments[it] == null }
            if (missing.size != 1) continue
            val gap = missing[0]
            val bytes = p.parity.copyOf()
            var len = p.lenXor
            for (i in start until hi) {
                if (i == gap) continue
                val d = entry.fragments[i]!!
                len = len xor d.size
                for (j in d.indices) if (j < bytes.size) bytes[j] = (bytes[j].toInt() xor d[j].toInt()).toByte()
            }
            if (len > bytes.size) continue
            entry.fragments[gap] = bytes.copyOf(len)
            entry.have++
            entry.recovered = true
        }
    }

    private fun deliver(entry: Pending): Frame {
        val total = entry.fragments.sumOf { it!!.size }
        val data = ByteArray(total)
        var off = 0
        for (f in entry.fragments) {
            f!!.copyInto(data, off)
            off += f.size
        }
        val id = entry.info.frameId
        val older = pending.keys.filter { !isNewer(it, id) }
        // Counting the gap in ids, not the incomplete entries, also catches frames of which not a
        // single packet arrived; the decoder must hear about those too.
        val last = lastDelivered
        framesDropped += if (last != null) (id - last - 1) and 0xFFFFFFFFL else (older.size - 1).toLong()
        older.forEach { pending.remove(it) }
        lastDelivered = id
        if (entry.recovered) framesRecovered++
        return Frame(entry.info, data, entry.recovered)
    }

    /** Drops the frames still incomplete [EXPIRE_US] after their first packet; returns how many. */
    fun expire(): Int {
        val now = clock()
        val old = pending.filterValues { now - it.firstUs >= EXPIRE_US }.keys
        old.forEach { pending.remove(it) }
        framesExpired += old.size
        return old.size
    }

    companion object {
        const val MAX_PENDING = 64
        const val EXPIRE_US = 200_000L

        /** Frame ids wrap at 2^32; a difference under half the space counts as newer. */
        fun isNewer(a: Long, b: Long): Boolean {
            val d = (a - b) and 0xFFFFFFFFL
            return d != 0L && d < 0x80000000L
        }
    }
}
