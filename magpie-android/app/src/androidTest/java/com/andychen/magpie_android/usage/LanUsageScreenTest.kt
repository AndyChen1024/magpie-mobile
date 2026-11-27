package com.andychen.magpie_android.usage

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andychen.magpie.shared.usage.UsageController
import com.andychen.magpie.shared.usage.UsageCredentials
import com.andychen.magpie.shared.usage.UsageHttpRequest
import com.andychen.magpie.shared.usage.UsageHttpResponse
import com.andychen.magpie.shared.usage.UsageOrigin
import com.andychen.magpie.shared.usage.UsageTransport
import com.andychen.magpie_android.R
import com.andychen.magpie_android.ui.MagpieApp
import com.andychen.magpie_android.ui.ThemeMode
import com.andychen.magpie_android.ui.theme.MagpieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanUsageScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun restrictedOverviewStillOpensOwnRequestsAndRefreshReplacesRows() {
        var requests = 0
        withUsage({ request ->
            if ("/usage/requests?" in request.url) {
                requests++
                assertEquals("Bearer demo-key", request.authorization)
                if (requests == 1) UsageHttpResponse(503, "") else UsageHttpResponse(
                    200, """{"rows":[{"t":"2026-11-20T10:00:00+08:00","agent":"demo","model":"own/model"}],"offset":0,"total":1}"""
                )
            } else UsageHttpResponse(403, """{"error":{"code":"gateway_key_held"}}""")
        }) {
            waitFor(label(R.string.usage_restricted))
            compose.onNodeWithText(label(R.string.usage_restricted)).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.requests)).performClick()
            waitFor(label(R.string.usage_server_error))
            compose.onNodeWithText(label(R.string.usage_retry)).performClick()
            waitFor("own/model")
            compose.onNodeWithText("own/model").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.usage_refresh)).performClick()
            waitFor(label(R.string.usage_rows_shown, 1, 1L))
            compose.onNodeWithText(label(R.string.usage_rows_shown, 1, 1L)).assertIsDisplayed()
            compose.onAllNodesWithText("own/model").assertCountEquals(1)
            compose.runOnIdle { assertEquals(3, requests) }
        }
    }

    @Test
    fun extendedUsageShowsHostBucketsGroupsAndRequestDetails() = withUsage({ request ->
        UsageHttpResponse(200, if ("/usage/requests?" in request.url) EXTENDED_REQUESTS else EXTENDED_SUMMARY)
    }) {
        waitFor(label(R.string.usage_total_tokens))
        showUsageText("83")
        showUsageText(label(R.string.usage_known_cost))
        showUsageText(label(R.string.usage_error_count_value, "1"))
        showUsageText(label(R.string.usage_reasoning_in_output))
        showUsageText(label(R.string.usage_cache_write))
        showUsageText(label(R.string.usage_cache_hit_rate))
        showUsageText(label(R.string.usage_trend_hour))
        showUsageText("03:00")
        showUsageText("05:00")
        showUsageText(label(R.string.usage_trend_values))
        compose.onNodeWithText(label(R.string.usage_trend_values)).performClick()
        showDetailText("04:00 +13:00", substring = true)
        showDetailText(label(R.string.io_tokens, "0", "0"))
        showDetailText(label(R.string.io_tokens, "19", "7"))
        compose.onNodeWithText(label(R.string.close)).performClick()
        showUsageText(label(R.string.usage_top_agents))
        showUsageText("Fixture Agent")
        showUsageText(label(R.string.usage_top_models))
        showUsageText("fixture/group")
        showUsageText(label(R.string.usage_group_counts, "40", "3"))

        openRequests()
        waitFor(label(R.string.usage_rows_shown, 3, 3L))
        showUsageText("fixture/served")
        compose.onNodeWithText("fixture/served").performClick()
        showDetailText("fixture/requested")
        showDetailText(label(R.string.usage_sent_model))
        showDetailText("fixture/routed")
        showDetailText(label(R.string.usage_served_model))
        showDetailText("Fixture Provider")
        showDetailText(label(R.string.usage_http_status, 200))
        showDetailText(label(R.string.usage_request_metering))
        showDetailText("29")
        showDetailText("0")
        showDetailText(label(R.string.usage_duration_value, "420"))
        showDetailText(label(R.string.usage_duration_value, "82"))
        compose.onNodeWithText(label(R.string.close)).performClick()

        showUsageText(label(R.string.usage_status_canceled), substring = true)
        showUsageText("fixture/canceled")
        compose.onNodeWithText("fixture/canceled").performClick()
        showDetailText(label(R.string.usage_sent_model))
        showDetailText(label(R.string.usage_served_model))
        showDetailText(label(R.string.usage_cache_not_reported))
        compose.onNodeWithText(label(R.string.close)).performClick()
        showUsageText(label(R.string.call_summary, label(R.string.status_failed), label(R.string.usage_http_status, 503)))
    }

    @Test
    fun oldUsageHidesOverviewExtensionsAndExplainsMissingRequestValues() = withUsage({ request ->
        UsageHttpResponse(200, if ("/usage/requests?" in request.url) OLD_REQUESTS else OLD_SUMMARY)
    }) {
        waitFor(label(R.string.usage_total_tokens))
        showUsageText(label(R.string.usage_known_cost))
        showUsageText(label(R.string.usage_cost_unavailable))
        listOf(
            R.string.usage_reasoning_in_output, R.string.cache_read, R.string.usage_cache_write, R.string.usage_cache_hit_rate,
            R.string.usage_trend_hour, R.string.usage_trend_day, R.string.usage_top_agents, R.string.usage_top_models
        ).forEach { compose.onNodeWithText(label(it)).assertDoesNotExist() }

        openRequests()
        waitFor(label(R.string.usage_rows_shown, 1, 1L))
        showUsageText(label(R.string.usage_http_missing))
        compose.onNodeWithText("legacy/model").performClick()
        showDetailText("legacy/model")
        showDetailText(label(R.string.usage_http_missing))
        showDetailText(label(R.string.usage_price_unknown))
        listOf(
            R.string.input_tokens, R.string.usage_first_token, R.string.usage_provider,
            R.string.usage_requested_model, R.string.usage_served_model
        ).forEach { id ->
            compose.onNode(hasText(label(id)) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        }
    }

    private fun withUsage(respond: (UsageHttpRequest) -> UsageHttpResponse, verify: () -> Unit) {
        val origin = checkNotNull(UsageOrigin.parse("https://fixture.local"))
        val controller = UsageController(
            transport = object : UsageTransport {
                override suspend fun get(request: UsageHttpRequest) = respond(request)
            },
            credentials = object : UsageCredentials {
                override suspend fun read(origin: UsageOrigin) = "demo-key"
            }
        )
        try {
            compose.setContent {
                var state by remember { mutableStateOf(controller.state) }
                DisposableEffect(controller) {
                    val binding = controller.observe { state = it }
                    controller.connect(origin)
                    onDispose { binding.cancel() }
                }
                MagpieTheme(ThemeMode.Light) {
                    MagpieApp(ThemeMode.Light, onThemeChange = {}) {
                        LanUsagePage(
                            LanUsageState(state, working = false, mode = UsageConnectionMode.Manual),
                            onPeriod = controller::selectPeriod,
                            onTab = controller::openTab,
                            onRefresh = controller::refresh,
                            onRetry = controller::retry,
                            onMore = controller::loadMore,
                            onEdit = {}, onDisconnect = {}, onDemo = {}, onPermissionSettings = {}
                        )
                    }
                }
            }
            compose.onNodeWithTag("nav_more").performClick()
            compose.onNodeWithText(label(R.string.usage)).performClick()
            verify()
        } finally {
            controller.close()
        }
    }

    private fun openRequests() {
        val requests = label(R.string.requests)
        compose.onNodeWithTag("lan_usage_list").performScrollToNode(hasContentDescription(requests))
        compose.onNodeWithContentDescription(requests).performClick()
    }

    private fun showUsageText(text: String, substring: Boolean = false) {
        val matcher = hasText(text, substring = substring)
        compose.onNodeWithTag("lan_usage_list").performScrollToNode(matcher)
        compose.onNode(matcher).assertIsDisplayed()
    }

    private fun showDetailText(text: String, substring: Boolean = false) {
        compose.onNode(hasText(text, substring = substring) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed()
    }

    private fun label(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val EXTENDED_SUMMARY = """{
            "calls":3,"errors":1,"input":64,"output":19,"cost":0.125,"unpriced":2,
            "cache_read":211,"cache_write":72,"reasoning":9,"bucket":"hour",
            "series":[
                {"time":"2026-11-20T03:00:00+13:00","input":45,"output":12},
                {"time":"2026-11-20T04:00:00+13:00","input":0,"output":0},
                {"time":"2026-11-20T05:00:00+13:00","input":19,"output":7}
            ],
            "agents":[{"id":"fixture-agent","name":"Fixture Agent","calls":3,"input":64,"output":19}],
            "models":[{"model":"fixture/group","provider":"fixture-provider","calls":2,"input":29,"output":7},
                      {"model":"fixture/group","provider":"second-provider","calls":1,"input":3,"output":1}]
        }"""

        const val EXTENDED_REQUESTS = """{"offset":0,"total":3,"rows":[
            {"t":"2026-11-20T05:45:00+13:00","agentName":"Fixture Agent","model":"fixture/routed",
             "providerName":"Fixture Provider","req":"fixture/requested","served":"fixture/served",
             "in":29,"out":7,"cache_read":72,"cache_write":0,"reasoning":3,"ms":420,"ttft_ms":82,
             "status":200,"priced":true,"cost":0.125},
            {"t":"2026-11-20T05:44:00+13:00","req":"fixture/group","model":"fixture/canceled",
             "served":"fixture/canceled","cache_unknown":true,"canceled":true},
            {"t":"2026-11-20T05:43:00+13:00","model":"fixture/failed","status":503}
        ]}"""

        const val OLD_SUMMARY = """{"calls":1,"errors":0,"input":24,"output":6,"cost":0,"unpriced":1}"""
        const val OLD_REQUESTS = """{"offset":0,"total":1,"rows":[
            {"t":"2026-11-20T05:00:00+08:00","agent":"legacy","model":"legacy/model"}
        ]}"""
    }
}
