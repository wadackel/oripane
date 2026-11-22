package dev.wadackel.oripane.protocol

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class Direction(val code: Int) {
    ControlToClient(1),
    ControlToHost(2),
    MediaToClient(3),
    MediaToHost(4),
}

/**
 * AES-GCM with this session's key. Each thread keeps its own `Cipher` and nonce buffer and only
 * re-initializes them per packet: a new `Cipher` for each of hundreds of packets a second was a
 * measurable share of the receive path. Nonces never repeat across threads, since each direction's
 * counter is taken once per packet.
 */
class SessionCipher(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private val local = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") to ByteArray(12) }

    fun seal(plaintext: ByteArray, dir: Direction, counter: Long, aad: ByteArray = EMPTY): ByteArray {
        val c = cipher(Cipher.ENCRYPT_MODE, dir, counter)
        if (aad.isNotEmpty()) c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    fun open(sealed: ByteArray, dir: Direction, counter: Long, aad: ByteArray = EMPTY): ByteArray =
        open(sealed, 0, sealed.size, dir, counter, aad, 0, aad.size)

    /** Opens `buf[offset, offset + length)` with `aad[aadOffset, aadOffset + aadLength)` as AAD, copying neither. */
    fun open(buf: ByteArray, offset: Int, length: Int, dir: Direction, counter: Long, aad: ByteArray, aadOffset: Int, aadLength: Int): ByteArray {
        if (length < 16) throw WireException("truncated")
        val c = cipher(Cipher.DECRYPT_MODE, dir, counter)
        if (aadLength > 0) c.updateAAD(aad, aadOffset, aadLength)
        return try {
            c.doFinal(buf, offset, length)
        } catch (e: AEADBadTagException) {
            throw WireException("authentication")
        }
    }

    private fun cipher(mode: Int, dir: Direction, counter: Long): Cipher {
        val (c, nonce) = local.get()
        writeNonce(nonce, dir, counter)
        c.init(mode, key, GCMParameterSpec(128, nonce))
        return c
    }

    companion object {
        private val EMPTY = ByteArray(0)

        private fun writeNonce(out: ByteArray, dir: Direction, counter: Long) {
            out[0] = dir.code.toByte()
            out[1] = 0
            out[2] = 0
            out[3] = 0
            for (i in 0 until 8) out[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        }
    }
}

/** An X25519 key pair as raw 32-byte values, the form the protocol carries. */
class X25519KeyPair private constructor(private val private: PrivateKey, val publicRaw: ByteArray) {
    fun sharedSecret(peerRaw: ByteArray): ByteArray {
        if (peerRaw.size != 32) throw WireException("bad public key")
        val peer = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + peerRaw))
        val ka = KeyAgreement.getInstance("XDH")
        ka.init(private)
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }

    companion object {
        // DER headers for an X25519 key (RFC 8410); the raw key is the trailing 32 bytes.
        private val SPKI_PREFIX = "302a300506032b656e032100".unhex()
        private val PKCS8_PREFIX = "302e020100300506032b656e04220420".unhex()

        fun generate(): X25519KeyPair {
            // Android's Conscrypt rejects NamedParameterSpec here; its XDH generator only makes
            // X25519 keys, which is also the JDK's default, so no parameters are passed.
            val kp = KeyPairGenerator.getInstance("XDH").generateKeyPair()
            return X25519KeyPair(kp.private, kp.public.encoded.takeLast(32).toByteArray())
        }

        /** Deterministic keys for tests. */
        fun fromPrivate(raw: ByteArray, publicRaw: ByteArray): X25519KeyPair {
            val priv = KeyFactory.getInstance("XDH").generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + raw))
            return X25519KeyPair(priv, publicRaw)
        }
    }
}

object Handshake {
    private val random = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun commitment(clientPub: ByteArray) = sha256(clientPub)

    fun sas(hostPub: ByteArray, clientPub: ByteArray): String {
        val d = sha256("oripane-sas".toByteArray(), hostPub, clientPub)
        val v = ByteReader(d).u32()
        return "%06d".format(v % 1_000_000)
    }

    fun pairKey(shared: ByteArray, hostPub: ByteArray, clientPub: ByteArray) =
        hkdf(shared, hostPub + clientPub, "oripane-pair")

    fun proof(pairKey: ByteArray, role: String, clientNonce: ByteArray, hostNonce: ByteArray): ByteArray =
        hmac(pairKey, role.toByteArray() + clientNonce + hostNonce)

    fun verify(proof: ByteArray, pairKey: ByteArray, role: String, clientNonce: ByteArray, hostNonce: ByteArray) =
        MessageDigest.isEqual(proof, proof(pairKey, role, clientNonce, hostNonce))

    fun sessionKey(pairKey: ByteArray, clientNonce: ByteArray, hostNonce: ByteArray) =
        hkdf(pairKey, clientNonce + hostNonce, "oripane-session")

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    /** RFC 5869 with SHA-256, 32 bytes of output (one expand block). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info.toByteArray() + byteArrayOf(1))
    }
}
