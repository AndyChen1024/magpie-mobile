package com.andychen.magpie_android.usage

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andychen.magpie.shared.usage.UsageGroup
import com.andychen.magpie.shared.usage.UsagePoint
import com.andychen.magpie.shared.usage.UsageSummary
import com.andychen.magpie_android.R
import com.andychen.magpie_android.ui.InfoDialog
import com.andychen.magpie_android.ui.MagpieDivider
import com.andychen.magpie_android.ui.MagpiePanel
import com.andychen.magpie_android.ui.UsageFormats
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal fun LazyListScope.usageOverview(value: UsageSummary, formats: UsageFormats) {
    item { SummaryPanel(value, formats) }
    val bucket = value.bucket
    if (value.series.isNotEmpty() && (bucket == "hour" || bucket == "day")) {
        item { TokenTrend(value.series, byHour = bucket == "hour", formats) }
    }
    if (value.reasoning != null || value.cacheRead != null || value.cacheWrite != null) {
        item { TokenDetails(value, formats) }
    }
    if (value.agents.isNotEmpty()) {
        item { UsageGroups(R.string.usage_top_agents, value.agents, formats) }
    }
    if (value.models.isNotEmpty()) {
        item { UsageGroups(R.string.usage_top_models, value.models, formats) }
    }
}

@Composable
private fun SummaryPanel(value: UsageSummary, formats: UsageFormats) {
    MagpiePanel {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryMetric(
                    R.string.usage_total_tokens,
                    formats.count(value.tokens),
                    stringResource(R.string.io_tokens, formats.count(value.input), formats.count(value.output)),
                    Modifier.weight(1.4f)
                )
                SummaryMetric(
                    R.string.usage_call_count,
                    formats.count(value.calls),
                    stringResource(R.string.usage_error_count_value, formats.count(value.errors)),
                    Modifier.weight(1f)
                )
            }
            MagpieDivider()
            val knownCost = if (value.costUsd == 0.0 && value.unpriced > 0) {
                stringResource(R.string.usage_cost_unavailable)
            } else {
                formats.usd(value.costUsd)
            }
            UsageValue(R.string.usage_known_cost, knownCost)
            if (value.unpriced > 0) {
                val quantity = value.unpriced.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                Text(
                    pluralStringResource(R.plurals.usage_unpriced_count, quantity, value.unpriced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(@StringRes label: Int, number: String, detail: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Text(number, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TokenDetails(value: UsageSummary, formats: UsageFormats) {
    MagpiePanel {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageHeading(stringResource(R.string.usage_token_breakdown))
            value.cacheRead?.let { UsageValue(R.string.cache_read, formats.count(it)) }
            value.cacheWrite?.let { UsageValue(R.string.usage_cache_write, formats.count(it)) }
            value.cacheHitRate?.let { UsageValue(R.string.usage_cache_hit_rate, formats.percent(it)) }
            value.reasoning?.let { UsageValue(R.string.usage_reasoning_in_output, formats.count(it)) }
        }
    }
}

@Composable
private fun TokenTrend(series: List<UsagePoint>, byHour: Boolean, formats: UsageFormats) {
    val peak = remember(series) { series.maxOf { it.tokens } }
    val colors = MaterialTheme.colorScheme
    val title = stringResource(if (byHour) R.string.usage_trend_hour else R.string.usage_trend_day)
    var showValues by remember(series, byHour) { mutableStateOf(false) }
    MagpiePanel {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageHeading(title)
            Text(stringResource(R.string.usage_host_time), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(
                formats.count(peak),
                Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            // Each slot is one host bucket, including buckets with no usage.
            Canvas(Modifier.fillMaxWidth().height(88.dp)) {
                val slot = size.width / series.size
                val barWidth = slot * 0.72f
                val scale = peak.coerceAtLeast(1).toDouble()
                series.forEachIndexed { index, point ->
                    val left = slot * index + (slot - barWidth) / 2f
                    val input = (size.height * (point.input / scale)).toFloat()
                    val output = (size.height * (point.output / scale)).toFloat()
                    drawRect(colors.onSurfaceVariant, Offset(left, size.height - input), Size(barWidth, input))
                    drawRect(colors.primary, Offset(left, size.height - input - output), Size(barWidth, output))
                }
                val baseline = size.height - 0.5f
                drawLine(colors.outline, Offset(0f, baseline), Offset(size.width, baseline))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val start = bucketLabel(series.first().timestamp, byHour, formats)
                val end = bucketLabel(series.last().timestamp, byHour, formats)
                Text(start, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (series.size > 1) {
                    Text(end, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TrendLegend(R.string.input_tokens, colors.onSurfaceVariant)
                TrendLegend(R.string.output_tokens, colors.primary)
            }
            TextButton(onClick = { showValues = true }) { Text(stringResource(R.string.usage_trend_values)) }
        }
    }
    if (showValues) {
        InfoDialog(title, onDismiss = { showValues = false }) {
            series.forEachIndexed { index, point ->
                if (index > 0) MagpieDivider()
                Text(bucketLabel(point.timestamp, byHour, formats, detailed = true), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.io_tokens, formats.count(point.input), formats.count(point.output)))
            }
        }
    }
}

@Composable
private fun TrendLegend(@StringRes label: Int, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

private val hourLabel = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

private fun bucketLabel(timestamp: String, byHour: Boolean, formats: UsageFormats, detailed: Boolean = false): String = try {
    val time = OffsetDateTime.parse(timestamp)
    when {
        detailed && byHour -> "${formats.date(time)} ${hourLabel.format(time)} ${time.offset.id}"
        detailed -> "${formats.date(time)} ${time.offset.id}"
        byHour -> hourLabel.format(time)
        else -> formats.date(time)
    }
} catch (_: DateTimeParseException) {
    timestamp
}

@Composable
private fun UsageGroups(@StringRes title: Int, groups: List<UsageGroup>, formats: UsageFormats) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UsageHeading(stringResource(title), Modifier.padding(horizontal = 4.dp))
        MagpiePanel {
            groups.forEachIndexed { index, group ->
                if (index > 0) MagpieDivider()
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(group.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.usage_group_counts, formats.count(group.tokens), formats.count(group.calls)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun UsageHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
}

@Composable
internal fun UsageValue(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(label), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
