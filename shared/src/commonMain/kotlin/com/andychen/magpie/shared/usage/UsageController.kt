package com.andychen.magpie.shared.usage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsageObservation internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

class UsageController internal constructor(
    private val transport: UsageTransport,
    private val credentials: UsageCredentials,
    dispatcher: CoroutineDispatcher,
    private val parserDispatcher: CoroutineDispatcher = dispatcher
) {
    constructor(transport: UsageTransport, credentials: UsageCredentials) :
        this(transport, credentials, Dispatchers.Main.immediate, Dispatchers.Default)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val states = MutableStateFlow(UsageState())
    val state: UsageState get() = states.value

    private var summaryJob: Job? = null
    private var requestsJob: Job? = null
    private var revision = 0L

    fun observe(observer: UsageObserver): UsageObservation = UsageObservation(scope.launch {
        states.collect { observer.onState(it) }
    })

    fun connect(origin: UsageOrigin) = restore(origin, UsagePeriod.TODAY, UsageTab.OVERVIEW)

    fun restore(origin: UsageOrigin, period: UsagePeriod, tab: UsageTab) {
        scope.launch { replaceQuery(UsageState(origin = origin, period = period, tab = tab)) }
    }

    fun disconnect() {
        scope.launch { replaceQuery(UsageState()) }
    }

    fun selectPeriod(period: UsagePeriod) {
        scope.launch {
            if (state.origin != null && period != state.period) {
                replaceQuery(UsageState(origin = state.origin, period = period, tab = state.tab))
            }
        }
    }

    fun openTab(tab: UsageTab) {
        scope.launch {
            if (state.origin == null || state.tab == tab) return@launch
            states.value = state.copy(tab = tab)
            when (tab) {
                UsageTab.OVERVIEW -> if (state.summary.phase == UsagePhase.IDLE) loadSummary()
                UsageTab.REQUESTS -> if (state.requests.phase == UsagePhase.IDLE) loadFirstPage()
            }
        }
    }

    fun refresh() {
        scope.launch { loadCurrentTab() }
    }

    fun retry() {
        scope.launch {
            if (state.tab == UsageTab.REQUESTS && state.requests.moreFailure != null) loadNextPage()
            else loadCurrentTab()
        }
    }

    fun loadMore() {
        scope.launch { if (state.tab == UsageTab.REQUESTS) loadNextPage() }
    }

    fun close() = scope.cancel()

    private fun replaceQuery(next: UsageState) {
        revision++
        summaryJob?.cancel()
        requestsJob?.cancel()
        states.value = next
        loadCurrentTab()
    }

    private fun loadCurrentTab() {
        when (state.tab) {
            UsageTab.OVERVIEW -> loadSummary()
            UsageTab.REQUESTS -> loadFirstPage()
        }
    }

    private fun loadSummary() {
        val query = state
        val origin = query.origin ?: return
        val expected = revision
        summaryJob?.cancel()
        states.value = query.copy(summary = SummaryState(phase = UsagePhase.LOADING))
        summaryJob = scope.launch {
            val path = "/v1/magpie/usage?period=${query.period.parameter}"
            val response = fetch(origin, path)
            currentCoroutineContext().ensureActive()
            if (expected != revision) return@launch
            val (failure, value) = withContext(parserDispatcher) {
                val reason = response.httpFailure(UsageTab.OVERVIEW)
                reason to if (reason == null) parseSummary(response.body) else null
            }
            val summary = if (value == null) {
                SummaryState(UsagePhase.ERROR, failure = failure ?: UsageFailure.INVALID_DATA)
            } else {
                val empty = value.calls == 0L && value.errors == 0L && value.tokens == 0L &&
                    value.costUsd == 0.0 && value.unpriced == 0L
                SummaryState(if (empty) UsagePhase.EMPTY else UsagePhase.READY, value = value)
            }
            states.value = state.copy(summary = summary)
        }
    }

    private fun loadFirstPage() {
        if (state.origin == null) return
        requestsJob?.cancel()
        states.value = state.copy(requests = RequestsState(phase = UsagePhase.LOADING))
        loadRequests(offset = 0, append = false)
    }

    private fun loadNextPage() {
        val previous = state.requests
        val offset = previous.nextOffset ?: return
        if (previous.loadingMore) return
        states.value = state.copy(requests = previous.copy(loadingMore = true, moreFailure = null))
        loadRequests(offset, append = true)
    }

    private fun loadRequests(offset: Long, append: Boolean) {
        val query = state
        val origin = query.origin ?: return
        val expected = revision
        requestsJob = scope.launch {
            val path = "/v1/magpie/usage/requests?period=${query.period.parameter}&offset=$offset&limit=$USAGE_PAGE_SIZE"
            val response = fetch(origin, path)
            currentCoroutineContext().ensureActive()
            if (expected != revision) return@launch
            val (failure, page) = withContext(parserDispatcher) {
                val reason = response.httpFailure(UsageTab.REQUESTS)
                reason to if (reason == null) parsePage(response.body, offset) else null
            }
            val previous = state.requests
            val requests = if (page == null) {
                val reason = failure ?: UsageFailure.INVALID_DATA
                if (append) previous.copy(loadingMore = false, moreFailure = reason)
                else RequestsState(UsagePhase.ERROR, failure = reason)
            } else {
                val rows = if (append) previous.rows + page.rows else page.rows
                RequestsState(
                    phase = if (rows.isEmpty()) UsagePhase.EMPTY else UsagePhase.READY,
                    rows = rows,
                    total = page.total,
                    nextOffset = page.nextOffset
                )
            }
            states.value = state.copy(requests = requests)
        }
    }

    private suspend fun fetch(origin: UsageOrigin, path: String): UsageHttpResponse {
        val key = try {
            credentials.read(origin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (key.isNullOrBlank() || key.any { it.code !in 33..126 }) {
            return UsageHttpResponse(0, "", UsageFailure.CREDENTIAL)
        }
        return try {
            transport.get(UsageHttpRequest(origin.value + path, "Bearer $key"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UsageHttpResponse(0, "", UsageFailure.NETWORK)
        }
    }
}
