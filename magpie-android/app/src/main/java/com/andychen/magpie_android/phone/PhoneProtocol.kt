package com.andychen.magpie_android.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal class PhoneOffer private constructor(
    val json: JsonObject,
    val serverId: String,
    val macPublicKey: ByteArray,
    val relayUrl: HttpUrl,
) {
    companion object {
        fun parse(json: JsonObject): PhoneOffer {
            require(json.number("v") == 2 && json.string("minimumSecurityMode") == "protected")
            val serverId = json.string("serverId")
            require(serverId.length in 1..128 && serverId.none { it.isISOControl() })
            val macKey = decodeStandardBase64(json.string("daemonPublicKeyB64"))
            require(macKey.size == 32)

            val relay = json.obj("relay")
            val endpoint = URI("https://${relay.string("endpoint")}")
            val host = requireNotNull(endpoint.host)
            val port = endpoint.port
            require(port in 1..65535 && endpoint.rawUserInfo == null && endpoint.rawPath.isEmpty() &&
                endpoint.rawQuery == null && endpoint.rawFragment == null)
            val useTls = relay["useTls"]?.let {
                require(it is JsonPrimitive && !it.isString)
                it.boolean
            } ?: false
            require(useTls || host in setOf("127.0.0.1", "[::1]"))

            // OkHttp takes the HTTP(S) upgrade URL for both ws and wss.
            val url = HttpUrl.Builder()
                .scheme(if (useTls) "https" else "http")
                .host(host)
                .port(port)
                .addPathSegment("ws")
                .addQueryParameter("serverId", serverId)
                .addQueryParameter("role", "client")
                .addQueryParameter("v", "2")
                .build()
            val normalized = buildJsonObject {
                put("v", 2)
                put("serverId", serverId)
                put("daemonPublicKeyB64", standardBase64(macKey))
                put("minimumSecurityMode", "protected")
                put("relay", buildJsonObject {
                    put("endpoint", relay.string("endpoint"))
                    put("useTls", useTls)
                })
            }
            return PhoneOffer(normalized, serverId, macKey, url)
        }
    }
}

internal class PhonePairing private constructor(val offer: PhoneOffer, val ticket: String) {
    companion object {
        fun parse(raw: String): PhonePairing {
            require(raw.length <= 4096)
            val uri = URI(raw.trim())
            require(uri.scheme == "magpie-mobile" && uri.rawAuthority == "pair" &&
                uri.rawPath.isEmpty() && uri.rawQuery == null)
            val fields = requireNotNull(uri.rawFragment).split('&').map { it.split('=', limit = 2) }
            require(fields.size == 2 && fields.all { it.size == 2 })
            val values = fields.associate { it[0] to it[1] }
            val ticket = values.getValue("ticket")
            decodeBase64Url(ticket, 32)
            val json = decodeBase64Url(values.getValue("offer")).decodeToString(throwOnInvalidSequence = true)
            return PhonePairing(PhoneOffer.parse(parseObject(json)), ticket)
        }
    }
}

internal class PhoneIdentity private constructor(private val key: Ed25519PrivateKeyParameters) {
    private val publicBytes = key.generatePublicKey().encoded
    val publicKey = base64Url(publicBytes)
    val deviceId = base64Url(MessageDigest.getInstance("SHA-256").digest(publicBytes))

    fun encodedSeed(): ByteArray = key.encoded

    fun proof(serverId: String, nonce: String, operation: String, ticket: String = ""): String {
        val bytes = listOf("magpie-connect-phone/v1", serverId, nonce, operation, publicKey, ticket)
            .joinToString("\n").encodeToByteArray()
        return base64Url(Ed25519Signer().apply {
            init(true, key)
            update(bytes, 0, bytes.size)
        }.generateSignature())
    }

    companion object {
        fun generate() = PhoneIdentity(Ed25519PrivateKeyParameters(SecureRandom()))

        fun fromSeed(seed: ByteArray): PhoneIdentity {
            require(seed.size == 32)
            return PhoneIdentity(Ed25519PrivateKeyParameters(seed, 0))
        }
    }
}

internal data class PhoneProfile(
    val offer: PhoneOffer,
    val identity: PhoneIdentity,
    val requiresPairing: Boolean = false,
) {
    // Version 1 stored only the offer here. This metadata is authenticated by the profile store.
    fun storedMetadata(): JsonObject = if (requiresPairing) {
        JsonObject(offer.json + ("requiresPairing" to JsonPrimitive(true)))
    } else offer.json

    companion object {
        fun fromStoredMetadata(metadata: JsonObject, identity: PhoneIdentity): PhoneProfile {
            val requiresPairing = metadata["requiresPairing"]?.let {
                require(it is JsonPrimitive && !it.isString)
                it.boolean
            } ?: false
            return PhoneProfile(PhoneOffer.parse(metadata), identity, requiresPairing)
        }
    }
}

internal class HostReading(val displayName: String, val requestId: String, val receipt: String) {
    companion object {
        fun fromReply(reply: JsonObject, requestId: String, serverId: String): HostReading {
            require(reply.string("id") == requestId && reply.string("hostId") == serverId)
            val receipt = reply.string("receipt")
            val uuid = UUID.fromString(receipt)
            require(uuid.toString() == receipt && uuid.version() == 4 && uuid.variant() == 2)
            return HostReading(reply.string("displayName").also { require(it.isNotBlank()) }, requestId, receipt)
        }
    }
}

internal fun phoneRequest(type: String, id: String, fields: JsonObject): JsonObject = buildJsonObject {
    fields.forEach { (name, value) -> put(name, value) }
    put("v", 1)
    put("type", type)
    put("id", id)
}

internal fun parseObject(raw: String) = Json.parseToJsonElement(raw) as JsonObject

internal fun JsonObject.obj(name: String) = getValue(name) as JsonObject

internal fun JsonObject.string(name: String): String = (getValue(name) as JsonPrimitive).let {
    require(it.isString)
    it.content
}

internal fun JsonObject.number(name: String): Int = (getValue(name) as JsonPrimitive).let {
    require(!it.isString)
    it.int
}

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun decodeBase64Url(value: String, size: Int? = null): ByteArray =
    Base64.getUrlDecoder().decode(value).also {
        require((size == null || it.size == size) && value == base64Url(it))
    }

internal fun standardBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

internal fun decodeStandardBase64(value: String): ByteArray = Base64.getDecoder().decode(value).also {
    require(value == standardBase64(it))
}
