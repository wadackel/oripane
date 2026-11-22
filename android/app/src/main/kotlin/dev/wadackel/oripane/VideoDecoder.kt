package dev.wadackel.oripane

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.wadackel.oripane.protocol.Reassembler
import java.util.ArrayDeque

/**
 * H.264 or HEVC decode straight to a Surface. Each output buffer is rendered the moment it is ready —
 * no presentation-time scheduling — and frames are never dropped between keyframes, because a
 * skipped P-frame corrupts every frame after it. When decoding falls behind, the backlog is
 * thrown away whole and a keyframe is requested instead.
 */
class VideoDecoder(private val onNeedKeyframe: () -> Unit, private val onRendered: (captureUs: Long, decodeUs: Long) -> Unit) {
    private val thread = HandlerThread("oripane-decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Object()
    private var codec: MediaCodec? = null
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Reassembler.Frame>()
    private val queuedAt = HashMap<Long, Long>()
    private var needKeyframe = true
    /** When the wait for a keyframe began or was last answered by a request; [STALL_US] later, it is asked for again. */
    private var needKeyframeSince = 0L
    private var lastKeyRequest = 0L
    /** Bumped by every configure and release: a codec built for an older one is thrown away. */
    private var generation = 0
    /** A codec is being built, or a stream waits for its surface; frames wait in `pending` meanwhile. */
    private var building = false
    /** What the running or building codec decodes to. */
    private var target: DecodeTarget<Surface>? = null
    var decoderName = ""
        private set
    var lowLatency = false
        private set

    /**
     * Decodes to [surface] at [width] by [height]. The codec is built on the decoder thread, since
     * building one took 70–150 ms that the UI or control thread would otherwise block on, and not
     * at all when the same surface, size and [videoCodec] are already set: a new stream starts with
     * a keyframe.
     * [requestKeyframe] is for a surface attached to a stream already going; a new stream brings
     * its own keyframe, and frames arriving while the codec is built wait for it.
     */
    fun configure(surface: Surface, width: Int, height: Int, videoCodec: String, requestKeyframe: Boolean) {
        val next = DecodeTarget(surface, width, height, videoCodec)
        val (gen, old) = synchronized(lock) {
            if (keepsDecoder(target, next, active = codec != null || building)) {
                markNeedKeyframeLocked(restart = true)
                null to null
            } else {
                // Frames kept while the surface was awaited belong to this stream; any others are
                // the previous stream's, at the previous size.
                if (target != null) pending.clear()
                if (pending.firstOrNull()?.info?.keyframe != true) markNeedKeyframeLocked(restart = true) else needKeyframe = false
                val old = codec
                codec = null
                freeInputs.clear()
                queuedAt.clear()
                building = true
                target = next
                ++generation to old
            }
        }
        if (gen == null) {
            if (requestKeyframe) requestKeyframe()
            return
        }
        handler.post {
            old?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            build(next, gen, requestKeyframe)
        }
    }

    /**
     * A stream started before the surface exists: its first keyframe waits for the surface
     * instead of being dropped and asked for again once the surface comes.
     */
    fun awaitSurface() {
        synchronized(lock) {
            if (codec != null) return
            building = true
            markNeedKeyframeLocked(restart = true)
            target = null
            // Kept from an earlier stream, they are the wrong size for the codec this one gets.
            pending.clear()
        }
    }

    private fun build(t: DecodeTarget<Surface>, gen: Int, requestKeyframe: Boolean) {
        if (synchronized(lock) { gen != generation }) return
        val (surface, width, height, videoCodec) = t
        val mime = mimeOf(videoCodec)
        val format = MediaFormat.createVideoFormat(mime, width, height)
        var c: MediaCodec? = null
        try {
            val name = findDecoder(format)?.name
            if (name == null) {
                log("decoder none_for codec=$videoCodec size=${width}x$height")
                // No target either: nothing would decode a keyframe, so none is asked for meanwhile.
                synchronized(lock) { if (gen == generation) { building = false; target = null } }
                return
            }
            val made = MediaCodec.createByCodecName(name)
            c = made
            val caps = made.codecInfo.getCapabilitiesForType(mime)
            // Some decoders throw at configure() when asked for a feature they lack.
            lowLatency = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Qualcomm decoders do not advertise FEATURE_LowLatency and hold frames for reordering
            // the stream never does (about 18ms extra on a Fold); decode-order output skips that.
            // A codec ignores vendor keys it does not know.
            format.setInteger(QTI_DECODE_ORDER, 1)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            made.setCallback(callback, handler)
            made.configure(format, surface, null, 0)
            val vendor = made.supportedVendorParameters.filter { it == QTI_DECODE_ORDER }
            if (vendor.isNotEmpty()) lowLatency = true
            // Input buffers are offered from the moment start() runs; the callback drops buffers from
            // any codec but the current one, so the codec must be current first. A release while this
            // was built means its surface is gone.
            val keyframeQueued = synchronized(lock) {
                if (gen != generation) return@synchronized null
                codec = made
                building = false
                pending.firstOrNull()?.info?.keyframe == true
            }
            if (keyframeQueued == null) {
                runCatching { made.release() }
                return
            }
            made.start()
            decoderName = name
            log("decoder configured name=$name codec=$videoCodec size=${width}x$height low_latency=$lowLatency vendor=${vendor.joinToString(",")} hw=${made.codecInfo.isHardwareAccelerated} keyframe_queued=$keyframeQueued")
            if (requestKeyframe && !keyframeQueued) requestKeyframe()
        } catch (e: Exception) {
            // Built off the caller, a surface destroyed meanwhile fails here; nothing may keep the codec.
            log("decoder configure_failed error=${e.javaClass.simpleName}")
            synchronized(lock) {
                if (c != null && codec === c) codec = null
                if (gen == generation) {
                    building = false
                    target = null
                }
            }
            c?.let { runCatching { it.release() } }
        }
    }

    /** Synchronous: a destroyed surface must have no codec left drawing to it. */
    fun release() {
        val c = synchronized(lock) {
            val c = codec
            codec = null
            building = false
            target = null
            generation++
            freeInputs.clear()
            pending.clear()
            queuedAt.clear()
            c
        }
        c?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    fun shutdown() {
        release()
        thread.quitSafely()
    }

    /** Called when the reassembler lost a frame: whatever follows depends on it. */
    fun markBroken() {
        synchronized(lock) { markNeedKeyframeLocked() }
        requestKeyframe()
    }

    /** [restart]: a new codec or stream starts the wait over, whatever was missing before. */
    private fun markNeedKeyframeLocked(restart: Boolean = false) {
        if (!needKeyframe || restart) needKeyframeSince = nowMicros()
        needKeyframe = true
    }

    /**
     * Asks again for a keyframe still missing [STALL_US] after it was needed. A stream's first
     * keyframe can arrive before its `stream` message and be dropped, and on a still screen no
     * later frame would show that it is missing.
     */
    fun requestIfStillWaiting() {
        val ask = synchronized(lock) {
            val now = nowMicros()
            (needKeyframe && target != null && now - needKeyframeSince > STALL_US).also { if (it) needKeyframeSince = now }
        }
        if (ask) requestKeyframe()
    }

    fun submit(frame: Reassembler.Frame) {
        synchronized(lock) {
            if (codec == null && !building) return
            if (needKeyframe && !frame.info.keyframe) {
                // Without a surface yet, the request is made once it comes.
                if (target != null) requestKeyframe()
                return
            }
            if (frame.info.keyframe) {
                needKeyframe = false
                pending.clear()
            }
            pending.addLast(frame)
            if (pending.size > if (building) BUILD_BACKLOG else MAX_BACKLOG) {
                log("decoder backlog_reset frames=${pending.size}")
                pending.clear()
                markNeedKeyframeLocked()
                if (target != null) requestKeyframe()
                return
            }
            feedLocked()
        }
    }

    private fun feedLocked() {
        val c = codec ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val frame = pending.removeFirst()
            val buf = c.getInputBuffer(index) ?: continue
            if (buf.capacity() < frame.data.size) {
                log("decoder input_too_small capacity=${buf.capacity()} frame=${frame.data.size}")
                c.queueInputBuffer(index, 0, 0, 0, 0)
                markNeedKeyframeLocked()
                continue
            }
            buf.clear()
            buf.put(frame.data)
            queuedAt[frame.info.captureUs] = nowMicros()
            val flags = if (frame.info.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(index, 0, frame.data.size, frame.info.captureUs, flags)
        }
    }

    private fun requestKeyframe() {
        val now = nowMicros()
        if (now - lastKeyRequest < 150_000) return
        lastKeyRequest = now
        onNeedKeyframe()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            synchronized(lock) {
                if (c !== codec) return
                freeInputs.addLast(index)
                feedLocked()
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val queued = synchronized(lock) {
                if (c !== codec) return
                queuedAt.remove(info.presentationTimeUs)
            }
            runCatching { c.releaseOutputBuffer(index, info.size > 0) }
            if (info.size > 0) {
                val decodeUs = queued?.let { nowMicros() - it } ?: 0
                onRendered(info.presentationTimeUs, decodeUs)
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            log("decoder error message=${e.diagnosticInfo} recoverable=${e.isRecoverable}")
            markBroken()
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            log("decoder output_format=$format")
        }
    }

    companion object {
        private const val MAX_BACKLOG = 8
        private const val STALL_US = 300_000L
        /** Frames that may wait while a codec is built: about half a second at 60 fps. */
        private const val BUILD_BACKLOG = 32
        private const val QTI_DECODE_ORDER = "vendor.qti-ext-dec-picture-order.enable"

        /**
         * Whether a hardware decoder takes [videoCodec] at [width] by [height]. It asks what [build]
         * asks, so the phone never offers a codec it then finds no decoder for. A software HEVC
         * decoder does not count: at panel size its latency costs more than H.264's extra bytes.
         */
        fun decodesInHardware(videoCodec: String, width: Int, height: Int) =
            findDecoder(MediaFormat.createVideoFormat(mimeOf(videoCodec), width, height))?.isHardwareAccelerated == true

        private fun findDecoder(format: MediaFormat): MediaCodecInfo? {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val name = list.findDecoderForFormat(format) ?: return null
            return list.codecInfos.firstOrNull { it.name == name }
        }
    }
}

private fun mimeOf(codec: String) = if (codec == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

/** What `display` offers in `codecs`, most preferred first; the host falls back to H.264 without it. */
fun offeredCodecs(hardwareHevc: Boolean) = if (hardwareHevc) listOf("hevc", "h264") else listOf("h264")

/**
 * A codec's surface, size and codec (`"h264"` or `"hevc"`). The surface compares by identity; a
 * SurfaceView reuses one object across destroy and create, and what keeps a destroyed one from
 * matching is `release()` clearing the target.
 */
data class DecodeTarget<S>(val surface: S, val width: Int, val height: Int, val codec: String = "h264")

/** A running (or building) codec for the same surface, size and codec takes a new stream as it is. */
fun <S> keepsDecoder(current: DecodeTarget<S>?, next: DecodeTarget<S>, active: Boolean) = active && current == next
