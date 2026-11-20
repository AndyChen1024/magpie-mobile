package com.andychen.magpie.shared.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UsageProtocolTest {
    @Test
    fun normalizesOnlyHttpsGatewayOrigins() {
        assertEquals(
            UsageOrigin.parse("https://gateway.local"),
            UsageOrigin.parse(" HTTPS://Gateway.Local:00443/v1/ ")
        )
        assertEquals("https://127.0.0.1:4433", UsageOrigin.parse("https://127.0.0.1:4433/")?.value)
        assertEquals(
            UsageOrigin.parse("https://[fd00::1]:8443"),
            UsageOrigin.parse("https://[fd00:0:0:0:0:0:0:1]:8443/v1")
        )
        listOf(
            "http://gateway.local", "https://user:pass@gateway.local",
            "https://gateway.local/v1/models", "https://gateway.local?",
            "https://gateway.local/#", "https://gateway.local\\misleading",
            "https://-bad.local", "https://gateway.local:65536", "https://gateway.local:0",
            "https://[fd00:::1]", "https://[fd00:0:0:0:0:0:0:0:1]", "https://gateway.local/%76%31"
        ).forEach { assertNull(UsageOrigin.parse(it), it) }
    }

    @Test
    fun readsHostTotalsWithoutRoundingOrInferringFromRows() {
        val totals = assertNotNull(parseSummary(UsageFixtures.summary))
        assertEquals(3L, totals.calls)
        assertEquals(1L, totals.errors)
        assertEquals(150L, totals.tokens)
        assertEquals(0.125, totals.costUsd)
        assertEquals(1L, totals.unpriced)
        assertEquals(0.0, assertNotNull(parseSummary(UsageFixtures.zero)).costUsd)
        assertNull(parseSummary("""{"calls":0}"""))
        assertNull(parseSummary(UsageFixtures.zero.replace("\"input\":0", "\"input\":\"0\"")))
    }

    @Test
    fun handlesOptionalStatusAndPreservesPricedZero() {
        val original = UsageFixtures.row
        val priced = original.replace("\"priced\":false,\"cost\":8", "\"priced\":true,\"cost\":0")
            .replace("\"status\":0", "\"status\":200")
            .replace("\"agentName\":\"Demo Agent\"", "\"agentName\":\"\"")
        val first = assertNotNull(parsePage(UsageFixtures.page(0, 2, original), 0))
        assertEquals(1L, first.nextOffset)
        assertEquals("Demo Agent", first.rows.single().agent)
        assertNull(first.rows.single().httpStatus)
        assertNull(first.rows.single().costUsd)
        val last = assertNotNull(parsePage(UsageFixtures.page(1, 2, priced), 1))
        assertNull(last.nextOffset)
        assertEquals("demo", last.rows.single().agent)
        assertEquals(0.0, last.rows.single().costUsd)
        assertEquals(200, last.rows.single().httpStatus)
        assertNull(parsePage(UsageFixtures.page(1, 2, original), 0))
        assertNull(parsePage(UsageFixtures.page(0, 1, """{"model":"missing-time"}"""), 0))
        assertNull(assertNotNull(parsePage(UsageFixtures.page(1, 1), 50)).nextOffset)
    }

    @Test
    fun mapsAuthorizationAndRedirectWithoutFollowingIt() {
        val restricted = UsageHttpResponse(403, UsageFixtures.restricted)
        assertEquals(UsageFailure.RESTRICTED, restricted.httpFailure(UsageTab.OVERVIEW))
        assertEquals(UsageFailure.FORBIDDEN, restricted.httpFailure(UsageTab.REQUESTS))
        assertEquals(UsageFailure.FORBIDDEN, UsageHttpResponse(403, "{}").httpFailure(UsageTab.OVERVIEW))
        assertEquals(UsageFailure.UNAUTHORIZED, UsageHttpResponse(401, "{}").httpFailure(UsageTab.REQUESTS))
        assertEquals(UsageFailure.REDIRECT, UsageHttpResponse(302, "").httpFailure(UsageTab.OVERVIEW))
        assertEquals(UsageFailure.TLS, UsageHttpResponse(0, "", UsageFailure.TLS).httpFailure(UsageTab.OVERVIEW))
    }
}
