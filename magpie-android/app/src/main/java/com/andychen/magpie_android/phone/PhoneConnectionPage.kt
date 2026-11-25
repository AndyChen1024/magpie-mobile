package com.andychen.magpie_android.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andychen.magpie_android.R
import com.google.zxing.DecodeHintType
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class PhoneScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}

@Composable
internal fun PhoneConnectionPage(model: PhoneConnectionViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val code = result.contents
        if (code != null) {
            model.scan(code)
        } else if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
            model.cameraDenied()
        }
    }
    val context = LocalContext.current
    val scanPrompt = stringResource(R.string.phone_scan_prompt)
    val status = when (state.status) {
        PhoneStatus.Unpaired -> R.string.phone_unpaired
        PhoneStatus.Connecting -> R.string.phone_connecting
        PhoneStatus.Connected -> R.string.phone_connected
        PhoneStatus.Disconnected -> R.string.phone_disconnected
        PhoneStatus.AuthorizationLost -> R.string.phone_authorization_lost
    }
    val problem = when (state.problem) {
        PhoneProblem.InvalidCode -> R.string.phone_invalid_code
        PhoneProblem.ExpiredCode -> R.string.phone_expired_code
        PhoneProblem.CameraDenied -> R.string.phone_camera_denied
        PhoneProblem.RelayUnavailable -> R.string.phone_relay_unavailable
        PhoneProblem.Protocol -> R.string.phone_protocol_error
        PhoneProblem.Storage -> R.string.phone_storage_error
        PhoneProblem.DeviceLimit -> R.string.phone_device_limit
        null -> null
    }
    Card(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(status), Modifier.testTag("phone_status"), style = MaterialTheme.typography.titleMedium)
            state.macName?.let {
                Text(it, Modifier.testTag("phone_mac_name"), style = MaterialTheme.typography.headlineSmall)
            }
            if (state.status == PhoneStatus.Unpaired && problem == null) {
                Text(stringResource(R.string.phone_instructions))
            }
            problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (state.status == PhoneStatus.Connecting || state.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.canScan) {
                Button(
                    modifier = Modifier.testTag("phone_scan"),
                    onClick = {
                        scanner.launch(ScanOptions()
                            .setCaptureActivity(PhoneScanActivity::class.java)
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(scanPrompt)
                            .setBeepEnabled(false)
                            .addExtra(DecodeHintType.TRY_HARDER.name, true)
                            .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false))
                    }
                ) {
                    Text(stringResource(if (state.status == PhoneStatus.AuthorizationLost) R.string.phone_pair_again else R.string.phone_scan))
                }
            }
            if (state.problem == PhoneProblem.CameraDenied) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) {
                    Text(stringResource(R.string.phone_app_settings))
                }
            }
            if (state.status == PhoneStatus.Connected || state.canRetry) {
                OutlinedButton(
                    onClick = model::readOrRetry,
                    enabled = !state.working,
                    modifier = Modifier.testTag("phone_read")
                ) {
                    Text(stringResource(if (state.canRetry) R.string.phone_retry else R.string.phone_read_mac))
                }
            }
        }
    }
}
