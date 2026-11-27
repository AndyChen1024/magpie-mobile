package com.andychen.magpie.shared.usage

enum class UsagePeriod(val parameter: String) {
    TODAY("today"), WEEK("7d"), MONTH("30d")
}

enum class UsageTab { OVERVIEW, REQUESTS }

enum class UsagePhase { IDLE, LOADING, READY, EMPTY, ERROR }

enum class UsageFailure {
    UNAUTHORIZED, RESTRICTED, FORBIDDEN, LOCAL_PERMISSION, NETWORK, TLS, REDIRECT, INVALID_DATA, SERVER, CREDENTIAL,
    DEVICE_NOT_AUTHORIZED, USAGE_PERMISSION_DENIED, USAGE_UNAVAILABLE, INVALID_REQUEST, UNSUPPORTED_REQUEST
}

data class UsageSummary(
    val calls: Long,
    val errors: Long,
    val input: Long,
    val output: Long,
    val costUsd: Double,
    val unpriced: Long,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val cacheUnknown: Long? = null,
    val reasoning: Long? = null,
    val bucket: String? = null,
    val series: List<UsagePoint> = emptyList(),
    val agents: List<UsageGroup> = emptyList(),
    val models: List<UsageGroup> = emptyList()
) {
    val tokens: Long get() = input + output

    val cacheHitRate: Double?
        get() {
            val reads = cacheRead ?: return null
            val writes = cacheWrite ?: return null
            val measuredPrompt = (input - (cacheUnknown ?: 0)).toDouble() + reads + writes
            if (measuredPrompt <= 0) return null
            return (reads / measuredPrompt).takeIf { it in 0.0..1.0 }
        }
}

data class UsagePoint(val timestamp: String, val input: Long, val output: Long) {
    val tokens: Long = requireNotNull(usageTokenSum(input, output))
}

data class UsageGroup(val name: String, val calls: Long, val input: Long, val output: Long) {
    val tokens: Long = requireNotNull(usageTokenSum(input, output))
}

data class UsageRow(
    val timestamp: String,
    val agent: String?,
    val model: String?,
    val httpStatus: Int?,
    val costUsd: Double?,
    val provider: String? = null,
    val requestedModel: String? = null,
    val servedModel: String? = null,
    val input: Long? = null,
    val output: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val cacheUnknown: Boolean = false,
    val reasoning: Long? = null,
    val durationMillis: Long? = null,
    val firstTokenMillis: Long? = null,
    val canceled: Boolean = false,
    val failed: Boolean = !canceled && httpStatus != null && httpStatus >= 400
) {
    val tokens: Long? get() = usageTokenSum(input, output)
}

internal fun usageTokenSum(input: Long?, output: Long?): Long? =
    if (input == null || output == null || input < 0 || output < 0 || input > Long.MAX_VALUE - output) {
        null
    } else {
        input + output
    }

data class SummaryState(
    val phase: UsagePhase = UsagePhase.IDLE,
    val value: UsageSummary? = null,
    val failure: UsageFailure? = null
)

data class RequestsState(
    val phase: UsagePhase = UsagePhase.IDLE,
    val rows: List<UsageRow> = emptyList(),
    val total: Long? = null,
    val nextOffset: Long? = null,
    val loadingMore: Boolean = false,
    val failure: UsageFailure? = null,
    val moreFailure: UsageFailure? = null
)

data class UsageState(
    val origin: UsageOrigin? = null,
    val period: UsagePeriod = UsagePeriod.TODAY,
    val tab: UsageTab = UsageTab.OVERVIEW,
    val summary: SummaryState = SummaryState(),
    val requests: RequestsState = RequestsState()
)
