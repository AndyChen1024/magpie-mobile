package com.andychen.magpie.shared.usage

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonArray

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
        unpriced = root.count("unpriced") ?: return null
    )
}

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
    val time = row.text("t") ?: return null
    try {
        Instant.parse(time)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val priced = row.number("priced")?.booleanOrNull == true
    return UsageRow(
        timestamp = time,
        agent = row.text("agentName") ?: row.text("agent"),
        model = row.text("model"),
        httpStatus = row.number("status")?.intOrNull?.takeIf { it in 100..599 },
        costUsd = if (priced) row.money() ?: return null else null
    )
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
