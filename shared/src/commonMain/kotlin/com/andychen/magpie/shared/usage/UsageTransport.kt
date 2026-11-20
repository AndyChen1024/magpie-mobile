package com.andychen.magpie.shared.usage

/** Native storage must return a key only for the specified origin. */
interface UsageCredentials {
    suspend fun read(origin: UsageOrigin): String?
}

/** Native transport must keep platform TLS validation and disable redirects. */
interface UsageTransport {
    suspend fun get(request: UsageHttpRequest): UsageHttpResponse
}

class UsageHttpRequest internal constructor(
    val url: String,
    val authorization: String
)

class UsageHttpResponse(
    val statusCode: Int,
    val body: String,
    val failure: UsageFailure? = null
)

fun interface UsageObserver {
    fun onState(state: UsageState)
}
