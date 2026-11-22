package dev.wadackel.oripane

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoDecoderTest {
    private val surface = Any()

    @Test
    fun aStreamAtTheSameSizeForTheSameSurfaceKeepsTheDecoder() {
        assertThat(keepsDecoder(DecodeTarget(surface, 1768, 2208), DecodeTarget(surface, 1768, 2208), active = true)).isTrue()
    }

    @Test
    fun aNewSizeANewSurfaceOrNoDecoderBuildsOne() {
        val t = DecodeTarget(surface, 1768, 2208)
        assertThat(keepsDecoder(t, DecodeTarget(surface, 1400, 2000), active = true)).isFalse()
        assertThat(keepsDecoder(t, DecodeTarget(Any(), 1768, 2208), active = true)).isFalse()
        assertThat(keepsDecoder(t, t, active = false)).isFalse()
        assertThat(keepsDecoder(null, t, active = true)).isFalse()
    }

    @Test
    fun aStreamInAnotherCodecBuildsADecoderForIt() {
        val avc = DecodeTarget(surface, 1768, 2208, "h264")
        assertThat(keepsDecoder(avc, DecodeTarget(surface, 1768, 2208, "hevc"), active = true)).isFalse()
        assertThat(keepsDecoder(DecodeTarget(surface, 1768, 2208, "hevc"), DecodeTarget(surface, 1768, 2208, "hevc"), active = true)).isTrue()
    }

    @Test
    fun hevcIsOfferedOnlyWithAHardwareDecoder() {
        assertThat(offeredCodecs(hardwareHevc = true)).containsExactly("hevc", "h264").inOrder()
        assertThat(offeredCodecs(hardwareHevc = false)).containsExactly("h264")
    }
}
