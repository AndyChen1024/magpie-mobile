package com.andychen.magpie_android.usage

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andychen.magpie.shared.usage.UsageRow
import com.andychen.magpie_android.R
import com.andychen.magpie_android.ui.InfoDialog
import com.andychen.magpie_android.ui.MagpieDivider
import com.andychen.magpie_android.ui.MagpieIcons
import com.andychen.magpie_android.ui.MagpiePanel
import com.andychen.magpie_android.ui.UsageFormats
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

@Composable
internal fun UsageRequestItem(row: UsageRow, formats: UsageFormats, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val outcome = requestOutcome(row)
    val http = row.httpStatus?.let { stringResource(R.string.usage_http_status, it) }
    val status = when {
        outcome != null && http != null -> stringResource(R.string.call_summary, stringResource(outcome), http)
        outcome != null -> stringResource(outcome)
        http != null -> http
        else -> stringResource(R.string.usage_http_missing)
    }
    val metrics = listOfNotNull(
        row.tokens?.let { stringResource(R.string.usage_token_count, formats.count(it)) },
        row.costUsd?.let(formats::usd),
        row.durationMillis?.let { durationLabel(it, formats) }
    ).joinToString(" · ")
    MagpiePanel {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
                onClickLabel = stringResource(R.string.request_detail), role = Role.Button, onClick = onClick
            ).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    row.servedModel ?: row.model ?: row.requestedModel ?: stringResource(R.string.usage_model_missing),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(row.agent, row.provider, formatRequestTime(row.timestamp, formats)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Text(
                    if (metrics.isEmpty()) status else stringResource(R.string.call_summary, status, metrics),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outcome == R.string.status_failed) colors.error else colors.onSurfaceVariant
                )
            }
            Icon(MagpieIcons.Next, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun UsageRequestDetail(row: UsageRow, formats: UsageFormats, onDismiss: () -> Unit) {
    val unavailable = stringResource(R.string.usage_unavailable_value)
    val sent = row.model
    val served = row.servedModel
    val requested = row.requestedModel
    val outcome = requestOutcome(row)
    val http = row.httpStatus?.let { stringResource(R.string.usage_http_status, it) }
    InfoDialog(stringResource(R.string.request_detail), onDismiss = onDismiss) {
        UsageHeading(stringResource(R.string.usage_request_routing))
        UsageValue(R.string.request_time, formatRequestTime(row.timestamp, formats))
        UsageValue(R.string.agents, row.agent ?: unavailable)
        row.provider?.let { UsageValue(R.string.usage_provider, it) }
        requested?.let { UsageValue(R.string.usage_requested_model, it) }
        if (requested == null && served == null) {
            UsageValue(R.string.model, sent ?: unavailable)
        } else {
            sent?.let { UsageValue(R.string.usage_sent_model, it) }
            served?.let { UsageValue(R.string.usage_served_model, it) }
        }
        if (outcome != null) {
            UsageValue(R.string.request_status, stringResource(outcome))
            UsageValue(R.string.usage_http_code, http ?: stringResource(R.string.usage_http_missing))
        } else {
            UsageValue(R.string.request_status, http ?: stringResource(R.string.usage_http_missing))
        }
        MagpieDivider()
        UsageHeading(stringResource(R.string.usage_request_metering))
        row.input?.let { UsageValue(R.string.input_tokens, formats.count(it)) }
        row.output?.let { UsageValue(R.string.output_tokens, formats.count(it)) }
        row.reasoning?.let { UsageValue(R.string.usage_reasoning_in_output, formats.count(it)) }
        if (row.cacheUnknown) {
            Text(stringResource(R.string.usage_cache_not_reported), style = MaterialTheme.typography.bodySmall)
        } else {
            row.cacheRead?.let { UsageValue(R.string.cache_read, formats.count(it)) }
            row.cacheWrite?.let { UsageValue(R.string.usage_cache_write, formats.count(it)) }
        }
        UsageValue(R.string.request_cost, row.costUsd?.let(formats::usd) ?: stringResource(R.string.usage_price_unknown))
        if (row.durationMillis != null || row.firstTokenMillis != null) {
            MagpieDivider()
            UsageHeading(stringResource(R.string.usage_request_timing))
            row.durationMillis?.let { UsageValue(R.string.usage_total_duration, durationLabel(it, formats)) }
            row.firstTokenMillis?.let { UsageValue(R.string.usage_first_token, durationLabel(it, formats)) }
        }
    }
}

@StringRes
private fun requestOutcome(row: UsageRow): Int? = when {
    row.canceled -> R.string.usage_status_canceled
    row.failed || (row.httpStatus ?: 0) >= 400 -> R.string.status_failed
    else -> null
}

@Composable
private fun durationLabel(ms: Long, formats: UsageFormats): String =
    stringResource(R.string.usage_duration_value, formats.count(ms))

private fun formatRequestTime(timestamp: String, formats: UsageFormats): String = try {
    formats.dateTime(OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault()))
} catch (_: DateTimeParseException) {
    timestamp
}
