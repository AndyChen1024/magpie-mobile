package com.andychen.magpie_android.usage

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andychen.magpie.shared.usage.UsageOrigin
import com.andychen.magpie_android.R
import com.andychen.magpie_android.ui.UsageScreen
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LanUsageRoute(viewModel: LanUsageViewModel, onOpenPhone: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var demo by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmingDisconnect by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && viewModel.state.value.mode == UsageConnectionMode.Manual) viewModel.retry()
    }

    fun requestPermissionOrRetry() {
        if (state.mode == UsageConnectionMode.Paired || hasLocalNetworkPermission(context)) viewModel.retry()
        else permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    DisposableEffect(viewModel, demo) {
        viewModel.setVisible(!demo)
        onDispose { viewModel.setVisible(false) }
    }

    if (demo) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.demo_data), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { demo = false }) { Text(stringResource(R.string.usage_live)) }
            }
            UsageScreen()
        }
    } else {
        LanUsagePage(
            state = state,
            onPeriod = viewModel::selectPeriod,
            onTab = viewModel::openTab,
            onRefresh = viewModel::refresh,
            onRetry = ::requestPermissionOrRetry,
            onMore = viewModel::loadMore,
            onEdit = { editing = true },
            onDisconnect = { confirmingDisconnect = true },
            onDemo = { demo = true },
            onSelectMode = viewModel::selectMode,
            onOpenPhone = onOpenPhone,
            onReconnect = viewModel::reconnect,
            onPermissionSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        )
    }

    if (editing && state.mode == UsageConnectionMode.Manual) {
        ConnectionDialog(
            initialOrigin = state.manualOrigin,
            hasSavedCertificate = state.hasCustomCertificate,
            onDismiss = { editing = false },
            onConnect = { origin, key, certificate, keepSavedCertificate ->
                viewModel.connect(origin, key, certificate, keepSavedCertificate)
                editing = false
                if (!hasLocalNetworkPermission(context)) {
                    permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
        )
    }

    if (confirmingDisconnect && state.mode == UsageConnectionMode.Manual) {
        AlertDialog(
            onDismissRequest = { confirmingDisconnect = false },
            title = { Text(stringResource(R.string.usage_disconnect)) },
            text = { Text(stringResource(R.string.usage_disconnect_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDisconnect = false
                    viewModel.disconnect()
                }) { Text(stringResource(R.string.usage_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDisconnect = false }) { Text(stringResource(R.string.usage_cancel)) }
            }
        )
    }
}

@Composable
private fun ConnectionDialog(
    initialOrigin: UsageOrigin?,
    hasSavedCertificate: Boolean,
    onDismiss: () -> Unit,
    onConnect: (UsageOrigin, String, X509Certificate?, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(initialOrigin?.value.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var selectedCertificate by remember { mutableStateOf<X509Certificate?>(null) }
    var retainSavedCertificate by remember { mutableStateOf(hasSavedCertificate) }
    var readingCertificate by remember { mutableStateOf(false) }
    var certificateError by remember { mutableStateOf(false) }
    val origin = UsageOrigin.parse(address)
    val keepsCertificate = retainSavedCertificate && origin == initialOrigin
    val trimmedKey = key.trim()
    val keyValid = trimmedKey.length in 1..4096 && trimmedKey.all { it.code in 33..126 }
    val selectCertificate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            readingCertificate = true
            scope.launch {
                try {
                    selectedCertificate = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use(::readUsageCertificate)
                            ?: error("Could not open certificate")
                    }
                    certificateError = false
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    certificateError = true
                } finally {
                    readingCertificate = false
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.usage_connect_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.usage_connect_hint))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.usage_address)) },
                    placeholder = { Text("https://magpie.local:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    isError = attempted && origin == null,
                    supportingText = if (attempted && origin == null) {
                        { Text(stringResource(R.string.usage_invalid_address)) }
                    } else null
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.usage_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = attempted && !keyValid,
                    supportingText = if (attempted && !keyValid) {
                        { Text(stringResource(R.string.usage_invalid_key)) }
                    } else null
                )
                Text(stringResource(R.string.usage_ca_hint), style = MaterialTheme.typography.bodySmall)
                if (selectedCertificate != null || keepsCertificate) {
                    Text(stringResource(R.string.usage_ca_selected), style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = { selectCertificate.launch(arrayOf("*/*")) }, enabled = !readingCertificate) {
                        Text(stringResource(R.string.usage_ca_import))
                    }
                    if (selectedCertificate != null || keepsCertificate || certificateError) {
                        TextButton(onClick = {
                            selectedCertificate = null
                            retainSavedCertificate = false
                            certificateError = false
                        }, enabled = !readingCertificate) {
                            Text(stringResource(R.string.usage_ca_remove))
                        }
                    }
                }
                if (certificateError) {
                    Text(stringResource(R.string.usage_ca_invalid), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    attempted = true
                    if (origin != null && keyValid) onConnect(origin, trimmedKey, selectedCertificate, keepsCertificate)
                },
                enabled = !readingCertificate && !certificateError
            ) { Text(stringResource(R.string.usage_connect)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.usage_cancel)) }
        }
    )
}
