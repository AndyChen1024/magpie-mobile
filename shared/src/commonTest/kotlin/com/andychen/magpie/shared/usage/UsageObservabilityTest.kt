package com.andychen.magpie.shared.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageObservabilityTest {
    @Test
    fun readsOptionalTotalsAndPreservesEmptyBucketsAndServerOrder() {
        val totals = summary(
            """"cache_read":0,"cache_write":12,"reasoning":9,"bucket":"hour",
                "series":[{"time":"2026-11-01T01:00:00-07:00","input":0,"output":0},
                          {"time":"2026-11-01T01:00:00-08:00","input":3,"output":4}],
                "agents":[{"id":"codex","calls":1,"input":3,"output":4,"provider":"ignored"}],
                "models":[{"name":"GPT","provider":"first","calls":1,"input":3,"output":4},
                          {"model":"GPT","provider":"second","calls":1,"input":1,"output":2},
                          {"name":"third","calls":1,"input":0,"output":1},
                          {"name":"fourth","calls":1,"input":0,"output":1},
                          {"name":"fifth","calls":1,"input":0,"output":1},
                          {"name":"sixth","calls":1,"input":0,"output":1}]"""
        )
        assertEquals(0L, totals.cacheRead)
        assertEquals(12L, totals.cacheWrite)
        assertNull(totals.cacheUnknown)
        assertEquals(9L, totals.reasoning)
        assertEquals("hour", totals.bucket)
        assertEquals(listOf("2026-11-01T01:00:00-07:00", "2026-11-01T01:00:00-08:00"), totals.series.map { it.timestamp })
        assertEquals(listOf(0L, 7L), totals.series.map { it.tokens })
        assertEquals(UsageGroup("codex", 1, 3, 4), totals.agents.single())
        assertEquals(listOf("GPT", "third", "fourth", "fifth", "sixth"), totals.models.map { it.name })
        assertEquals(UsageGroup("GPT", 2, 4, 6), totals.models.first())
        assertEquals(listOf(10L, 1L, 1L, 1L, 1L), totals.models.map { it.tokens })
    }

    @Test
    fun cacheHitRateUsesOnlyMeasuredPromptTokens() {
        val value = summary(""""cache_read":30,"cache_write":50,"cache_unknown":50""")
        assertEquals(0.2, value.cacheHitRate)
        assertNull(value.copy(cacheUnknown = 200).cacheHitRate)
        assertNull(value.copy(cacheWrite = null).cacheHitRate)
        assertEquals(0.0, value.copy(cacheRead = 0).cacheHitRate)
    }

    @Test
    fun keepsBaseSummaryWhenExtensionsAreMissingMalformedOrOverflowing() {
        val legacy = assertNotNull(parseSummary(UsageFixtures.summary))
        assertEquals(UsageSummary(3, 1, 120, 30, 0.125, 1), legacy)
        val good = """{"time":"2026-11-01T00:00:00Z","input":0,"output":0}"""
        for (series in listOf(
            "$good,{}",
            "$good,${good.replace("\"input\":0", "\"input\":${Long.MAX_VALUE}").replace("\"output\":0", "\"output\":1")}",
            List(32) { good }.joinToString(",")
        )) {
            val value = summary(
                """"cache_read":"4","cache_write":-1,"cache_unknown":0,"reasoning":1.5,
                    "bucket":"unknown","series":[$series],"agents":{},
                    "models":[{"name":"overflow","calls":1,"input":${Long.MAX_VALUE},"output":1},
                              {"id":"private-host","calls":1,"input":0,"output":1},
                              {"model":"ok","calls":0,"input":0,"output":0}]"""
            )
            assertEquals(150L, value.tokens)
            assertNull(value.bucket)
            assertNull(value.cacheRead)
            assertNull(value.cacheWrite)
            assertEquals(0L, value.cacheUnknown)
            assertNull(value.reasoning)
            assertTrue(value.series.isEmpty())
            assertTrue(value.agents.isEmpty())
            assertEquals(listOf("ok"), value.models.map { it.name })
        }
    }

    @Test
    fun readsRequestDetailsAndUsesCancellationBeforeFailure() {
        val details = row(
            """"providerName":"Display","provider":"provider-id","req":"asked","served":"answered",
                "in":0,"out":29,"cache_read":0,"cache_write":4,"reasoning":8,"ms":95,"ttft_ms":45,"err":"failure""""
        )
        assertEquals("Display", details.provider)
        assertEquals("asked", details.requestedModel)
        assertEquals("answered", details.servedModel)
        assertEquals(0L, details.input)
        assertEquals(29L, details.output)
        assertEquals(29L, details.tokens)
        assertEquals(0L, details.cacheRead)
        assertEquals(4L, details.cacheWrite)
        assertFalse(details.cacheUnknown)
        assertTrue(row(""""cache_unknown":true""").cacheUnknown)
        assertEquals(8L, details.reasoning)
        assertEquals(95L, details.durationMillis)
        assertEquals(45L, details.firstTokenMillis)
        assertTrue(details.failed)
        assertTrue(row(""""failed":true""").failed)
        assertTrue(row(""""failed":false""", status = 503).failed)
        val canceled = row(""""canceled":true,"failed":true,"err":"failure"""", status = 499)
        assertTrue(canceled.canceled)
        assertFalse(canceled.failed)
    }

    @Test
    fun degradesMalformedRequestDetailsAndChecksTokenSums() {
        val legacy = assertNotNull(parsePage(UsageFixtures.page(0, 1, UsageFixtures.row), 0)).rows.single()
        assertEquals(UsageRow("2026-11-20T10:00:00.125+08:00", "Demo Agent", "demo/model", null, null), legacy)
        val malformed = row(
            """"providerName":{},"provider":"fallback","req":true,"served":" ","in":-1,"out":"2",
                "cache_read":-1,"cache_write":"0","reasoning":1.1,"ms":0,"ttft_ms":-2,
                "canceled":"true","failed":"true","err":["ignored"]"""
        )
        assertEquals(legacy.copy(provider = "fallback"), malformed)
        assertNull(row(""""in":2""").tokens)
        assertNull(row(""""in":${Long.MAX_VALUE},"out":1""").tokens)
        assertEquals(Long.MAX_VALUE, row(""""in":${Long.MAX_VALUE},"out":0""").tokens)
    }

    private fun summary(details: String): UsageSummary =
        assertNotNull(parseSummary(UsageFixtures.summary.dropLast(1) + ",$details}"))

    private fun row(details: String, status: Int = 0): UsageRow {
        val raw = UsageFixtures.row.replace("\"status\":0", "\"status\":$status").dropLast(1) + ",$details}"
        return assertNotNull(parsePage(UsageFixtures.page(0, 1, raw), 0)).rows.single()
    }
}
