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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andychen.magpie.shared.usage.UsageController
import com.andychen.magpie.shared.usage.UsageCredentials
import com.andychen.magpie.shared.usage.UsageHttpRequest
import com.andychen.magpie.shared.usage.UsageHttpResponse
import com.andychen.magpie.shared.usage.UsageOrigin
import com.andychen.magpie.shared.usage.UsageTransport
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
                            LanUsageState(state, working = false),
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
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("用量").performClick()
            waitFor("此密钥只能查看自己的请求。")
            compose.onNodeWithText("此密钥只能查看自己的请求。").assertIsDisplayed()
            compose.onNodeWithContentDescription("请求").performClick()
            waitFor("主机暂时无法提供用量，请稍后重试。")
            compose.onNodeWithText("重试").performClick()
            waitFor("own/model")
            compose.onNodeWithText("own/model").assertIsDisplayed()
            compose.onNodeWithText("刷新").performClick()
            waitFor("已显示 1 条，共 1 条")
            compose.onNodeWithText("已显示 1 条，共 1 条").assertIsDisplayed()
            compose.onAllNodesWithText("own/model").assertCountEquals(1)
            compose.runOnIdle { assertEquals(3, requests) }
        } finally {
            controller.close()
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
