package com.andychen.magpie.shared.usage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Returns the gateway JSON payload for the selected host. */
interface UsageQuerySource {
    suspend fun summary(period: UsagePeriod): UsageQueryResult
    suspend fun requests(period: UsagePeriod, offset: Long, limit: Int): UsageQueryResult
}

class UsageQueryResult(val body: String, val failure: UsageFailure? = null)

internal class HttpsUsageQuerySource(
    private val origin: UsageOrigin,
    private val transport: UsageTransport,
    private val credentials: UsageCredentials,
    private val parserDispatcher: CoroutineDispatcher
) : UsageQuerySource {
    override suspend fun summary(period: UsagePeriod): UsageQueryResult =
        get("/v1/magpie/usage?period=${period.parameter}", UsageTab.OVERVIEW)

    override suspend fun requests(period: UsagePeriod, offset: Long, limit: Int): UsageQueryResult =
        get("/v1/magpie/usage/requests?period=${period.parameter}&offset=$offset&limit=$limit", UsageTab.REQUESTS)

    private suspend fun get(path: String, tab: UsageTab): UsageQueryResult {
        val key = try {
            credentials.read(origin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (key.isNullOrBlank() || key.any { it.code !in 33..126 }) {
            return UsageQueryResult("", UsageFailure.CREDENTIAL)
        }
        val response = transport.get(UsageHttpRequest(origin.value + path, "Bearer $key"))
        return withContext(parserDispatcher) { UsageQueryResult(response.body, response.httpFailure(tab)) }
    }
}
