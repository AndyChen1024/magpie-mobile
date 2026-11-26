package com.andychen.magpie_android.phone

import androidx.lifecycle.viewModelScope
import com.southernstorm.noise.protocol.HandshakeState
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneConnectionViewModelTest {
    @Test
    fun reconnectNowRestartsTheSavedIdentityEvenDuringAnAutomaticAttempt() = withDisconnectedPhone {
        val retryReading = CompletableDeferred<Unit>()
        oldMac.onRequest = { _, request ->
            if (request.string("type") == "host.get") retryReading.complete(Unit)
        }
        withTimeout(5_000) { retryReading.await() }
        assertTrue(model.state.value.working)
        assertTrue(model.state.value.canScan)
        assertTrue(model.state.value.canRetry)
        respondWithOldName()
        reconnectOld()
        assertEquals(3, oldMac.count("device.auth"))
    }

    @Test
    fun cancelledScanKeepsTheSavedPairingAndResumesAutomaticRetry() = withDisconnectedPhone {
        assertTrue(model.beginScan())
        assertFalse(model.beginScan())
        model.finishScan(null)
        assertFallback(PhoneProblem.RelayUnavailable)
        respondWithOldName()
        until { model.state.value.status == PhoneStatus.Connected }
        assertOldConnection()
    }

    @Test
    fun invalidCodeLeavesBothRecoveryActionsAvailable() = withDisconnectedPhone {
        // A result without a scan reservation cannot start pairing.
        model.finishScan("invalid")
        assertEquals(PhoneProblem.RelayUnavailable, model.state.value.problem)
        assertTrue(model.beginScan())
        model.finishScan("https://example.invalid/")
        assertFallback(PhoneProblem.InvalidCode)
        respondWithOldName()
        reconnectOld()
    }

    @Test
    fun cameraDenialReleasesTheScanAndKeepsTheSavedIdentity() = withDisconnectedPhone {
        assertTrue(model.beginScan())
        model.finishScan(null, cameraDenied = true)
        assertFallback(PhoneProblem.CameraDenied)
        respondWithOldName()
        reconnectOld()
    }

    @Test
    fun expiredReplacementOnTheSameMacDoesNotOverwriteTheSavedOffer() = withDisconnectedPhone {
        oldMac.onRequest = { connection, request ->
            if (request.string("type") == "device.pair") {
                connection.reply(request, buildJsonObject { put("error", "invalid_pairing") })
            }
        }
        assertTrue(model.beginScan())
        model.finishScan(pairingLink(oldMac.offer))
        assertFallback(PhoneProblem.ExpiredCode)
        assertEquals(0, store.saveAttempts)
        assertEquals(oldProfile.identity.publicKey, oldMac.lastPublicKey("device.pair"))
        respondWithOldName()
        reconnectOld()
    }

    @Test
    fun lostReplacementAcknowledgementLeavesTheOldProfileRetryable() = withDisconnectedPhone {
        FakeMac().use { candidateMac ->
            candidateMac.onRequest = { connection, _ -> connection.close() }
            assertTrue(model.beginScan())
            model.finishScan(pairingLink(candidateMac.offer))
            assertFallback(PhoneProblem.RelayUnavailable)
            assertEquals(1, candidateMac.count("device.pair"))
            assertEquals(0, candidateMac.count("device.auth"))
            respondWithOldName()
            reconnectOld()
        }
    }

    @Test
    fun storageFailureAfterNewAuthorizationKeepsTheSavedProfile() = withDisconnectedPhone {
        FakeMac().use { candidateMac ->
            candidateMac.onRequest = { connection, request -> connection.acceptPairing(request) }
            store.beforeSave = { throw IOException("simulated disk failure") }
            assertTrue(model.beginScan())
            model.finishScan(pairingLink(candidateMac.offer))
            assertFallback(PhoneProblem.Storage)
            assertEquals(1, store.saveAttempts)
            assertEquals(0, candidateMac.count("host.get"))
            respondWithOldName()
            reconnectOld()
        }
    }

    @Test
    fun acceptedScanWinsAgainstAnOldRetryAndPublishesTheNewMacOnlyAfterSaving() = withDisconnectedPhone {
        val oldRead = CompletableDeferred<Pair<FakeMac.Connection, JsonObject>>()
        oldMac.onRequest = { connection, request -> oldRead.complete(connection to request) }
        val (oldConnection, oldRequest) = withTimeout(5_000) { oldRead.await() }
        assertTrue(model.state.value.working)
        assertTrue(model.beginScan())
        // This reply was already in flight when the user opened the camera.
        oldConnection.replyHost(oldRequest, "Old Mac")
        assertTrue(model.state.value.scanning)

        FakeMac().use { candidateMac ->
            val pair = CompletableDeferred<Pair<FakeMac.Connection, JsonObject>>()
            candidateMac.onRequest = { connection, request ->
                when (request.string("type")) {
                    "device.pair" -> pair.complete(connection to request)
                    "host.get" -> connection.replyHost(request, "New Mac")
                }
            }
            val saveStarted = CompletableDeferred<PhoneProfile>()
            val allowSave = CompletableDeferred<Unit>()
            store.beforeSave = { profile ->
                saveStarted.complete(profile)
                allowSave.await()
            }
            model.finishScan(pairingLink(candidateMac.offer))
            val (candidateConnection, pairRequest) = withTimeout(5_000) { pair.await() }
            assertSame(oldProfile, store.saved)
            assertEquals(0, store.saveAttempts)
            assertNotEquals(oldProfile.identity.publicKey, pairRequest.string("publicKey"))

            candidateConnection.acceptPairing(pairRequest)
            val candidate = withTimeout(5_000) { saveStarted.await() }
            assertSame(oldProfile, store.saved)
            assertEquals(0, candidateMac.count("host.get"))
            allowSave.complete(Unit)

            until { model.state.value.status == PhoneStatus.Connected }
            assertEquals("New Mac", model.state.value.macName)
            assertSame(candidate, store.saved)
            assertEquals(1, store.saveAttempts)
            assertEquals(pairRequest.string("publicKey"), store.saved.identity.publicKey)
            assertEquals(candidateMac.offer.relayUrl, store.saved.offer.relayUrl)
        }
    }

    private class ProfileStore(var saved: PhoneProfile) {
        var beforeSave: suspend (PhoneProfile) -> Unit = {}
        var saveAttempts = 0
            private set

        suspend fun save(profile: PhoneProfile) {
            saveAttempts++
            beforeSave(profile)
            saved = profile
        }
    }

    private class DisconnectedPhone(val oldMac: FakeMac) {
        val oldProfile = PhoneProfile(oldMac.offer, PhoneIdentity.generate())
        val store = ProfileStore(oldProfile)
        val model = PhoneConnectionViewModel(loadProfile = { store.saved }, saveProfile = store::save)

        suspend fun assertFallback(problem: PhoneProblem) {
            until { model.state.value.status == PhoneStatus.Disconnected && !model.state.value.working }
            val state = model.state.value
            assertEquals(problem, state.problem)
            assertTrue(state.canScan)
            assertTrue(state.canRetry)
            assertSame(oldProfile, store.saved)
        }

        fun respondWithOldName() {
            oldMac.onRequest = { connection, request -> connection.replyHost(request, "Old Mac") }
        }

        suspend fun reconnectOld() {
            model.readOrRetry()
            until { model.state.value.status == PhoneStatus.Connected }
            assertOldConnection()
        }

        fun assertOldConnection() {
            assertEquals("Old Mac", model.state.value.macName)
            assertEquals(oldProfile.identity.publicKey, oldMac.lastPublicKey("device.auth"))
            assertSame(oldProfile, store.saved)
        }
    }

    private fun withDisconnectedPhone(test: suspend DisconnectedPhone.() -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try {
                runBlocking(main) {
                    FakeMac().use { oldMac ->
                        oldMac.onRequest = { connection, _ -> connection.close() }
                        val phone = DisconnectedPhone(oldMac)
                        try {
                            phone.assertFallback(PhoneProblem.RelayUnavailable)
                            phone.test()
                        } finally {
                            phone.model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                        }
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
    }
}

private fun pairingLink(offer: PhoneOffer): String {
    val encodedOffer = base64Url(offer.json.toString().encodeToByteArray())
    val ticket = base64Url(ByteArray(32) { 0x57 })
    return "magpie-mobile://pair#offer=$encodedOffer&ticket=$ticket"
}

private fun FakeMac.lastPublicKey(type: String): String =
    received.last { it.string("type") == type }.string("publicKey")

private fun FakeMac.Connection.acceptPairing(request: JsonObject) {
    val key = decodeBase64Url(request.string("publicKey"), 32)
    val deviceId = base64Url(MessageDigest.getInstance("SHA-256").digest(key))
    reply(request, buildJsonObject { put("deviceId", deviceId) })
}

private fun FakeMac.Connection.replyHost(request: JsonObject, name: String) {
    reply(request, buildJsonObject {
        put("hostId", FakeMac.SERVER_ID)
        put("displayName", name)
        put("receipt", UUID.randomUUID().toString())
    })
}

private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) {
    while (!condition()) kotlinx.coroutines.delay(10)
}

/**
 * A relay and Mac in one local WebSocket server. It runs the responder side of
 * the protected handshake and accepts any device.
 */
private class FakeMac : Closeable {
    private val server = MockWebServer()
    private val privateKey = ByteArray(32)
    private val publicKey = ByteArray(32)

    /** Every request the Mac decrypted, across all connections, in arrival order. */
    val received = CopyOnWriteArrayList<JsonObject>()

    /** Answers everything except the handshake and `device.auth`. */
    @Volatile var onRequest: (Connection, JsonObject) -> Unit = { _, _ -> }

    init {
        val keys = HandshakeState(PATTERN, HandshakeState.RESPONDER)
        keys.localKeyPair.generateKeyPair()
        keys.localKeyPair.getPrivateKey(privateKey, 0)
        keys.localKeyPair.getPublicKey(publicKey, 0)
        keys.destroy()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(Connection())
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val offer: PhoneOffer
        get() = PhoneOffer.parse(buildJsonObject {
            put("v", 2)
            put("serverId", SERVER_ID)
            put("minimumSecurityMode", "protected")
            put("daemonPublicKeyB64", standardBase64(publicKey))
            put("relay", buildJsonObject {
                put("endpoint", "127.0.0.1:${server.port}")
                put("useTls", false)
            })
        })

    fun count(type: String): Int = received.count { it.string("type") == type }

    override fun close() = server.shutdown()

    inner class Connection : WebSocketListener() {
        private lateinit var socket: WebSocket
        private var channel: PaseoProtectedChannel? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val secure = channel
            if (secure == null) {
                channel = accept(webSocket, text)
                send(buildJsonObject {
                    put("type", "challenge")
                    put("serverId", SERVER_ID)
                    put("nonce", base64Url(ByteArray(32) { 0x21 }))
                })
                return
            }
            if (text.startsWith("{")) return
            val request = parseObject(secure.decode(text))
            received += request
            if (request.string("type") == "device.auth") {
                val key = decodeBase64Url(request.string("publicKey"), 32)
                reply(request, buildJsonObject { put("deviceId", base64Url(MessageDigest.getInstance("SHA-256").digest(key))) })
            } else {
                onRequest(this, request)
            }
        }

        private fun accept(webSocket: WebSocket, text: String): PaseoProtectedChannel {
            val hello = Base64.getDecoder().decode(parseObject(text).obj("protected").string("message"))
            val noise = HandshakeState(PATTERN, HandshakeState.RESPONDER)
            val prologue = "paseo-relay-e2ee/1".encodeToByteArray()
            noise.setPrologue(prologue, 0, prologue.size)
            noise.localKeyPair.setPrivateKey(privateKey, 0)
            noise.start()
            val parameters = ByteArray(2)
            check(noise.readMessage(hello, 0, hello.size, parameters, 0) == parameters.size)
            val response = ByteArray(50)
            check(noise.writeMessage(response, 0, parameters, 0, parameters.size) == response.size)
            webSocket.send(buildJsonObject {
                put("type", "e2ee_ready")
                put("protected", buildJsonObject {
                    put("v", 1)
                    put("message", standardBase64(response))
                })
            }.toString())
            return PaseoProtectedChannel(noise.split())
        }

        @Synchronized
        fun send(message: JsonObject) {
            val record = checkNotNull(channel).encode(JsonObject(message + ("v" to JsonPrimitive(1))).toString())
            socket.send(record)
        }

        fun reply(request: JsonObject, fields: JsonObject) = send(
            JsonObject(fields + mapOf("type" to JsonPrimitive("result"), "id" to request.getValue("id")))
        )

        /** The relay closes this connection in the normal way. */
        fun close() {
            socket.close(1001, null)
        }

    }

    companion object {
        const val SERVER_ID = "fixture-mac"
        private const val PATTERN = "Noise_NK_25519_ChaChaPoly_SHA256"
    }
}
