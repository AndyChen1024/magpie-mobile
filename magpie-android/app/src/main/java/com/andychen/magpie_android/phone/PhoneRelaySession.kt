package com.andychen.magpie_android.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class PhoneServerError(val code: String) : Exception()
internal class PhoneProtocolError : Exception()

/**
 * One protected connection to the Mac. After the handshake, replies are routed
 * to their requests by `id`.
 */
internal class PhoneRelaySession(
    client: OkHttpClient,
    private val offer: PhoneOffer,
) : Closeable {
    private val inbox = Channel<String>(16)
    private val opened = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val cipherLock = Any()
    @Volatile private var channel: PaseoProtectedChannel? = null
    private lateinit var nonce: String

    val isOpen: Boolean get() = !closed.isCompleted

    /** Route a device revocation from any protected request through the connection owner. */
    fun authorizationLost() {
        end(PhoneServerError("device_not_authorized"))
        socket.cancel()
    }

    private val socket = client.newWebSocket(Request.Builder().url(offer.relayUrl).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > MAX_RELAY_FRAME_BYTES || !inbox.trySend(text).isSuccess) {
                end(PhoneProtocolError())
                webSocket.cancel()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            end(PhoneProtocolError())
            webSocket.cancel()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            end(IOException())
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            end(IOException())
        }
    })

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun handshake() = withTimeout(12_000) {
        opened.await()
        PaseoHandshake(offer.macPublicKey).use { handshake ->
            socketSend(handshake.hello)
            while (channel == null) {
                // A relay may open the client socket before the Mac peer joins it.
                select {
                    inbox.onReceive { incoming -> channel = handshake.accept(incoming) }
                    onTimeout(1_000) { socketSend(handshake.hello) }
                }
            }
        }
        val challenge = receiveProtected()
        require(challenge.string("type") == "challenge" && challenge.string("serverId") == offer.serverId)
        nonce = challenge.string("nonce")
        decodeBase64Url(nonce, 32)
        // From here one reader decrypts every record in arrival order.
        scope.launch { route() }
    }

    suspend fun authorize(identity: PhoneIdentity, ticket: String?, label: String) {
        val operation = if (ticket == null) "device.auth" else "device.pair"
        val (_, reply) = request(operation, buildJsonObject {
            put("publicKey", identity.publicKey)
            put("signature", identity.proof(offer.serverId, nonce, operation, ticket.orEmpty()))
            if (ticket != null) {
                put("ticket", ticket)
                put("label", label)
            }
        })
        require(reply.string("deviceId") == identity.deviceId)
    }

    suspend fun readHost(): HostReading {
        val (id, reply) = request("host.get")
        return HostReading.fromReply(reply, id, offer.serverId)
    }

    suspend fun awaitRead(refresh: Channel<Unit>) = select {
        closed.onAwait { }
        refresh.onReceive { }
    }

    /**
     * Writes one request and returns the Mac's reply, including a reply that
     * reports an error. Throws when no reply arrives: the Mac may still have
     * received the request.
     */
    suspend fun exchange(type: String, fields: JsonObject, timeoutMs: Long): JsonObject {
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        try {
            // Completes a request that raced with the end of the connection.
            if (closed.isCompleted) closed.await()
            sendProtected(phoneRequest(type, id, fields).toString())
            return withTimeout(timeoutMs) { reply.await() }
        } catch (error: IOException) {
            if (closed.isCompleted) closed.await()
            throw error
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap())): Pair<String, JsonObject> {
        val reply = exchange(type, fields, 8_000)
        if ("error" in reply) throw PhoneServerError(reply.string("error"))
        return reply.string("id") to reply
    }

    private suspend fun route() {
        try {
            while (true) {
                val message = receiveProtected()
                when ((message["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                    "result" -> pending.remove(message.string("id"))?.complete(message)
                    // Other record types belong to a newer Mac.
                }
            }
        } catch (error: Exception) {
            end(if (error is IOException) error else PhoneProtocolError())
            socket.cancel()
        }
    }

    private suspend fun receiveProtected(): JsonObject {
        val secure = checkNotNull(channel)
        while (true) {
            val text = inbox.receive()
            // The Mac may send a second ready in response to an in-flight hello retry.
            if (text.startsWith("{") && parseObject(text).string("type") == "e2ee_ready") continue
            return parseObject(synchronized(cipherLock) { secure.decode(text) }).also { require(it.number("v") == 1) }
        }
    }

    private fun sendProtected(text: String) {
        val secure = checkNotNull(channel)
        // The record counter and the socket queue must advance together.
        synchronized(cipherLock) {
            socketSend(secure.encode(text))
        }
    }

    private fun socketSend(text: String) {
        if (!socket.send(text)) throw IOException()
    }

    private fun end(error: Exception) {
        if (!closed.completeExceptionally(error)) return
        opened.completeExceptionally(error)
        inbox.close(error)
        for (id in pending.keys) pending.remove(id)?.completeExceptionally(error)
    }

    override fun close() {
        end(IOException())
        socket.cancel()
        scope.cancel()
        synchronized(cipherLock) { channel?.close() }
    }

    companion object {
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
