package dev.wadackel.oripane.protocol

enum class MediaType(val code: Int) {
    VideoFragment(1),
    VideoParity(2),
    Input(3),
    Feedback(4),
    Hello(5),
    HelloAck(6),
    ;

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: throw WireException("bad type $code")
    }
}

data class MediaHeader(val type: MediaType, val sessionId: Long, val seq: Long) {
    fun encode(): ByteArray = ByteWriter(SIZE).u8(VERSION).u8(type.code).u32(sessionId).u64(seq).toByteArray()

    companion object {
        const val SIZE = 14
        const val VERSION = 1

        fun decode(packet: ByteArray, length: Int = packet.size): MediaHeader {
            val r = ByteReader(packet, 0, length)
            val v = r.u8()
            if (v != VERSION) throw WireException("bad version $v")
            return MediaHeader(MediaType.of(r.u8()), r.u32(), r.u64())
        }
    }
}

object MediaCodecs {
    fun seal(header: MediaHeader, plaintext: ByteArray, cipher: SessionCipher, dir: Direction): ByteArray {
        val aad = header.encode()
        return aad + cipher.seal(plaintext, dir, header.seq, aad)
    }

    /** The header is the AAD; both are read in place from the receive buffer. */
    fun open(packet: ByteArray, length: Int, cipher: SessionCipher, dir: Direction): Pair<MediaHeader, ByteArray> {
        val header = MediaHeader.decode(packet, length)
        return header to cipher.open(packet, MediaHeader.SIZE, length - MediaHeader.SIZE, dir, header.seq, packet, 0, MediaHeader.SIZE)
    }
}

data class FrameInfo(
    val frameId: Long,
    val fragCount: Int,
    val keyframe: Boolean,
    val captureUs: Long,
    val hostUs: Long,
    val frameBytes: Long,
)

sealed interface FramePacket {
    val info: FrameInfo
}

class VideoFragment(override val info: FrameInfo, val fragIndex: Int, val payload: ByteArray) : FramePacket {
    fun encode(): ByteArray = ByteWriter(25 + payload.size)
        .u32(info.frameId).u16(fragIndex).u16(info.fragCount).u8(if (info.keyframe) 1 else 0)
        .u64(info.captureUs).u32(info.hostUs).u32(info.frameBytes).bytes(payload).toByteArray()

    companion object {
        fun decode(d: ByteArray): VideoFragment {
            val r = ByteReader(d)
            val frameId = r.u32()
            val fragIndex = r.u16()
            val fragCount = r.u16()
            val flags = r.u8()
            val info = FrameInfo(frameId, fragCount, flags and 1 != 0, r.u64(), r.u32(), r.u32())
            return VideoFragment(info, fragIndex, r.rest())
        }
    }
}

class VideoParity(
    override val info: FrameInfo,
    val groupStart: Int,
    val groupCount: Int,
    val lenXor: Int,
    val parity: ByteArray,
) : FramePacket {
    fun encode(): ByteArray = ByteWriter(28 + parity.size)
        .u32(info.frameId).u16(groupStart).u8(groupCount).u16(info.fragCount).u8(if (info.keyframe) 1 else 0)
        .u64(info.captureUs).u32(info.hostUs).u32(info.frameBytes).u16(lenXor).bytes(parity).toByteArray()

    companion object {
        fun decode(d: ByteArray): VideoParity {
            val r = ByteReader(d)
            val frameId = r.u32()
            val groupStart = r.u16()
            val groupCount = r.u8()
            val fragCount = r.u16()
            val flags = r.u8()
            val info = FrameInfo(frameId, fragCount, flags and 1 != 0, r.u64(), r.u32(), r.u32())
            val lenXor = r.u16()
            return VideoParity(info, groupStart, groupCount, lenXor, r.rest())
        }
    }
}

enum class PointerAction(val code: Int) { Move(0), Down(1), Drag(2), Up(3), DownContinued(4) }

sealed interface InputEvent {
    data class Pointer(val action: PointerAction, val x: Float, val y: Float) : InputEvent
    data class Scroll(val dx: Float, val dy: Float) : InputEvent
    data class RightClick(val x: Float, val y: Float) : InputEvent
}

data class SequencedInput(val seq: Long, val event: InputEvent)

object InputPacket {
    fun encode(events: List<SequencedInput>): ByteArray {
        val w = ByteWriter().u8(events.size)
        for (e in events) {
            w.u32(e.seq)
            when (val ev = e.event) {
                is InputEvent.Pointer -> w.u8(1).u8(ev.action.code).f32(ev.x).f32(ev.y)
                is InputEvent.Scroll -> w.u8(2).f32(ev.dx).f32(ev.dy)
                is InputEvent.RightClick -> w.u8(3).f32(ev.x).f32(ev.y)
            }
        }
        return w.toByteArray()
    }
}

data class Feedback(
    val lastFrameId: Long,
    val packetsReceived: Long,
    val packetsLost: Long,
    val framesRecovered: Long,
    val framesDropped: Long,
    val decodeUs: Long,
    val totalUs: Long,
) {
    fun encode(): ByteArray = ByteWriter(28).apply {
        listOf(lastFrameId, packetsReceived, packetsLost, framesRecovered, framesDropped, decodeUs, totalUs).forEach { u32(it and 0xFFFFFFFFL) }
    }.toByteArray()
}
