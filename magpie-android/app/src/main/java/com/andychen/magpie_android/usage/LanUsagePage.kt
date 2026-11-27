package com.andychen.magpie_android.usage

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andychen.magpie.shared.usage.UsageFailure
import com.andychen.magpie.shared.usage.UsagePeriod
import com.andychen.magpie.shared.usage.UsagePhase
import com.andychen.magpie.shared.usage.UsageRow
import com.andychen.magpie.shared.usage.UsageTab
import com.andychen.magpie_android.R
import com.andychen.magpie_android.phone.PhoneStatus
import com.andychen.magpie_android.ui.EmptyPanel
import com.andychen.magpie_android.ui.MagpiePanel
import com.andychen.magpie_android.ui.Segments
import com.andychen.magpie_android.ui.rememberUsageFormats

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
    onPermissionSettings: () -> Unit,
    onSelectMode: (UsageConnectionMode) -> Unit = {},
    onOpenPhone: () -> Unit = {},
    onReconnect: () -> Unit = {}
) {
    val usage = state.usage
    val formats = rememberUsageFormats()
    val origin = usage.origin
    val manualOrigin = state.manualOrigin ?: origin
    val paired = state.mode == UsageConnectionMode.Paired
    val connected = if (paired) state.pairedOnline else origin != null
    key(state.mode, state.bindingRevision, origin, usage.period, usage.tab) {
        var detail by remember(usage.requests.failure) { mutableStateOf<UsageRow?>(null) }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("lan_usage_list"),
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
                Segments(
                    labels = listOf(stringResource(R.string.usage_source_paired), stringResource(R.string.usage_source_manual)),
                    selected = state.mode.ordinal,
                    onSelect = { onSelectMode(UsageConnectionMode.entries[it]) }
                )
            }
            if (paired) {
                item { PairedConnectionPanel(state, onOpenPhone, onReconnect) }
            } else {
                item {
                    MagpiePanel {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                manualOrigin?.value ?: stringResource(R.string.usage_disconnected),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row {
                                TextButton(onClick = onEdit, enabled = !state.working) {
                                    Text(stringResource(if (manualOrigin == null) R.string.usage_connect_title else R.string.usage_edit))
                                }
                                if (manualOrigin != null) {
                                    TextButton(onClick = onDisconnect, enabled = !state.working) {
                                        Text(stringResource(R.string.usage_disconnect))
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.storageError) item { EmptyPanel(stringResource(R.string.usage_storage_error)) }
            }
            if (state.working) {
                item { UsageLoading() }
                return@LazyColumn
            }
            if (!connected) {
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
                UsageTab.OVERVIEW -> {
                    val summary = usage.summary
                    when (summary.phase) {
                        UsagePhase.IDLE, UsagePhase.LOADING -> item { UsageLoading() }
                        UsagePhase.EMPTY -> item { EmptyPanel(stringResource(R.string.usage_empty)) }
                        UsagePhase.ERROR -> item { UsageError(summary.failure, paired, onRetry, onPermissionSettings) }
                        UsagePhase.READY -> summary.value?.let { usageOverview(it, formats) }
                    }
                }
                UsageTab.REQUESTS -> {
                    val requests = usage.requests
                    when (requests.phase) {
                        UsagePhase.IDLE, UsagePhase.LOADING -> item { UsageLoading() }
                        UsagePhase.EMPTY -> item { EmptyPanel(stringResource(R.string.usage_empty)) }
                        UsagePhase.ERROR -> item { UsageError(requests.failure, paired, onRetry, onPermissionSettings) }
                        UsagePhase.READY -> {
                            requests.total?.let { total ->
                                item { Text(stringResource(R.string.usage_rows_shown, requests.rows.size, total)) }
                            }
                            items(requests.rows) { row ->
                                UsageRequestItem(row, formats, onClick = { detail = row })
                            }
                            item {
                                when {
                                    requests.loadingMore -> UsageLoading()
                                    requests.moreFailure != null ->
                                        UsageError(requests.moreFailure, paired, onRetry, onPermissionSettings)
                                    requests.nextOffset != null ->
                                        TextButton(onClick = onMore) { Text(stringResource(R.string.usage_next_page)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        detail?.takeIf { it in usage.requests.rows }?.let { row ->
            UsageRequestDetail(row, formats, onDismiss = { detail = null })
        }
    }
}

@Composable
private fun PairedConnectionPanel(state: LanUsageState, onOpenPhone: () -> Unit, onReconnect: () -> Unit) {
    val status = state.phone.status
    MagpiePanel {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val message = when (status) {
                PhoneStatus.Unpaired -> stringResource(R.string.usage_pair_prompt)
                PhoneStatus.AuthorizationLost -> stringResource(R.string.usage_device_not_authorized)
                PhoneStatus.Connecting -> stringResource(R.string.phone_connecting)
                PhoneStatus.Connected, PhoneStatus.Disconnected -> if (state.pairedOnline) {
                    state.phone.macName ?: stringResource(R.string.phone_connected)
                } else {
                    stringResource(R.string.usage_paired_offline)
                }
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            when (status) {
                PhoneStatus.Unpaired, PhoneStatus.AuthorizationLost -> TextButton(onClick = onOpenPhone) {
                    Text(stringResource(R.string.usage_open_pairing))
                }
                PhoneStatus.Disconnected -> TextButton(onClick = onReconnect, enabled = state.phone.canRetry && !state.phone.scanning) {
                    Text(stringResource(R.string.phone_retry))
                }
                else -> Unit
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
private fun UsageLoading() {
    Text(stringResource(R.string.usage_loading))
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun UsageError(failure: UsageFailure?, paired: Boolean, onRetry: () -> Unit, onPermissionSettings: () -> Unit) {
    MagpiePanel {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(if (paired && failure == UsageFailure.NETWORK) R.string.usage_paired_network_error else failure.label))
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
    UsageFailure.DEVICE_NOT_AUTHORIZED -> R.string.usage_device_not_authorized
    UsageFailure.USAGE_PERMISSION_DENIED -> R.string.usage_paired_permission_denied
    UsageFailure.USAGE_UNAVAILABLE -> R.string.usage_paired_unavailable
    UsageFailure.INVALID_REQUEST -> R.string.usage_paired_invalid_request
    UsageFailure.UNSUPPORTED_REQUEST -> R.string.usage_paired_unsupported
    UsageFailure.SERVER, null -> R.string.usage_server_error
}
