package com.andychen.magpie_android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andychen.magpie_android.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class UsagePage(@field:StringRes val label: Int) {
    Overview(R.string.overview), Requests(R.string.requests),
    Sessions(R.string.sessions), Context(R.string.context)
}

private enum class UsageRange(@field:StringRes val label: Int, val days: Int) {
    Today(R.string.range_today, 1), Week(R.string.range_week, 7), Month(R.string.range_month, 30)
}

private val requestTime = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
private val chartDate = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)

@Composable
internal fun UsageScreen() {
    var tab by rememberSaveable { mutableStateOf(UsagePage.Overview) }
    var range by rememberSaveable { mutableStateOf(UsageRange.Today) }
    var showRange by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var detailIndex by rememberSaveable { mutableIntStateOf(-1) }
    val today = remember { LocalDate.now() }
    val allRequests = remember(today) { demoRequests(today) }
    val firstDate = today.minusDays(range.days - 1L)
    val requests = remember(allRequests, firstDate) {
        allRequests.withIndex().filter { (_, request) -> !request.time.toLocalDate().isBefore(firstDate) }
    }
    val totals = remember(requests) { requests.map { it.value }.totals() }

    Segments(UsagePage.entries.map { stringResource(it.label) }, tab.ordinal) {
        tab = UsagePage.entries[it]
    }
    if (tab == UsagePage.Overview || tab == UsagePage.Requests) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Row(
                    modifier = Modifier.clip(ControlShape)
                        .clickable(role = Role.Button) { showRange = true }
                        .heightIn(min = 48.dp)
                        .padding(start = 2.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(range.label), style = MaterialTheme.typography.titleMedium)
                    Icon(MagpieIcons.Down, null, modifier = Modifier.size(15.dp))
                }
                DropdownMenu(expanded = showRange, onDismissRequest = { showRange = false }) {
                    UsageRange.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.label)) },
                            onClick = {
                                range = option
                                showRange = false
                            }
                        )
                    }
                }
            }
            Text(money(totals.costCents), style = MaterialTheme.typography.titleLarge)
        }
    }
    when (tab) {
        UsagePage.Overview -> UsageOverview(totals, requests.map { it.value }, range, firstDate)
        UsagePage.Requests -> {
            SearchBox(query, stringResource(R.string.find_request)) { query = it }
            val search = query.trim()
            val matching = requests.filter { (_, request) ->
                request.agent.name.contains(search, ignoreCase = true) ||
                    request.model.name.contains(search, ignoreCase = true)
            }
            if (matching.isEmpty()) {
                EmptyPanel(stringResource(R.string.no_matches))
            } else {
                MagpiePanel {
                    matching.forEachIndexed { position, (index, request) ->
                        if (position > 0) MagpieDivider()
                        InfoRow(
                            title = request.model.name,
                            subtitle = "${request.agent.name} · ${request.time.format(requestTime)}",
                            icon = request.agent.icon,
                            trailing = stringResource(request.statusLabel),
                            onClick = { detailIndex = index }
                        )
                    }
                }
            }
        }
        UsagePage.Sessions -> SessionsScreen()
        UsagePage.Context -> EmptyPanel(stringResource(R.string.not_connected))
    }

    allRequests.getOrNull(detailIndex)?.let { request ->
        RequestDetail(request, onDismiss = { detailIndex = -1 })
    }
}

@Composable
private fun UsageOverview(
    totals: UsageTotals,
    requests: List<DemoRequest>,
    range: UsageRange,
    firstDate: LocalDate
) {
    val colors = MaterialTheme.colorScheme
    val reads = totals.cacheRead + totals.input
    val hitRate = if (reads > 0) totals.cacheRead.toDouble() / reads else 0.0
    Text(
        stringResource(R.string.usage_note),
        modifier = Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant
    )
    MagpiePanel {
        MetricRow(
            left = Metric(shortCount(totals.tokens), stringResource(R.string.token), tokenDetail(totals)),
            right = Metric(
                shortCount(totals.cacheRead),
                stringResource(R.string.cache_read),
                stringResource(R.string.cache_hit_rate, String.format(Locale.US, "%.1f%%", hitRate * 100))
            )
        )
        HorizontalDivider(color = colors.outlineVariant)
        MetricRow(
            left = Metric(
                shortCount(totals.reasoning),
                stringResource(R.string.reasoning),
                stringResource(R.string.included_in_output)
            ),
            right = Metric(
                totals.count.toString(),
                stringResource(R.string.calls),
                stringResource(R.string.failed_calls, totals.failures)
            )
        )
    }
    val buckets = remember(requests, range, firstDate) {
        if (range == UsageRange.Today) {
            val byHour = requests.groupBy { it.time.hour }
            (0..23).map { byHour[it].orEmpty().totals() }
        } else {
            val byDate = requests.groupBy { it.time.toLocalDate() }
            (0 until range.days).map { byDate[firstDate.plusDays(it.toLong())].orEmpty().totals() }
        }
    }
    val labels = if (range == UsageRange.Today) {
        listOf("00:00", "06:00", "12:00", "18:00", "23:00")
    } else {
        listOf(0, (range.days - 1) / 2, range.days - 1).map { firstDate.plusDays(it.toLong()).format(chartDate) }
    }
    TokenChart(buckets, labels)

    SectionLabel(stringResource(R.string.agents))
    MagpiePanel {
        requests.groupBy { it.agent }.mapValues { (_, entries) -> entries.totals() }
            .entries.sortedByDescending { it.value.tokens }
            .forEachIndexed { index, (agent, agentTotals) ->
                if (index > 0) MagpieDivider()
                UsageBreakdown(agent.name, agent.icon, agentTotals)
            }
    }
    SectionLabel(stringResource(R.string.model))
    MagpiePanel {
        requests.groupBy { it.model }.mapValues { (_, entries) -> entries.totals() }
            .entries.sortedByDescending { it.value.tokens }
            .forEachIndexed { index, (model, modelTotals) ->
                if (index > 0) MagpieDivider()
                UsageBreakdown(model.name, model.icon, modelTotals)
            }
    }
}

private data class Metric(val value: String, val label: String, val subtitle: String)

@Composable
private fun MetricRow(left: Metric, right: Metric) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        MetricCell(left, Modifier.weight(1f).fillMaxHeight())
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MetricCell(right, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun MetricCell(metric: Metric, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.heightIn(min = 90.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(metric.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(metric.label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(metric.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun TokenChart(buckets: List<UsageTotals>, labels: List<String>) {
    val colors = MaterialTheme.colorScheme
    val maxTokens = buckets.maxOf { it.tokens }.coerceAtLeast(1)
    val chartLabel = stringResource(R.string.token_chart)
    MagpiePanel {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                shortCount(maxTokens),
                Modifier.align(Alignment.End),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Canvas(
                Modifier.fillMaxWidth().height(78.dp).semantics { contentDescription = chartLabel }
            ) {
                val slot = size.width / buckets.size
                val barWidth = slot * 0.72f
                buckets.forEachIndexed { index, bucket ->
                    val left = slot * index + (slot - barWidth) / 2
                    val input = size.height * bucket.input / maxTokens
                    val output = size.height * bucket.output / maxTokens
                    drawRect(colors.primary.copy(alpha = 0.35f), Offset(left, size.height - input), Size(barWidth, input))
                    drawRect(colors.primary, Offset(left, size.height - input - output), Size(barWidth, output))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun UsageBreakdown(name: String, icon: Int, totals: UsageTotals) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BrandIcon(icon, Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.call_summary, totals.count, totals.failures),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Text(shortCount(totals.tokens), style = MaterialTheme.typography.labelLarge)
            Text(tokenDetail(totals), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text(money(totals.costCents), style = MaterialTheme.typography.labelLarge, color = colors.secondary)
    }
}

@Composable
private fun RequestDetail(request: DemoRequest, onDismiss: () -> Unit) {
    InfoDialog(stringResource(R.string.request_detail), onDismiss) {
        val fields = listOf(
            R.string.model to request.model.name,
            R.string.agents to request.agent.name,
            R.string.request_status to stringResource(request.statusLabel),
            R.string.request_time to request.time.format(requestTime),
            R.string.input_tokens to request.input.toString(),
            R.string.output_tokens to request.output.toString(),
            R.string.cache_read to request.cacheRead.toString(),
            R.string.latency to stringResource(R.string.duration_ms, request.latencyMs),
            R.string.request_cost to money(request.costCents)
        )
        fields.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(label), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, Modifier.weight(1f))
            }
        }
    }
}

private val DemoRequest.statusLabel: Int
    get() = if (failed) R.string.status_failed else R.string.status_success

@Composable
private fun tokenDetail(totals: UsageTotals): String =
    stringResource(R.string.io_tokens, shortCount(totals.input), shortCount(totals.output))

private fun shortCount(value: Int): String =
    if (value < 1_000) value.toString()
    else String.format(Locale.US, "%.1f", value / 1_000.0).removeSuffix(".0") + "K"

private fun money(cents: Int): String = String.format(Locale.US, "$%.2f", cents / 100.0)
