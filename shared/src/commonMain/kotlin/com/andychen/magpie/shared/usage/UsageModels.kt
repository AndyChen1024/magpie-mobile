package com.andychen.magpie.shared.usage

enum class UsagePeriod(val parameter: String) {
    TODAY("today"), WEEK("7d"), MONTH("30d")
}

enum class UsageTab { OVERVIEW, REQUESTS }

enum class UsagePhase { IDLE, LOADING, READY, EMPTY, ERROR }

enum class UsageFailure {
    UNAUTHORIZED, RESTRICTED, FORBIDDEN, LOCAL_PERMISSION, NETWORK, TLS, REDIRECT, INVALID_DATA, SERVER, CREDENTIAL
}

data class UsageSummary(
    val calls: Long,
    val errors: Long,
    val input: Long,
    val output: Long,
    val costUsd: Double,
    val unpriced: Long
) {
    val tokens: Long get() = input + output
}

data class UsageRow(
    val timestamp: String,
    val agent: String?,
    val model: String?,
    val httpStatus: Int?,
    val costUsd: Double?
)

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
