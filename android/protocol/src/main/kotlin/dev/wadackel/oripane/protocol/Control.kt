package dev.wadackel.oripane.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Length-prefixed frames on the TCP control channel. */
class ControlStream(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    private var cipher: ControlCipher? = null

    fun secure(sessionKey: ByteArray) {
        cipher = ControlCipher(sessionKey, isHost = false)
    }

    fun read(): ByteArray {
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME) throw WireException("control frame of $len bytes")
        val payload = ByteArray(len)
        input.readFully(payload)
        return cipher?.open(payload) ?: payload
    }

    @Synchronized
    fun write(payload: ByteArray) {
        val bytes = cipher?.seal(payload) ?: payload
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    companion object {
        const val MAX_FRAME = 1 shl 20
    }
}

/** Seals and opens control messages with implicit per-direction counters. */
class ControlCipher(sessionKey: ByteArray, isHost: Boolean) {
    private val cipher = SessionCipher(sessionKey)
    private val sendDir = if (isHost) Direction.ControlToClient else Direction.ControlToHost
    private val recvDir = if (isHost) Direction.ControlToHost else Direction.ControlToClient
    private var sent = 0L
    private var received = 0L

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray = cipher.seal(plaintext, sendDir, sent++)

    fun open(sealed: ByteArray): ByteArray = cipher.open(sealed, recvDir, received++)
}
