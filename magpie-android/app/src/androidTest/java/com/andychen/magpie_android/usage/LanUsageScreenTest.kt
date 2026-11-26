package com.andychen.magpie_android.usage

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        val origin = checkNotNull(UsageOrigin.parse("https://fixture.local"))
        val controller = UsageController(
            transport = object : UsageTransport {
                override suspend fun get(request: UsageHttpRequest): UsageHttpResponse =
                    if ("/usage/requests?" in request.url) {
                        requests++
                        assertEquals("Bearer demo-key", request.authorization)
                        if (requests == 1) UsageHttpResponse(503, "") else UsageHttpResponse(
                            200, """{"rows":[{"t":"2026-11-20T10:00:00+08:00","agent":"demo","model":"own/model"}],"offset":0,"total":1}"""
                        )
                    } else UsageHttpResponse(403, """{"error":{"code":"gateway_key_held"}}""")
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
        } finally {
            controller.close()
        }
    }

    private fun label(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
