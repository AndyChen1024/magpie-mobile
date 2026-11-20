package com.andychen.magpie_android.usage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andychen.magpie.shared.usage.UsageFailure
import com.andychen.magpie.shared.usage.UsagePeriod
import com.andychen.magpie.shared.usage.UsagePhase
import com.andychen.magpie.shared.usage.UsageRow
import com.andychen.magpie.shared.usage.UsageSummary
import com.andychen.magpie.shared.usage.UsageTab
import com.andychen.magpie_android.R
import com.andychen.magpie_android.ui.EmptyPanel
import com.andychen.magpie_android.ui.InfoDialog
import com.andychen.magpie_android.ui.InfoRow
import com.andychen.magpie_android.ui.MagpieDivider
import com.andychen.magpie_android.ui.MagpiePanel
import com.andychen.magpie_android.ui.Segments
import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@Composable
internal fun LanUsagePage(
    state: LanUsageState,
    onPeriod: (UsagePeriod) -> Unit,
    onTab: (UsageTab) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDemo: () -> Unit,
    onPermissionSettings: () -> Unit
) {
    val usage = state.usage
    val origin = usage.origin
    key(origin, usage.period, usage.tab) {
        var detail by remember { mutableStateOf<UsageRow?>(null) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.usage),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge
                )
            }
            item {
                MagpiePanel {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            origin?.value ?: stringResource(R.string.usage_disconnected),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row {
                            TextButton(onClick = onEdit, enabled = !state.working) {
                                Text(stringResource(if (origin == null) R.string.usage_connect_title else R.string.usage_edit))
                            }
                            if (origin != null) {
                                TextButton(onClick = onDisconnect, enabled = !state.working) {
                                    Text(stringResource(R.string.usage_disconnect))
                                }
                            }
                        }
                    }
                }
            }
            if (state.storageError) item { EmptyPanel(stringResource(R.string.usage_storage_error)) }
            if (state.working) {
                item { UsageLoading() }
                return@LazyColumn
            }
            if (origin == null) {
                item { TextButton(onClick = onDemo) { Text(stringResource(R.string.usage_show_demo)) } }
                return@LazyColumn
            }
            item {
                Segments(
                    labels = listOf(stringResource(R.string.overview), stringResource(R.string.requests)),
                    selected = usage.tab.ordinal,
                    onSelect = { onTab(UsageTab.entries[it]) }
                )
            }
            item { RangeControls(usage.period, onPeriod, onRefresh) }
            when (usage.tab) {
                UsageTab.OVERVIEW -> item {
                    val summary = usage.summary
                    when (summary.phase) {
                        UsagePhase.IDLE, UsagePhase.LOADING -> UsageLoading()
                        UsagePhase.EMPTY -> EmptyPanel(stringResource(R.string.usage_empty))
                        UsagePhase.ERROR -> UsageError(summary.failure, onRetry, onPermissionSettings)
                        UsagePhase.READY -> summary.value?.let { SummaryPanel(it) }
                    }
                }
                UsageTab.REQUESTS -> {
                    val requests = usage.requests
                    when (requests.phase) {
                        UsagePhase.IDLE, UsagePhase.LOADING -> item { UsageLoading() }
                        UsagePhase.EMPTY -> item { EmptyPanel(stringResource(R.string.usage_empty)) }
                        UsagePhase.ERROR -> item { UsageError(requests.failure, onRetry, onPermissionSettings) }
                        UsagePhase.READY -> {
                            requests.total?.let { total ->
                                item { Text(stringResource(R.string.usage_rows_shown, requests.rows.size, total)) }
                            }
                            items(requests.rows) { row ->
                                MagpiePanel {
                                    InfoRow(
                                        title = row.model ?: stringResource(R.string.usage_unavailable_value),
                                        subtitle = listOf(row.agent ?: stringResource(R.string.usage_unavailable_value),
                                            formatTime(row.timestamp)).joinToString(" · "),
                                        trailing = statusLabel(row),
                                        onClick = { detail = row }
                                    )
                                }
                            }
                            item {
                                when {
                                    requests.loadingMore -> UsageLoading()
                                    requests.moreFailure != null ->
                                        UsageError(requests.moreFailure, onRetry, onPermissionSettings)
                                    requests.nextOffset != null ->
                                        TextButton(onClick = onMore) { Text(stringResource(R.string.usage_next_page)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        detail?.let { row ->
            InfoDialog(stringResource(R.string.request_detail), onDismiss = { detail = null }) {
                DetailLine(R.string.request_time, formatTime(row.timestamp))
                DetailLine(R.string.agents, row.agent ?: stringResource(R.string.usage_unavailable_value))
                DetailLine(R.string.model, row.model ?: stringResource(R.string.usage_unavailable_value))
                DetailLine(R.string.request_status, statusLabel(row))
                DetailLine(R.string.request_cost, row.costUsd?.let(::usd) ?: stringResource(R.string.usage_price_unknown))
            }
        }
    }
}

@Composable
private fun RangeControls(period: UsagePeriod, onPeriod: (UsagePeriod) -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }) { Text(stringResource(period.label)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                UsagePeriod.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(item.label)) },
                        onClick = {
                            open = false
                            onPeriod(item)
                        }
                    )
                }
            }
        }
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.usage_refresh)) }
    }
}

@Composable
private fun SummaryPanel(value: UsageSummary) {
    MagpiePanel {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DetailLine(R.string.usage_call_count, value.calls.toString())
            DetailLine(R.string.usage_error_count, value.errors.toString())
            MagpieDivider()
            DetailLine(R.string.token, value.tokens.toString())
            DetailLine(R.string.input_tokens, value.input.toString())
            DetailLine(R.string.output_tokens, value.output.toString())
            MagpieDivider()
            DetailLine(R.string.usage_known_cost, usd(value.costUsd))
            if (value.unpriced > 0) {
                Text(stringResource(R.string.usage_unpriced_count, value.unpriced),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailLine(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f))
    }
}

@Composable
private fun UsageLoading() {
    Text(stringResource(R.string.usage_loading))
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun UsageError(failure: UsageFailure?, onRetry: () -> Unit, onPermissionSettings: () -> Unit) {
    MagpiePanel {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(failure.label))
            Row {
                TextButton(onClick = onRetry) {
                    Text(stringResource(if (failure == UsageFailure.LOCAL_PERMISSION) R.string.usage_permission_retry else R.string.usage_retry))
                }
                if (failure == UsageFailure.LOCAL_PERMISSION) {
                    TextButton(onClick = onPermissionSettings) { Text(stringResource(R.string.usage_system_settings)) }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(row: UsageRow): String =
    row.httpStatus?.let { "HTTP $it" } ?: stringResource(R.string.usage_unavailable_value)

private val UsagePeriod.label: Int get() = when (this) {
    UsagePeriod.TODAY -> R.string.range_today
    UsagePeriod.WEEK -> R.string.range_week
    UsagePeriod.MONTH -> R.string.range_month
}

private val UsageFailure?.label: Int get() = when (this) {
    UsageFailure.UNAUTHORIZED -> R.string.usage_unauthorized
    UsageFailure.RESTRICTED -> R.string.usage_restricted
    UsageFailure.FORBIDDEN -> R.string.usage_forbidden
    UsageFailure.LOCAL_PERMISSION -> R.string.usage_permission_denied
    UsageFailure.NETWORK -> R.string.usage_connection_failed
    UsageFailure.TLS -> R.string.usage_tls
    UsageFailure.REDIRECT -> R.string.usage_redirect
    UsageFailure.INVALID_DATA -> R.string.usage_invalid_data
    UsageFailure.CREDENTIAL -> R.string.usage_key_missing
    UsageFailure.SERVER, null -> R.string.usage_server_error
}

private fun usd(value: Double): String = NumberFormat.getCurrencyInstance(Locale.US).run {
    maximumFractionDigits = 10
    format(value)
}

private val displayTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private fun formatTime(timestamp: String): String = try {
    OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault()).format(displayTime)
} catch (_: DateTimeParseException) {
    timestamp
}
