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
