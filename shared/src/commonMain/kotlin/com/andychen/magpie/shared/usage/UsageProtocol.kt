package com.andychen.magpie.shared.usage

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal const val USAGE_PAGE_SIZE = 50

internal data class LedgerPage(val rows: List<UsageRow>, val offset: Long, val total: Long) {
    val nextOffset: Long? get() = (offset + rows.size).takeIf { rows.isNotEmpty() && it < total }
}

internal fun UsageHttpResponse.httpFailure(tab: UsageTab): UsageFailure? = failure ?: when (statusCode) {
    in 200..299 -> null
    in 300..399 -> UsageFailure.REDIRECT
    401 -> UsageFailure.UNAUTHORIZED
    403 -> {
        val code = ((jsonObject(body)?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.content
        if (tab == UsageTab.OVERVIEW && code == "gateway_key_held") UsageFailure.RESTRICTED
        else UsageFailure.FORBIDDEN
    }
    else -> UsageFailure.SERVER
}

internal fun parseSummary(body: String): UsageSummary? {
    val root = jsonObject(body) ?: return null
    val input = root.count("input") ?: return null
    val output = root.count("output") ?: return null
    if (input > Long.MAX_VALUE - output) return null
    return UsageSummary(
        calls = root.count("calls") ?: return null,
        errors = root.count("errors") ?: return null,
        input = input,
        output = output,
        costUsd = root.money() ?: return null,
        unpriced = root.count("unpriced") ?: return null,
        cacheRead = root.count("cache_read"),
        cacheWrite = root.count("cache_write"),
        cacheUnknown = root.count("cache_unknown"),
        reasoning = root.count("reasoning"),
        bucket = root.text("bucket")?.takeIf { it in setOf("hour", "day", "week") },
        series = parseSeries(root["series"]),
        agents = readGroups(root["agents"], "id").take(5).toList(),
        models = parseModelGroups(root["models"])
    )
}

private fun parseSeries(element: JsonElement?): List<UsagePoint> {
    val series = element as? JsonArray ?: return emptyList()
    if (series.size > 31) return emptyList()
    return series.map {
        val point = it as? JsonObject ?: return emptyList()
        val time = point.timestamp("time") ?: return emptyList()
        val input = point.count("input") ?: return emptyList()
        val output = point.count("output") ?: return emptyList()
        if (usageTokenSum(input, output) == null) return emptyList()
        UsagePoint(time, input, output)
    }
}

private fun readGroups(element: JsonElement?, fallback: String): Sequence<UsageGroup> {
    val groups = element as? JsonArray ?: return emptySequence()
    return groups.asSequence().mapNotNull {
        val group = it as? JsonObject ?: return@mapNotNull null
        val name = group.text("name") ?: group.text(fallback) ?: return@mapNotNull null
        val calls = group.count("calls") ?: return@mapNotNull null
        val input = group.count("input") ?: return@mapNotNull null
        val output = group.count("output") ?: return@mapNotNull null
        if (usageTokenSum(input, output) == null) return@mapNotNull null
        UsageGroup(name, calls, input, output)
    }
}

private fun parseModelGroups(element: JsonElement?): List<UsageGroup> =
    readGroups(element, "model").groupBy(UsageGroup::name).mapNotNull { (name, rows) ->
        var calls = 0L
        var input = 0L
        var output = 0L
        for (row in rows) {
            calls = usageTokenSum(calls, row.calls) ?: return@mapNotNull null
            input = usageTokenSum(input, row.input) ?: return@mapNotNull null
            output = usageTokenSum(output, row.output) ?: return@mapNotNull null
        }
        if (usageTokenSum(input, output) == null) return@mapNotNull null
        UsageGroup(name, calls, input, output)
    }.sortedWith(compareByDescending(UsageGroup::tokens).thenByDescending(UsageGroup::calls)).take(5)

internal fun parsePage(body: String, requestedOffset: Long): LedgerPage? {
    val root = jsonObject(body) ?: return null
    val offset = root.count("offset") ?: return null
    val total = root.count("total") ?: return null
    val rows = root["rows"] as? JsonArray ?: return null
    if (offset > total || rows.size > USAGE_PAGE_SIZE || rows.size > total - offset ||
        offset != requestedOffset && !(rows.isEmpty() && offset == total && offset < requestedOffset)
    ) return null
    return LedgerPage(rows.map { parseRow(it) ?: return null }, offset, total)
}

private fun parseRow(element: JsonElement): UsageRow? {
    val row = element as? JsonObject ?: return null
    val time = row.timestamp("t") ?: return null
    val priced = row.number("priced")?.booleanOrNull == true
    val status = row.number("status")?.intOrNull
    val canceled = row.number("canceled")?.booleanOrNull == true
    return UsageRow(
        timestamp = time,
        agent = row.text("agentName") ?: row.text("agent"),
        model = row.text("model"),
        httpStatus = status?.takeIf { it in 100..599 },
        costUsd = if (priced) row.money() ?: return null else null,
        provider = row.text("providerName") ?: row.text("provider"),
        requestedModel = row.text("req"),
        servedModel = row.text("served"),
        input = row.count("in"),
        output = row.count("out"),
        cacheRead = row.count("cache_read"),
        cacheWrite = row.count("cache_write"),
        cacheUnknown = row.number("cache_unknown")?.booleanOrNull == true,
        reasoning = row.count("reasoning"),
        durationMillis = row.count("ms")?.takeIf { it > 0 },
        firstTokenMillis = row.count("ttft_ms")?.takeIf { it > 0 },
        canceled = canceled,
        failed = !canceled && (row.number("failed")?.booleanOrNull == true ||
            (status != null && status >= 400) || row.text("err") != null)
    )
}

private fun JsonObject.timestamp(name: String): String? {
    val time = text(name) ?: return null
    return try {
        Instant.parse(time)
        time
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun jsonObject(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonObject.number(name: String) = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }
private fun JsonObject.count(name: String) = number(name)?.longOrNull?.takeIf { it >= 0 }
private fun JsonObject.money() = number("cost")?.doubleOrNull?.takeIf { it.isFinite() }
private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
