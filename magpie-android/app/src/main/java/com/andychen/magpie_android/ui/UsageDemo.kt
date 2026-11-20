package com.andychen.magpie_android.ui

import java.time.LocalDate
import java.time.LocalDateTime

internal data class DemoRequest(
    val time: LocalDateTime,
    val agent: DemoAgent,
    val model: DemoModel,
    val input: Int,
    val output: Int,
    val cacheRead: Int,
    val reasoning: Int,
    val costCents: Int,
    val latencyMs: Int,
    val failed: Boolean
)

internal data class UsageTotals(
    val input: Int,
    val output: Int,
    val cacheRead: Int,
    val reasoning: Int,
    val costCents: Int,
    val count: Int,
    val failures: Int
) {
    val tokens: Int get() = input + output
}

internal fun List<DemoRequest>.totals(): UsageTotals = UsageTotals(
    input = sumOf { it.input },
    output = sumOf { it.output },
    cacheRead = sumOf { it.cacheRead },
    reasoning = sumOf { it.reasoning },
    costCents = sumOf { it.costCents },
    count = size,
    failures = count { it.failed }
)

internal fun demoRequests(today: LocalDate): List<DemoRequest> = buildList {
    for (daysAgo in 0..29) {
        val date = today.minusDays(daysAgo.toLong())
        val hours = if (daysAgo == 0) listOf(17, 15, 13, 11, 10, 9, 8, 7) else listOf(11)
        hours.forEachIndexed { index, hour ->
            val seed = daysAgo + index
            val agent = demoAgents[seed % demoAgents.size]
            val failed = seed % 7 == 4
            val input = if (failed) 0 else 11_000 + (seed * 4_313) % 19_000
            val output = if (failed) 0 else input / 2
            add(
                DemoRequest(
                    time = date.atTime(hour, (seed * 13) % 60),
                    agent = agent,
                    model = demoModels[agent.defaultModel],
                    input = input,
                    output = output,
                    cacheRead = input * (2 + seed % 3),
                    reasoning = output / 5,
                    costCents = input / 120,
                    latencyMs = 540 + (seed * 1_207) % 4_000,
                    failed = failed
                )
            )
        }
    }
}
