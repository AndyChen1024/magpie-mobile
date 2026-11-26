package com.andychen.magpie.shared.usage

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class UsageControllerTest {
    @Test
    fun handlesRestrictedOverviewAndRetriesPageWithoutDuplicateRows() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        try {
            controller.connect(harness.first)
            runCurrent()
            harness.complete(403, UsageFixtures.restricted, suffix = "usage?period=today")
            assertEquals(UsageFailure.RESTRICTED, controller.state.summary.failure)

            controller.openTab(UsageTab.REQUESTS)
            runCurrent()
            val row = UsageFixtures.row
            harness.complete(200, UsageFixtures.page(0, 51, *Array(50) { row }), "offset=0&limit=50")
            assertEquals(50, controller.state.requests.rows.size)
            controller.loadMore()
            controller.loadMore()
            runCurrent()
            assertEquals(1, harness.calls.size)
            harness.complete(503, "", "offset=50&limit=50")
            assertEquals(50, controller.state.requests.rows.size)
            assertEquals(UsageFailure.SERVER, controller.state.requests.moreFailure)
            controller.retry()
            runCurrent()
            harness.complete(200, UsageFixtures.page(50, 51, row), "offset=50&limit=50")
            assertEquals(51, controller.state.requests.rows.size)
            assertNull(controller.state.requests.nextOffset)
            controller.refresh()
            runCurrent()
            harness.complete(200, UsageFixtures.page(0, 1, row), "offset=0&limit=50")
            assertEquals(1, controller.state.requests.rows.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun lateResponsesAfterPeriodHostKeyAndDisconnectDoNotPublish() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        val second = UsageOrigin.parse("https://second.local")!!
        try {
            controller.connect(harness.first)
            runCurrent()
            val oldPeriod = harness.calls.removeFirst()
            controller.selectPeriod(UsagePeriod.WEEK)
            runCurrent()
            harness.complete(200, UsageFixtures.summary, suffix = "period=7d")
            oldPeriod.respond(200, UsageFixtures.zero)
            runCurrent()
            assertEquals(UsagePeriod.WEEK, controller.state.period)
            assertEquals(3L, controller.state.summary.value?.calls)

            controller.refresh()
            runCurrent()
            val oldHost = harness.calls.removeFirst()
            controller.connect(second)
            runCurrent()
            assertEquals("Bearer key-second", harness.calls.single().request.authorization)
            harness.complete(200, UsageFixtures.zero)
            oldHost.respond(200, UsageFixtures.summary)
            runCurrent()
            assertEquals(second, controller.state.origin)
            assertEquals(UsagePhase.EMPTY, controller.state.summary.phase)

            controller.refresh()
            runCurrent()
            val oldKey = harness.calls.removeFirst()
            harness.secondKey = "replacement"
            controller.connect(second)
            runCurrent()
            assertEquals("Bearer replacement", harness.calls.single().request.authorization)
            assertEquals(second.value + "/v1/magpie/usage?period=today", harness.calls.single().request.url)
            harness.complete(200, UsageFixtures.summary)
            oldKey.respond(200, UsageFixtures.zero)
            runCurrent()
            assertEquals(3L, controller.state.summary.value?.calls)

            controller.openTab(UsageTab.REQUESTS)
            runCurrent()
            val latePage = harness.calls.removeFirst()
            controller.disconnect()
            runCurrent()
            latePage.respond(200, UsageFixtures.page(0, 1, UsageFixtures.row))
            runCurrent()
            assertEquals(UsageState(), controller.state)
        } finally {
            harness.close()
        }
    }

    @Test
    fun separatesEmptyBadDataNetworkAndDeniedPermission() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        try {
            controller.connect(harness.first)
            runCurrent()
            harness.complete(200, UsageFixtures.zero)
            assertEquals(UsagePhase.EMPTY, controller.state.summary.phase)
            controller.retry()
            runCurrent()
            harness.complete(200, """{"calls":0}""")
            assertEquals(UsageFailure.INVALID_DATA, controller.state.summary.failure)
            controller.retry()
            runCurrent()
            harness.fail(UsageFailure.NETWORK)
            assertEquals(UsageFailure.NETWORK, controller.state.summary.failure)
            controller.retry()
            runCurrent()
            harness.fail(UsageFailure.LOCAL_PERMISSION)
            assertEquals(UsageFailure.LOCAL_PERMISSION, controller.state.summary.failure)
            controller.retry()
            runCurrent()
            harness.complete(401, "")
            assertEquals(UsageFailure.UNAUTHORIZED, controller.state.summary.failure)
            controller.retry()
            runCurrent()
            harness.complete(200, UsageFixtures.summary)
            assertEquals(UsagePhase.READY, controller.state.summary.phase)
        } finally {
            harness.close()
        }
    }

    @Test
    fun fixedSourcesIgnoreLateRepliesAcrossHttpsAndPairedConnections() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        val firstMac = HeldSource()
        val replacementMac = HeldSource()
        try {
            controller.connect(harness.first)
            runCurrent()
            val staleHttps = harness.calls.removeFirst()

            controller.restoreSource(firstMac, UsagePeriod.WEEK, UsageTab.REQUESTS)
            runCurrent()
            assertNull(controller.state.origin)
            assertEquals(Triple(UsagePeriod.WEEK, 0L, 50), firstMac.pageQueries.single())
            firstMac.pages.removeFirst().resume(UsageQueryResult(UsageFixtures.page(0, 3, UsageFixtures.row)))
            staleHttps.respond(200, UsageFixtures.summary)
            runCurrent()
            assertNull(controller.state.summary.value)
            assertEquals(1L, controller.state.requests.nextOffset)

            controller.loadMore()
            runCurrent()
            assertEquals(Triple(UsagePeriod.WEEK, 1L, 50), firstMac.pageQueries.last())
            val stalePage = firstMac.pages.removeFirst()
            controller.restoreSource(replacementMac, UsagePeriod.WEEK, UsageTab.REQUESTS)
            runCurrent()
            assertTrue(controller.state.requests.rows.isEmpty())
            assertEquals(Triple(UsagePeriod.WEEK, 0L, 50), replacementMac.pageQueries.single())
            stalePage.resume(UsageQueryResult(UsageFixtures.page(1, 3, UsageFixtures.row)))
            runCurrent()
            assertEquals(UsagePhase.LOADING, controller.state.requests.phase)

            val staleReplacement = replacementMac.pages.removeFirst()
            controller.connect(harness.first)
            runCurrent()
            harness.complete(200, UsageFixtures.summary)
            staleReplacement.resume(UsageQueryResult("", UsageFailure.DEVICE_NOT_AUTHORIZED))
            runCurrent()
            assertEquals(harness.first, controller.state.origin)
            assertEquals(3L, controller.state.summary.value?.calls)
            assertTrue(controller.state.requests.rows.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun pairedDenialClearsBothTabsAndRevocationStopsQueriesUntilRebound() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        val source = HeldSource()
        try {
            controller.restoreSource(source, UsagePeriod.MONTH, UsageTab.REQUESTS)
            runCurrent()
            source.pages.removeFirst().resume(UsageQueryResult(UsageFixtures.page(0, 2, UsageFixtures.row)))
            runCurrent()
            controller.openTab(UsageTab.OVERVIEW)
            runCurrent()
            source.summaries.removeFirst().resume(UsageQueryResult(UsageFixtures.summary))
            runCurrent()
            assertEquals(3L, controller.state.summary.value?.calls)
            assertEquals(1, controller.state.requests.rows.size)

            controller.openTab(UsageTab.REQUESTS)
            controller.loadMore()
            runCurrent()
            source.pages.removeFirst().resume(UsageQueryResult("", UsageFailure.USAGE_PERMISSION_DENIED))
            runCurrent()
            assertNull(controller.state.summary.value)
            assertTrue(controller.state.requests.rows.isEmpty())
            assertNull(controller.state.requests.nextOffset)
            assertEquals(UsageFailure.USAGE_PERMISSION_DENIED, controller.state.summary.failure)
            assertEquals(UsageFailure.USAGE_PERMISSION_DENIED, controller.state.requests.failure)

            controller.retry()
            runCurrent()
            assertEquals(Triple(UsagePeriod.MONTH, 0L, 50), source.pageQueries.last())
            source.pages.removeFirst().resume(UsageQueryResult(UsageFixtures.page(0, 1, UsageFixtures.row)))
            runCurrent()
            controller.openTab(UsageTab.OVERVIEW)
            runCurrent()
            source.summaries.removeFirst().resume(UsageQueryResult("", UsageFailure.DEVICE_NOT_AUTHORIZED))
            runCurrent()
            assertTrue(controller.state.requests.rows.isEmpty())
            assertEquals(UsageFailure.DEVICE_NOT_AUTHORIZED, controller.state.summary.failure)
            controller.retry()
            runCurrent()
            assertTrue(source.summaries.isEmpty())
        } finally {
            harness.close()
        }
    }

    private class HeldSource : UsageQuerySource {
        val summaries = ArrayDeque<Continuation<UsageQueryResult>>()
        val pages = ArrayDeque<Continuation<UsageQueryResult>>()
        val pageQueries = mutableListOf<Triple<UsagePeriod, Long, Int>>()

        override suspend fun summary(period: UsagePeriod): UsageQueryResult =
            suspendCoroutine { summaries.addLast(it) }

        override suspend fun requests(period: UsagePeriod, offset: Long, limit: Int): UsageQueryResult {
            pageQueries += Triple(period, offset, limit)
            return suspendCoroutine { pages.addLast(it) }
        }
    }

    private class Harness(private val scope: TestScope) {
        val first = UsageOrigin.parse("https://first.local")!!
        var secondKey = "key-second"
        val calls = ArrayDeque<Call>()
        val controller = UsageController(
            transport = object : UsageTransport {
                override suspend fun get(request: UsageHttpRequest): UsageHttpResponse =
                    suspendCoroutine { calls.addLast(Call(request, it)) }
            },
            credentials = object : UsageCredentials {
                override suspend fun read(origin: UsageOrigin) = if (origin == first) "key-first" else secondKey
            },
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )

        fun complete(status: Int, body: String, suffix: String = "") {
            val call = calls.removeFirst()
            assertTrue(call.request.url.endsWith(suffix))
            call.respond(status, body)
            scope.runCurrent()
        }

        fun fail(failure: UsageFailure) {
            calls.removeFirst().result.resume(UsageHttpResponse(0, "", failure))
            scope.runCurrent()
        }

        fun close() {
            controller.close()
            while (calls.isNotEmpty()) calls.removeFirst().respond(500, "")
            scope.runCurrent()
        }
    }

    private data class Call(val request: UsageHttpRequest, val result: Continuation<UsageHttpResponse>) {
        fun respond(status: Int, body: String) = result.resume(UsageHttpResponse(status, body))
    }
}
