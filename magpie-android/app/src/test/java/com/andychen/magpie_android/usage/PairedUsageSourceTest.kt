package com.andychen.magpie_android.usage

import com.andychen.magpie.shared.usage.UsageFailure
import com.andychen.magpie.shared.usage.UsagePeriod
import com.andychen.magpie_android.phone.FakeMac
import com.andychen.magpie_android.phone.PhoneIdentity
import com.andychen.magpie_android.phone.PhoneRelaySession
import com.andychen.magpie_android.phone.PhoneServerError
import com.andychen.magpie_android.phone.parseObject
import com.andychen.magpie_android.phone.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairedUsageSourceTest {
    @Test
    fun sendsUsageFieldsAtTheTopLevelOfProtectedRequests() = runBlocking {
        val summary = parseObject("""{"calls":1}""")
        val page = parseObject("""{"offset":50,"total":50,"rows":[]}""")
        FakeMac().use { mac ->
            mac.onRequest = { connection, request ->
                val usage = if (request.string("type") == "usage.summary") summary else page
                connection.reply(request, buildJsonObject { put("usage", usage) })
            }
            PhoneRelaySession(PhoneRelaySession.client(), mac.offer).use { session ->
                session.handshake()
                session.authorize(PhoneIdentity.generate(), ticket = null, label = "")
                val source = PairedUsageSource(
                    { type, fields -> session.exchange(type, fields, timeoutMs = 5_000) },
                    { session.isOpen },
                    session::authorizationLost
                )
                assertEquals(summary, parseObject(source.summary(UsagePeriod.TODAY).body))
                assertEquals(page, parseObject(source.requests(UsagePeriod.MONTH, 50, 50).body))

                val requests = mac.received.filter { it.string("type").startsWith("usage.") }
                val envelope = setOf("v", "type", "id")
                assertEquals("usage.summary", requests[0].string("type"))
                assertEquals(envelope + "period", requests[0].keys)
                assertEquals("today", requests[0].string("period"))
                assertEquals("usage.requests", requests[1].string("type"))
                assertEquals(envelope + setOf("period", "offset", "limit"), requests[1].keys)
                assertEquals("30d", requests[1].string("period"))
                assertEquals(JsonPrimitive(50), requests[1]["offset"])
                assertEquals(JsonPrimitive(50), requests[1]["limit"])
            }
        }
    }

    @Test
    fun mapsMacFailuresAndOnlyDeviceRevocationClosesTheSession() = runBlocking {
        val failures = linkedMapOf(
            "usage_permission_denied" to UsageFailure.USAGE_PERMISSION_DENIED,
            "usage_unavailable" to UsageFailure.USAGE_UNAVAILABLE,
            "invalid_request" to UsageFailure.INVALID_REQUEST,
            "unsupported_request" to UsageFailure.UNSUPPORTED_REQUEST,
            "device_not_authorized" to UsageFailure.DEVICE_NOT_AUTHORIZED
        )
        FakeMac().use { mac ->
            val replies = failures.keys.iterator()
            mac.onRequest = { connection, request ->
                connection.reply(request, buildJsonObject { put("error", replies.next()) })
            }
            PhoneRelaySession(PhoneRelaySession.client(), mac.offer).use { session ->
                session.handshake()
                session.authorize(PhoneIdentity.generate(), ticket = null, label = "")
                val source = PairedUsageSource(
                    { type, fields -> session.exchange(type, fields, timeoutMs = 5_000) },
                    { session.isOpen },
                    session::authorizationLost
                )
                for (expected in failures.values) {
                    val result = source.summary(UsagePeriod.WEEK)
                    assertEquals(expected, result.failure)
                    assertEquals("", result.body)
                    assertEquals(expected != UsageFailure.DEVICE_NOT_AUTHORIZED, session.isOpen)
                }
                val terminal = runCatching { session.awaitRead(Channel<Unit>()) }.exceptionOrNull()
                assertEquals("device_not_authorized", (terminal as PhoneServerError).code)
            }
        }
    }

    @Test
    fun aReplyFromTheReplacedConnectionCannotInvalidateItsReplacement() = runBlocking {
        var current = true
        var revoked = false
        var exchanges = 0
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<JsonObject>()
        val source = PairedUsageSource(
            exchange = { _, _ ->
                exchanges++
                started.complete(Unit)
                reply.await()
            },
            isCurrent = { current },
            onAuthorizationLost = { revoked = true }
        )
        val pending = async { source.summary(UsagePeriod.TODAY) }
        withTimeout(5_000) { started.await() }
        current = false
        reply.complete(parseObject("""{"error":"device_not_authorized"}"""))
        val result = pending.await()
        assertEquals(UsageFailure.NETWORK, result.failure)
        assertFalse(revoked)
        assertEquals(UsageFailure.NETWORK, source.summary(UsagePeriod.TODAY).failure)
        assertEquals(1, exchanges)
    }
}
