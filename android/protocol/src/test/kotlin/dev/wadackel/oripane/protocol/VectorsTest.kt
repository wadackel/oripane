package dev.wadackel.oripane.protocol

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Checks this implementation byte-for-byte against `spec/vectors.json`, written by the Mac host. */
class VectorsTest {
    private val v = JSONObject(File(System.getProperty("vectors")).readText())
    private fun hex(key: String) = v.getString(key).unhex()

    @Test
    fun keyAgreementAndPairingMatchTheHost() {
        val host = X25519KeyPair.fromPrivate(hex("hostPriv"), hex("hostPub"))
        val client = X25519KeyPair.fromPrivate(hex("clientPriv"), hex("clientPub"))
        assertThat(host.sharedSecret(hex("clientPub")).hex()).isEqualTo(v.getString("shared"))
        assertThat(client.sharedSecret(hex("hostPub")).hex()).isEqualTo(v.getString("shared"))
        assertThat(Handshake.commitment(hex("clientPub")).hex()).isEqualTo(v.getString("commitment"))
        assertThat(Handshake.sas(hex("hostPub"), hex("clientPub"))).isEqualTo(v.getString("sas"))
        assertThat(Handshake.pairKey(hex("shared"), hex("hostPub"), hex("clientPub")).hex()).isEqualTo(v.getString("pairKey"))
    }

    @Test
    fun authenticationMatchesTheHost() {
        val pk = hex("pairKey")
        val cn = hex("clientNonce")
        val hn = hex("hostNonce")
        assertThat(Handshake.proof(pk, "host", cn, hn).hex()).isEqualTo(v.getString("hostProof"))
        assertThat(Handshake.proof(pk, "client", cn, hn).hex()).isEqualTo(v.getString("clientProof"))
        assertThat(Handshake.verify(hex("hostProof"), pk, "host", cn, hn)).isTrue()
        assertThat(Handshake.verify(hex("hostProof"), pk, "client", cn, hn)).isFalse()
        assertThat(Handshake.sessionKey(pk, cn, hn).hex()).isEqualTo(v.getString("sessionKey"))
    }

    @Test
    fun sealedControlAndMediaMatchTheHost() {
        val cipher = SessionCipher(hex("sessionKey"))
        val control = v.getJSONObject("control")
        val sealed = cipher.seal(control.getString("plain").unhex(), Direction.ControlToHost, control.getLong("counter"))
        assertThat(sealed.hex()).isEqualTo(control.getString("sealed"))

        val media = v.getJSONObject("media")
        val packet = media.getString("packet").unhex()
        val (header, plain) = MediaCodecs.open(packet, packet.size, cipher, Direction.MediaToClient)
        assertThat(header.sessionId).isEqualTo(media.getLong("sessionId"))
        assertThat(header.seq).isEqualTo(media.getLong("seq"))
        assertThat(plain.hex()).isEqualTo(media.getString("plain"))

        val tampered = packet.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val error = runCatching { MediaCodecs.open(tampered, tampered.size, cipher, Direction.MediaToClient) }.exceptionOrNull()
        assertThat(error).isInstanceOf(WireException::class.java)
    }

    @Test
    fun aPacketOpensInPlaceFromALargerReceiveBuffer() {
        val cipher = SessionCipher(hex("sessionKey"))
        val packet = v.getJSONObject("media").getString("packet").unhex()
        val buf = ByteArray(65536) { 0x5a }
        packet.copyInto(buf)
        val (_, plain) = MediaCodecs.open(buf, packet.size, cipher, Direction.MediaToClient)
        assertThat(plain.hex()).isEqualTo(v.getJSONObject("media").getString("plain"))
    }

    @Test
    fun oneCipherSealsAndOpensOnSeveralThreadsAtOnce() {
        val cipher = SessionCipher(ByteArray(32) { it.toByte() })
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until 4).map { t ->
            Thread {
                for (i in 0 until 2000) {
                    val counter = t * 1_000_000L + i
                    val plain = ByteArray(1100) { (i + t).toByte() }
                    val header = MediaHeader(MediaType.VideoFragment, 7, counter)
                    val packet = MediaCodecs.seal(header, plain, cipher, Direction.MediaToClient)
                    val (_, opened) = MediaCodecs.open(packet, packet.size, cipher, Direction.MediaToClient)
                    if (!opened.contentEquals(plain)) failures.incrementAndGet()
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertThat(failures.get()).isEqualTo(0)
    }

    @Test
    fun hostPacketsReassembleIntoTheFrameEvenWithLosses() {
        val frame = v.getJSONObject("frame").getString("bytes").unhex()
        val arr = v.getJSONArray("framePackets")
        val packets = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val plain = o.getString("plain").unhex()
            if (o.getString("type") == "fragment") VideoFragment.decode(plain) else VideoParity.decode(plain)
        }
        // Re-encoding must reproduce the host's bytes exactly.
        packets.forEachIndexed { i, p ->
            val bytes = if (p is VideoFragment) p.encode() else (p as VideoParity).encode()
            assertThat(bytes.hex()).isEqualTo(arr.getJSONObject(i).getString("plain"))
        }
        val fragments = packets.filterIsInstance<VideoFragment>()
        for (lost in fragments.indices) {
            val r = Reassembler()
            var out: Reassembler.Frame? = null
            for (p in packets) {
                if (p is VideoFragment && p.fragIndex == lost) continue
                out = r.add(p) ?: out
            }
            assertThat(out?.data?.hex()).isEqualTo(frame.hex())
            assertThat(out?.recovered).isTrue()
        }
    }

    @Test
    fun inputAndFeedbackMatchTheHost() {
        val input = InputPacket.encode(
            listOf(
                SequencedInput(9, InputEvent.Pointer(PointerAction.Down, 0.25f, 0.75f)),
                SequencedInput(10, InputEvent.Scroll(-3.5f, 12f)),
                SequencedInput(11, InputEvent.RightClick(0.5f, 0.5f)),
                SequencedInput(12, InputEvent.Pointer(PointerAction.DownContinued, 0.5f, 0.25f)),
            ),
        )
        assertThat(input.hex()).isEqualTo(v.getString("input"))
        val fb = Feedback(7, 100, 2, 1, 0, 3000, 31000)
        assertThat(fb.encode().hex()).isEqualTo(v.getString("feedback"))
    }
}

class KeyGenerationTest {
    @Test
    fun generatedKeysAgree() {
        val a = X25519KeyPair.generate()
        val b = X25519KeyPair.generate()
        assertThat(a.publicRaw).hasLength(32)
        assertThat(a.sharedSecret(b.publicRaw)).isEqualTo(b.sharedSecret(a.publicRaw))
    }
}

class ReassemblerTest {
    private fun fragment(id: Long, payload: ByteArray) =
        VideoFragment(FrameInfo(id, 1, false, 1, 1, payload.size.toLong()), 0, payload)

    @Test
    fun wholyLostFrameCountsAsDropped() {
        val r = Reassembler()
        r.add(fragment(1, byteArrayOf(1)))
        r.add(fragment(3, byteArrayOf(3)))
        assertThat(r.framesDropped).isEqualTo(1)
    }

    @Test
    fun incompleteFrameBeforeTheFirstDeliveredCountsAsDropped() {
        val r = Reassembler()
        r.add(VideoFragment(FrameInfo(1, 2, false, 1, 1, 2), 0, byteArrayOf(1)))
        r.add(fragment(2, byteArrayOf(2)))
        assertThat(r.framesDropped).isEqualTo(1)
    }

    private fun half(id: Long, keyframe: Boolean = false) =
        VideoFragment(FrameInfo(id, 2, keyframe, 1, 1, 2), 0, byteArrayOf(id.toByte()))

    @Test
    fun pendingFramesStayBoundedWhileNoFrameCompletes() {
        val r = Reassembler()
        for (id in 0L until 20_000L) r.add(half(id))
        assertThat(r.pendingCount).isAtMost(64)
        assertThat(r.framesExpired).isEqualTo(20_000L - 64)
    }

    @Test
    fun aKeyframeLeftIncompleteExpiresAfter200Ms() {
        var now = 0L
        val r = Reassembler(clock = { now })
        r.add(half(1, keyframe = true))
        now = 199_000
        assertThat(r.expire()).isEqualTo(0)
        now = 200_000
        assertThat(r.expire()).isEqualTo(1)
        assertThat(r.framesExpired).isEqualTo(1)
        assertThat(r.pendingCount).isEqualTo(0)
        assertThat(r.framesDropped).isEqualTo(0)
    }

    @Test
    fun anExpiredFrameIsNotCountedAgainAsDroppedButTheGapIs() {
        var now = 0L
        val r = Reassembler(clock = { now })
        r.add(fragment(1, byteArrayOf(1)))
        r.add(half(2))
        now = 300_000
        r.expire()
        r.add(fragment(3, byteArrayOf(3)))
        assertThat(r.framesExpired).isEqualTo(1)
        assertThat(r.framesDropped).isEqualTo(1)
    }

    @Test
    fun staleFrameIsIgnored() {
        val r = Reassembler()
        r.add(fragment(5, byteArrayOf(5)))
        assertThat(r.add(fragment(4, byteArrayOf(4)))).isNull()
        assertThat(r.framesDropped).isEqualTo(0)
    }
}
