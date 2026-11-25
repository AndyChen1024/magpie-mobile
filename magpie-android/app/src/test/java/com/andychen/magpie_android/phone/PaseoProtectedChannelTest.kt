package com.andychen.magpie_android.phone

import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class PaseoProtectedChannelTest {
    @Test
    fun refusesLegacyReadyAndLowOrderMacKey() {
        assertThrows(Exception::class.java) { PaseoHandshake(ByteArray(32)) }
        val (mac, key) = responder()
        try {
            PaseoHandshake(key).use { handshake ->
                assertThrows(Exception::class.java) { handshake.accept("""{"type":"e2ee_ready"}""") }
            }
        } finally {
            mac.destroy()
        }
    }

    @Test
    fun handshakeRequiresThePinnedMacKey() {
        val (mac, _) = responder()
        val (otherMac, otherKey) = responder()
        try {
            PaseoHandshake(otherKey).use { handshake ->
                assertThrows(Exception::class.java) { ready(mac, handshake) }
            }
        } finally {
            mac.destroy()
            otherMac.destroy()
        }
    }

    @Test
    fun authenticatedRecordsAdvanceCountersAndRejectReplay() {
        val (mac, key) = responder()
        try {
            PaseoHandshake(key).use { handshake ->
                handshake.accept(ready(mac, handshake)).use { client ->
                    PaseoProtectedChannel(mac.split()).use { server ->
                        val request = """{"type":"host.get"}"""
                        val reply = """{"displayName":"示例 Mac"}"""
                        assertEquals(request, server.decode(client.encode(request)))
                        assertEquals(request, server.decode(client.encode(request)))
                        val record = server.encode(reply)
                        assertEquals(reply, client.decode(record))
                        assertThrows(Exception::class.java) { client.decode(record) }
                    }
                }
            }
        } finally {
            mac.destroy()
        }
    }

    private fun responder(): Pair<HandshakeState, ByteArray> {
        val noise = HandshakeState("Noise_NK_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER)
        val prologue = "paseo-relay-e2ee/1".encodeToByteArray()
        noise.setPrologue(prologue, 0, prologue.size)
        noise.localKeyPair.generateKeyPair()
        val key = ByteArray(32).also { noise.localKeyPair.getPublicKey(it, 0) }
        noise.start()
        return noise to key
    }

    private fun ready(mac: HandshakeState, client: PaseoHandshake): String {
        val hello = Base64.getDecoder().decode(parseObject(client.hello).obj("protected").string("message"))
        val payload = ByteArray(2)
        assertEquals(2, mac.readMessage(hello, 0, hello.size, payload, 0))
        val response = ByteArray(50)
        assertEquals(50, mac.writeMessage(response, 0, payload, 0, payload.size))
        return buildJsonObject {
            put("type", "e2ee_ready")
            put("protected", buildJsonObject {
                put("v", 1)
                put("message", standardBase64(response))
            })
        }.toString()
    }
}
