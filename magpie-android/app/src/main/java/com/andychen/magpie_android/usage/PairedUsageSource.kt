package com.andychen.magpie_android.usage

import com.andychen.magpie.shared.usage.UsageFailure
import com.andychen.magpie.shared.usage.UsagePeriod
import com.andychen.magpie.shared.usage.UsageQueryResult
import com.andychen.magpie.shared.usage.UsageQuerySource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A usage source bound to one authorized phone.session connection. */
internal class PairedUsageSource(
    private val exchange: suspend (String, JsonObject) -> JsonObject,
    private val isCurrent: () -> Boolean,
    private val onAuthorizationLost: () -> Unit
) : UsageQuerySource {
    override suspend fun summary(period: UsagePeriod): UsageQueryResult =
        query("usage.summary", buildJsonObject { put("period", period.parameter) })

    override suspend fun requests(period: UsagePeriod, offset: Long, limit: Int): UsageQueryResult =
        query("usage.requests", buildJsonObject {
            put("period", period.parameter)
            put("offset", offset)
            put("limit", limit)
        })

    private suspend fun query(type: String, fields: JsonObject): UsageQueryResult {
        if (!isCurrent()) return UsageQueryResult("", UsageFailure.NETWORK)
        val reply = try {
            exchange(type, fields)
        } catch (_: TimeoutCancellationException) {
            return UsageQueryResult("", UsageFailure.NETWORK)
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return UsageQueryResult("", UsageFailure.NETWORK)

        if ("error" in reply) {
            val code = (reply["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val failure = when (code) {
                "device_not_authorized" -> UsageFailure.DEVICE_NOT_AUTHORIZED
                "usage_permission_denied" -> UsageFailure.USAGE_PERMISSION_DENIED
                "usage_unavailable" -> UsageFailure.USAGE_UNAVAILABLE
                "invalid_request" -> UsageFailure.INVALID_REQUEST
                "unsupported_request" -> UsageFailure.UNSUPPORTED_REQUEST
                else -> UsageFailure.INVALID_DATA
            }
            if (failure == UsageFailure.DEVICE_NOT_AUTHORIZED) onAuthorizationLost()
            return UsageQueryResult("", failure)
        }
        val usage = reply["usage"] as? JsonObject ?: return UsageQueryResult("", UsageFailure.INVALID_DATA)
        return UsageQueryResult(usage.toString())
    }
}
