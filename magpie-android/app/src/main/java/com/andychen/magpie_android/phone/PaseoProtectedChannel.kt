package com.andychen.magpie_android.phone

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.SecureRandom

internal const val MAX_RELAY_FRAME_BYTES = 8192

/** Paseo 0.16.2 protected initiator. Magpie exchanges only text frames under the relay size limit. */
internal class PaseoHandshake(macPublicKey: ByteArray) : Closeable {
    private val noise = HandshakeState("Noise_NK_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)
    val hello: String

    init {
        try {
            requireUsableKey(macPublicKey)
            val prologue = "paseo-relay-e2ee/1".encodeToByteArray()
            noise.setPrologue(prologue, 0, prologue.size)
            noise.remotePublicKey.setPublicKey(macPublicKey, 0)
            noise.start()
            val message = ByteArray(50)
            // Parameter v1, binary ciphertext flag off. Magpie app records are JSON.
            check(noise.writeMessage(message, 0, byteArrayOf(1, 0), 0, 2) == message.size)
            val legacyEnvelopeKey = X25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded
            hello = buildJsonObject {
                put("type", "e2ee_hello")
                put("key", standardBase64(legacyEnvelopeKey))
                put("protected", buildJsonObject {
                    put("v", 1)
                    put("message", standardBase64(message))
                })
            }.toString()
        } catch (error: Exception) {
            noise.destroy()
            throw error
        }
    }

    fun accept(raw: String): PaseoProtectedChannel {
        val ready = parseObject(raw)
        require(ready.string("type") == "e2ee_ready")
        val envelope = ready.obj("protected")
        require(envelope.number("v") == 1)
        val message = decodeStandardBase64(envelope.string("message"))
        require(message.size == 50)
        requireUsableKey(message.copyOfRange(0, 32))
        val params = ByteArray(2)
        check(noise.readMessage(message, 0, message.size, params, 0) == params.size)
        require(params.contentEquals(byteArrayOf(1, 0)))
        return PaseoProtectedChannel(noise.split())
    }

    override fun close() = noise.destroy()

    private fun requireUsableKey(publicKey: ByteArray) {
        // Noise-Java permits zero DH output. Match Paseo's rejection of low-order points.
        // Any clamped scalar suffices for this check, which uses no session key.
        val output = ByteArray(32)
        val valid = X25519.calculateAgreement(ByteArray(32), 0, publicKey, 0, output, 0)
        output.fill(0)
        require(valid)
    }
}

internal class PaseoProtectedChannel(private val ciphers: CipherStatePair) : Closeable {
    fun encode(text: String): String {
        val data = text.encodeToByteArray()
        require(data.size <= MAX_TEXT_BYTES)
        val plaintext = ByteBuffer.allocate(HEADER_BYTES + data.size)
            .put(TEXT).put(FIRST_AND_FINAL).putInt(data.size).put(data).array()
        val ciphertextSize = plaintext.size + TAG_BYTES
        val record = ByteArray(LENGTH_BYTES + ciphertextSize)
        ByteBuffer.wrap(record).putShort(ciphertextSize.toShort())
        check(ciphers.sender.encryptWithAd(null, plaintext, 0, record, LENGTH_BYTES, plaintext.size) == ciphertextSize)
        return standardBase64(record)
    }

    fun decode(text: String): String {
        require(text.length <= MAX_RELAY_FRAME_BYTES)
        val record = decodeStandardBase64(text)
        require(record.size >= LENGTH_BYTES + HEADER_BYTES + TAG_BYTES)
        val ciphertextSize = ByteBuffer.wrap(record).short.toInt() and 0xffff
        require(ciphertextSize == record.size - LENGTH_BYTES)
        val plaintext = ByteArray(ciphertextSize - TAG_BYTES)
        check(ciphers.receiver.decryptWithAd(null, record, LENGTH_BYTES, plaintext, 0, ciphertextSize) == plaintext.size)
        val header = ByteBuffer.wrap(plaintext)
        require(header.get() == TEXT && header.get() == FIRST_AND_FINAL && header.int == plaintext.size - HEADER_BYTES)
        return plaintext.decodeToString(HEADER_BYTES, plaintext.size, throwOnInvalidSequence = true)
    }

    override fun close() = ciphers.destroy()

    private companion object {
        const val TEXT: Byte = 0
        const val FIRST_AND_FINAL: Byte = 3
        const val HEADER_BYTES = 6
        const val TAG_BYTES = 16
        const val LENGTH_BYTES = 2
        const val MAX_TEXT_BYTES = MAX_RELAY_FRAME_BYTES * 3 / 4 - HEADER_BYTES - TAG_BYTES - LENGTH_BYTES
    }
}
