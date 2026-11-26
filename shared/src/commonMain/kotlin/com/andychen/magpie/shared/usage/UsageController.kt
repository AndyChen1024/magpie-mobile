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

class UsageController private constructor(
    private val httpsSource: ((UsageOrigin) -> UsageQuerySource)?,
    dispatcher: CoroutineDispatcher,
    private val parserDispatcher: CoroutineDispatcher
) {
    constructor() : this(null, Dispatchers.Main.immediate, Dispatchers.Default)

    constructor(transport: UsageTransport, credentials: UsageCredentials) :
        this(transport, credentials, Dispatchers.Main.immediate, Dispatchers.Default)

    internal constructor(
        transport: UsageTransport,
        credentials: UsageCredentials,
        dispatcher: CoroutineDispatcher,
        parserDispatcher: CoroutineDispatcher = dispatcher
    ) : this({ HttpsUsageQuerySource(it, transport, credentials, parserDispatcher) }, dispatcher, parserDispatcher)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val states = MutableStateFlow(UsageState())
    val state: UsageState get() = states.value

    private var source: UsageQuerySource? = null
    private var summaryJob: Job? = null
    private var requestsJob: Job? = null
    private var revision = 0L

    fun observe(observer: UsageObserver): UsageObservation = UsageObservation(scope.launch {
        states.collect { observer.onState(it) }
    })

    fun connect(origin: UsageOrigin) = restore(origin, UsagePeriod.TODAY, UsageTab.OVERVIEW)

    fun restore(origin: UsageOrigin, period: UsagePeriod, tab: UsageTab) {
        val createSource = checkNotNull(httpsSource) { "HTTPS transport and credentials are required for an HTTPS origin" }
        scope.launch {
            source = createSource(origin)
            replaceQuery(UsageState(origin = origin, period = period, tab = tab))
        }
    }

    /** Each source is bound to one connection. Replace it when that connection changes. */
    fun restoreSource(source: UsageQuerySource, period: UsagePeriod, tab: UsageTab) {
        scope.launch {
            this@UsageController.source = source
            replaceQuery(UsageState(period = period, tab = tab))
        }
    }

    fun disconnect() {
        scope.launch {
            source = null
            replaceQuery(UsageState())
        }
    }

    fun selectPeriod(period: UsagePeriod) {
        scope.launch {
            if (source != null && period != state.period) {
                replaceQuery(UsageState(origin = state.origin, period = period, tab = state.tab))
            }
        }
    }

    fun openTab(tab: UsageTab) {
        scope.launch {
            if (source == null || state.tab == tab) return@launch
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
        invalidateJobs()
        states.value = next
        loadCurrentTab()
    }

    private fun invalidateJobs() {
        revision++
        summaryJob?.cancel()
        requestsJob?.cancel()
    }

    private fun clearDeniedData(failure: UsageFailure?): Boolean {
        if (failure != UsageFailure.DEVICE_NOT_AUTHORIZED && failure != UsageFailure.USAGE_PERMISSION_DENIED) {
            return false
        }
        if (failure == UsageFailure.DEVICE_NOT_AUTHORIZED) source = null
        invalidateJobs()
        states.value = state.copy(
            summary = SummaryState(UsagePhase.ERROR, failure = failure),
            requests = RequestsState(UsagePhase.ERROR, failure = failure)
        )
        return true
    }

    private fun loadCurrentTab() {
        when (state.tab) {
            UsageTab.OVERVIEW -> loadSummary()
            UsageTab.REQUESTS -> loadFirstPage()
        }
    }

    private fun loadSummary() {
        val query = state
        val source = source ?: return
        val expected = revision
        summaryJob?.cancel()
        states.value = query.copy(summary = SummaryState(phase = UsagePhase.LOADING))
        summaryJob = scope.launch {
            val response = fetch { source.summary(query.period) }
            currentCoroutineContext().ensureActive()
            if (expected != revision || clearDeniedData(response.failure)) return@launch
            val value = withContext(parserDispatcher) {
                if (response.failure == null) parseSummary(response.body) else null
            }
            val summary = if (value == null) {
                SummaryState(UsagePhase.ERROR, failure = response.failure ?: UsageFailure.INVALID_DATA)
            } else {
                val empty = value.calls == 0L && value.errors == 0L && value.tokens == 0L &&
                    value.costUsd == 0.0 && value.unpriced == 0L
                SummaryState(if (empty) UsagePhase.EMPTY else UsagePhase.READY, value = value)
            }
            val requests = state.requests
            states.value = state.copy(
                summary = summary,
                requests = if (value != null && requests.failure == UsageFailure.USAGE_PERMISSION_DENIED) {
                    RequestsState()
                } else requests
            )
        }
    }

    private fun loadFirstPage() {
        if (source == null) return
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
        val source = source ?: return
        val expected = revision
        requestsJob = scope.launch {
            val response = fetch { source.requests(query.period, offset, USAGE_PAGE_SIZE) }
            currentCoroutineContext().ensureActive()
            if (expected != revision || clearDeniedData(response.failure)) return@launch
            val page = withContext(parserDispatcher) {
                if (response.failure == null) parsePage(response.body, offset) else null
            }
            val previous = state.requests
            val requests = if (page == null) {
                val reason = response.failure ?: UsageFailure.INVALID_DATA
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
            val summary = state.summary
            states.value = state.copy(
                requests = requests,
                summary = if (page != null && summary.failure == UsageFailure.USAGE_PERMISSION_DENIED) {
                    SummaryState()
                } else summary
            )
        }
    }

    private suspend fun fetch(query: suspend () -> UsageQueryResult): UsageQueryResult = try {
        query()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        UsageQueryResult("", UsageFailure.NETWORK)
    }
}
