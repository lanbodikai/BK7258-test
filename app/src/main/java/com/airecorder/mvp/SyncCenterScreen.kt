package com.airecorder.mvp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.airecorder.mvp.core.ble.BleConnectionState
import com.airecorder.mvp.core.ble.LivePreviewState
import com.airecorder.mvp.core.ble.isActive
import com.airecorder.mvp.core.wifi.RecorderWifiJoinPhase
import com.airecorder.mvp.sync.SyncState
import com.airecorder.mvp.sync.RecorderRecoveryState
import com.airecorder.mvp.sync.isRunning
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncCenterScreen(viewModel: MainViewModel, nav: NavHostController) {
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val transport by viewModel.syncTransport.collectAsStateWithLifecycle()
    val livePreview by viewModel.livePreview.collectAsStateWithLifecycle()
    val recorderStatus by viewModel.status.collectAsStateWithLifecycle()
    val recorderRecovery by viewModel.recorderRecovery.collectAsStateWithLifecycle()
    val currentSyncState = syncState
    val context = LocalContext.current
    val recorderConnected = connection is BleConnectionState.Connected
    val wifiJoinPhase = (currentSyncState as? SyncState.JoiningRecorderWifi)?.phase
    var openedManualPanelForRequest by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(wifiJoinPhase) {
        if (wifiJoinPhase != RecorderWifiJoinPhase.WAITING_FOR_MANUAL_SELECTION) {
            openedManualPanelForRequest = false
        } else if (!openedManualPanelForRequest) {
            openedManualPanelForRequest = true
            openRecorderWifiPanel(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                actions = {
                    if (currentSyncState is SyncState.Complete || currentSyncState is SyncState.Cancelled || currentSyncState is SyncState.Failed) {
                        IconButton(onClick = viewModel::clearSyncOutcome) {
                            Icon(Icons.Filled.Close, "Clear sync result")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { SyncStatusCard(currentSyncState) }

            if (recorderRecovery !is RecorderRecoveryState.Idle) {
                item {
                    RecorderRecoveryCard(
                        state = recorderRecovery,
                        onOpenWifiSettings = { openRecorderWifiPanel(context) },
                        onOpenDevice = { nav.navigate("device") }
                    )
                }
            }

            if (currentSyncState is SyncState.JoiningRecorderWifi) {
                item {
                    WifiHandoffHelp(
                        phase = currentSyncState.phase,
                        ssid = currentSyncState.ssid,
                        wifiPassword = currentSyncState.wifiPassword,
                        onOpenWifiSettings = { openRecorderWifiPanel(context) },
                        onCancel = viewModel::cancelSync
                    )
                }
            }

            item {
                TransferMethodSelector(
                    selected = transport,
                    enabled = !currentSyncState.isRunning,
                    onSelect = viewModel::selectSyncTransport
                )
            }

            if (transport == SyncTransport.BLUETOOTH_LIVE_STREAM) {
                item {
                    BluetoothLiveStreamCard(
                        state = livePreview,
                        recorderConnected = recorderConnected,
                        recorderIsRecording = recorderStatus?.recording == 1,
                        onToggle = viewModel::toggleBleLiveStream
                    )
                }
            }

            if (transport == SyncTransport.WIFI_COMPLETED_RECORDINGS) {
                item { SyncTimeline(currentSyncState) }
            }

            if (currentSyncState is SyncState.Failed) {
                item {
                    SyncFailureCard(
                        failure = syncFailurePresentation(currentSyncState.message),
                        onRetry = {
                            if (!recorderConnected) nav.navigate("device") else viewModel.sync()
                        },
                        onConnectRecorder = { nav.navigate("device") },
                        recorderConnected = recorderConnected,
                        onOpenNetworkSettings = {
                            if (currentSyncState.message.contains("SYNC-WIFI-LOCATION-SERVICES")) {
                                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            } else {
                                openRecorderWifiPanel(context)
                            }
                        },
                        onShareReport = { shareSyncReport(context, currentSyncState.message) }
                    )
                }
            }

            item {
                if (transport == SyncTransport.WIFI_COMPLETED_RECORDINGS) {
                    SyncActionRow(
                        syncState = currentSyncState,
                        recorderConnected = recorderConnected,
                        onStart = viewModel::sync,
                        onCancel = viewModel::cancelSync,
                        onOpenDevice = { nav.navigate("device") },
                        onOpenLibrary = { nav.navigate("library") }
                    )
                }
            }

            if (currentSyncState is SyncState.Complete) {
                item {
                    Text(
                        if (currentSyncState.waitingForInternet) {
                            "Recordings are saved. Connect the phone to the internet; transcription will start automatically."
                        } else {
                            "Audio processing continues in Recordings. You can leave this page while transcripts are created."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (currentSyncState.waitingForInternet) {
                        OutlinedButton(onClick = { openRecorderWifiPanel(context) }) {
                            Icon(Icons.Filled.Wifi, contentDescription = null)
                            Text("Choose internet Wi-Fi")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferMethodSelector(
    selected: SyncTransport,
    enabled: Boolean,
    onSelect: (SyncTransport) -> Unit
) {
    val options = listOf(SyncTransport.WIFI_COMPLETED_RECORDINGS, SyncTransport.BLUETOOTH_LIVE_STREAM)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Transfer method", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    val isSelected = selected == option
                    SegmentedButton(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        enabled = enabled,
                        label = {
                            Text(if (option == SyncTransport.WIFI_COMPLETED_RECORDINGS) "Wi-Fi" else "Bluetooth")
                        }
                    )
                }
            }
            Text(
                if (selected == SyncTransport.WIFI_COMPLETED_RECORDINGS) {
                    "Wi-Fi copies completed recordings from the recorder TF card."
                } else {
                    "Bluetooth saves live audio to this phone while the recorder is recording. It cannot copy completed TF-card files."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun BluetoothLiveStreamCard(
    state: LivePreviewState,
    recorderConnected: Boolean,
    recorderIsRecording: Boolean,
    onToggle: () -> Unit
) {
    val isActive = state.isActive
    val canToggle = recorderConnected && recorderIsRecording && state !is LivePreviewState.Starting && state !is LivePreviewState.Stopping
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Bluetooth live audio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when (state) {
                    LivePreviewState.Off -> "Start the board recording, then begin and save the live stream."
                    LivePreviewState.Starting -> "Starting Bluetooth live audio..."
                    is LivePreviewState.Receiving -> "Saving Bluetooth live audio for ${formatLiveDuration(state.receivedDurationMillis)}."
                    LivePreviewState.Stopping -> "Stopping Bluetooth live audio..."
                    is LivePreviewState.Unavailable -> "Live audio is unavailable: ${state.message}"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (!recorderConnected) {
                Text("Connect the recorder before starting Bluetooth live audio.", style = MaterialTheme.typography.bodySmall)
            } else if (!recorderIsRecording) {
                Text("Start recording on the board before starting Bluetooth live audio.", style = MaterialTheme.typography.bodySmall)
            }
            if (state is LivePreviewState.Starting || state is LivePreviewState.Stopping) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = onToggle, enabled = canToggle, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null)
                Text(if (isActive) "Stop and save live stream" else "Start and save live stream")
            }
        }
    }
}

private fun formatLiveDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun SyncStatusCard(state: SyncState) {
    val presentation = syncPresentation(state)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(presentation.icon, contentDescription = null, tint = presentation.color)
                Column {
                    Text(presentation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(presentation.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state is SyncState.Downloading) {
                val overallProgress = state.overallProgress
                LinearProgressIndicator(progress = { overallProgress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatTransferBytes(state.overallTransferred)} of ${formatTransferBytes(state.overallBytes)} (${(overallProgress * 100).roundToInt()}%)",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "${state.dataMode.lowercase().replaceFirstChar(Char::uppercase)} FTP · ${state.transferSpeedLabel()}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SyncTimeline(state: SyncState) {
    val activeStep = state.activeSyncStep
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sync progress", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SyncTimelineStep("Connect to recorder", activeStep >= 1, activeStep == 1)
        SyncTimelineStep("Join recorder Wi-Fi", activeStep >= 2, activeStep == 2)
        SyncTimelineStep("Download recordings", activeStep >= 3, activeStep == 3)
        SyncTimelineStep("Save to Recordings", activeStep >= 4, activeStep == 4)
        SyncTimelineStep("Start transcription", activeStep >= 5, activeStep == 5)
    }
}

@Composable
private fun RecorderRecoveryCard(
    state: RecorderRecoveryState,
    onOpenWifiSettings: () -> Unit,
    onOpenDevice: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state) {
                RecorderRecoveryState.Idle -> Unit
                is RecorderRecoveryState.Reconnecting -> {
                    Text("Restoring recorder Bluetooth", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Attempt ${state.attempt} of ${state.maxAttempts}. The recorder can take several minutes to leave Wi-Fi transfer mode.")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                RecorderRecoveryState.Connected -> {
                    Text("Recorder reconnected", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Bluetooth controls are ready again.")
                }
                RecorderRecoveryState.NeedsUserAction -> {
                    Text("Recorder needs reconnection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Switch the phone away from recorder Wi-Fi, power-cycle the recorder, then reconnect it.")
                    OutlinedButton(onClick = onOpenWifiSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Wi-Fi settings") }
                    Button(onClick = onOpenDevice, modifier = Modifier.fillMaxWidth()) { Text("Connect recorder") }
                }
            }
        }
    }
}

@Composable
private fun SyncTimelineStep(label: String, complete: Boolean, active: Boolean) {
    val tint = when {
        complete && !active -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            if (complete && !active) Icons.Filled.CheckCircle else Icons.Filled.Info,
            contentDescription = null,
            tint = tint
        )
        Text(label, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WifiHandoffHelp(
    phase: RecorderWifiJoinPhase,
    ssid: String,
    wifiPassword: String,
    onOpenWifiSettings: () -> Unit,
    onCancel: () -> Unit
) {
    val message = when (phase) {
        RecorderWifiJoinPhase.STARTING -> "Starting the recorder Wi-Fi. Keep this app open."
        RecorderWifiJoinPhase.CHECKING_SAVED_WIFI -> "Checking whether Android has already connected to the recorder Wi-Fi."
        RecorderWifiJoinPhase.WAITING_FOR_HOTSPOT -> "Waiting for the recorder hotspot to become visible. This normally takes a few seconds."
        RecorderWifiJoinPhase.REQUESTING_SYSTEM_APPROVAL -> "Android will ask to connect to the recorder Wi-Fi. Tap Connect and stay in AI Recorder. If its popup does not respond, tap Cancel; this screen will recover within 30 seconds."
        RecorderWifiJoinPhase.WAITING_FOR_MANUAL_SELECTION -> "Select the \"recoder\" Wi-Fi network in the Android panel, then return here. Sync will continue automatically."
        RecorderWifiJoinPhase.CONNECTED -> "Connected to recorder Wi-Fi. Looking for saved recordings."
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Wifi, contentDescription = null)
                Text("Recorder Wi-Fi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(message)
            if (phase == RecorderWifiJoinPhase.WAITING_FOR_MANUAL_SELECTION) {
                Text("Network: $ssid", fontWeight = FontWeight.SemiBold)
                Text("Password: $wifiPassword", fontWeight = FontWeight.SemiBold)
                ManualWifiCountdown()
                Button(onClick = onOpenWifiSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Text("Choose recorder Wi-Fi")
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel sync")
                }
            }
        }
    }
}

@Composable
private fun ManualWifiCountdown() {
    var secondsRemaining by remember { mutableIntStateOf(120) }
    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1_000L)
            secondsRemaining -= 1
        }
    }
    Text("${secondsRemaining}s remaining", style = MaterialTheme.typography.bodySmall)
}

private fun openRecorderWifiPanel(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
}

@Composable
private fun SyncActionRow(
    syncState: SyncState,
    recorderConnected: Boolean,
    onStart: () -> Boolean,
    onCancel: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    when {
        syncState.isRunning -> {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel sync") }
        }
        syncState is SyncState.Failed -> Unit
        syncState is SyncState.Complete -> {
            Button(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) { Text("View recordings") }
        }
        recorderConnected -> {
            Button(onClick = { onStart() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(if (syncState is SyncState.Failed) "Try sync again" else "Sync recordings")
            }
        }
        else -> {
            Button(onClick = onOpenDevice, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null)
                Text("Connect recorder")
            }
        }
    }
}

@Composable
private fun SyncFailureCard(
    failure: SyncFailurePresentation,
    onRetry: () -> Unit,
    onConnectRecorder: () -> Unit,
    recorderConnected: Boolean,
    onOpenNetworkSettings: () -> Unit,
    onShareReport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(failure.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(failure.message)
            failure.steps.forEach { step ->
                Text("• $step", style = MaterialTheme.typography.bodySmall)
            }
            Text("Support code: ${failure.supportCode}", style = MaterialTheme.typography.labelMedium)
            if (recorderConnected) {
                Button(onClick = onRetry) { Text("Try again") }
            } else {
                Button(onClick = onConnectRecorder) { Text("Connect recorder") }
            }
            if (failure.supportCode.startsWith("SYNC-WIFI")) {
                OutlinedButton(onClick = onOpenNetworkSettings) {
                    Text(if (failure.supportCode.contains("LOCATION")) "Turn on Location" else "Open Wi-Fi settings")
                }
            }
            OutlinedButton(onClick = onShareReport) { Text("Share support report") }
        }
    }
}

private data class SyncPresentation(
    val title: String,
    val message: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

private fun syncPresentation(state: SyncState): SyncPresentation = when (state) {
    SyncState.Idle -> SyncPresentation("Ready to sync", "Connect your recorder, then sync saved recordings.", Icons.Filled.Sync, Color.Unspecified)
    SyncState.PreparingRecorder -> SyncPresentation("Preparing recorder", "Switching the recorder from Bluetooth to transfer mode.", Icons.Filled.Bluetooth, Color.Unspecified)
    is SyncState.JoiningRecorderWifi -> SyncPresentation(
        "Connecting to recorder",
        when (state.phase) {
            RecorderWifiJoinPhase.STARTING -> "Starting recorder Wi-Fi."
            RecorderWifiJoinPhase.CHECKING_SAVED_WIFI -> "Checking saved recorder Wi-Fi."
            RecorderWifiJoinPhase.WAITING_FOR_HOTSPOT -> "Waiting for recorder hotspot."
            RecorderWifiJoinPhase.REQUESTING_SYSTEM_APPROVAL -> "Waiting for Android to approve recorder Wi-Fi. Recovery starts automatically after 30 seconds."
            RecorderWifiJoinPhase.WAITING_FOR_MANUAL_SELECTION -> "Waiting for recorder Wi-Fi selection."
            RecorderWifiJoinPhase.CONNECTED -> "Recorder Wi-Fi connected."
        },
        Icons.Filled.Wifi,
        Color.Unspecified
    )
    is SyncState.ConnectingToRecorderFiles -> SyncPresentation(
        "Connecting to recordings",
        "Starting the recorder transfer service (${state.attempt} of ${state.maxAttempts}).",
        Icons.Filled.Wifi,
        Color.Unspecified
    )
    SyncState.ListingFiles -> SyncPresentation("Finding recordings", "Checking the recorder for completed recordings.", Icons.Filled.Sync, Color.Unspecified)
    is SyncState.Downloading -> SyncPresentation(
        "Downloading recordings",
        if (state.retryAttempt == 0) "Downloading ${state.fileName}" else "Retrying ${state.fileName}",
        Icons.Filled.Sync,
        Color.Unspecified
    )
    SyncState.LeavingRecorderWifi -> SyncPresentation("Finishing sync", "Saving recordings safely to this phone.", Icons.Filled.CheckCircle, Color.Unspecified)
    SyncState.WaitingForInternet -> SyncPresentation("Recordings saved", "Waiting for an internet connection to start transcription.", Icons.Filled.Wifi, Color.Unspecified)
    SyncState.WaitingForBleRecovery -> SyncPresentation("Sync complete", "The recorder will return to Bluetooth after its Wi-Fi session ends.", Icons.Filled.CheckCircle, Color.Unspecified)
    is SyncState.Complete -> {
        val importMessage = when {
            state.importedCount > 0 && state.alreadyOnPhoneCount > 0 -> "Added ${state.importedCount} new recording(s). ${state.alreadyOnPhoneCount} were already on this phone."
            state.importedCount > 0 -> "Added ${state.importedCount} new recording(s) to this phone."
            state.alreadyOnPhoneCount > 0 -> "No new recordings. ${state.alreadyOnPhoneCount} recording(s) are already on this phone."
            else -> "No completed recordings were found on the recorder."
        }
        val recoveryMessage = if (state.recoveredRecordingCount > 0) {
            " Recovered ${state.recoveredRecordingCount} file(s); removed ${state.discardedAudioFrames} damaged 20 ms audio frame(s), so brief gaps may remain."
        } else {
            ""
        }
        SyncPresentation("Sync complete", importMessage + recoveryMessage, Icons.Filled.CheckCircle, Color.Unspecified)
    }
    SyncState.Cancelled -> SyncPresentation("Sync cancelled", "Completed downloads remain on this phone. You can resume the remaining files later.", Icons.Filled.Info, Color.Unspecified)
    is SyncState.Failed -> SyncPresentation("Sync needs attention", "Review the recovery steps below, then try again.", Icons.Filled.ErrorOutline, MaterialThemeColorPlaceholder)
}

private val MaterialThemeColorPlaceholder = Color(0xFFB3261E)

private val SyncState.activeSyncStep: Int
    get() = when (this) {
        SyncState.Idle -> 0
        SyncState.PreparingRecorder -> 1
        is SyncState.JoiningRecorderWifi,
        is SyncState.ConnectingToRecorderFiles -> 2
        SyncState.ListingFiles,
        is SyncState.Downloading -> 3
        SyncState.LeavingRecorderWifi,
        SyncState.WaitingForBleRecovery -> 4
        SyncState.WaitingForInternet,
        is SyncState.Complete -> 5
        SyncState.Cancelled,
        is SyncState.Failed -> 0
    }

private val SyncState.Downloading.overallProgress: Float
    get() = if (overallBytes > 0L) (overallTransferred.toFloat() / overallBytes.toFloat()).coerceIn(0f, 1f) else 0f

private fun SyncState.Downloading.transferSpeedLabel(): String {
    val elapsedMillis = android.os.SystemClock.elapsedRealtime() - transferStartedAtMillis
    if (transferStartedAtMillis == 0L || elapsedMillis < 1_000L || fileTransferred <= 0L) return "measuring speed"
    val kibPerSecond = fileTransferred / 1024.0 / (elapsedMillis / 1_000.0)
    return "${"%.1f".format(java.util.Locale.US, kibPerSecond)} KiB/s"
}

private data class SyncFailurePresentation(
    val title: String,
    val message: String,
    val steps: List<String>,
    val supportCode: String
)

private fun syncFailurePresentation(rawFailure: String): SyncFailurePresentation {
    val supportCode = Regex("SYNC-[A-Z-]+-\\d+").find(rawFailure)?.value ?: "SYNC-UNKNOWN"
    return when {
        rawFailure.contains("SYNC-BLE-WIFI-AP") -> SyncFailurePresentation(
            "Recorder hotspot did not start",
            "The app sent the Wi-Fi command, but the recorder did not confirm the hotspot before Bluetooth timed out.",
            listOf("Reconnect the recorder over Bluetooth.", "Make sure recording has stopped, then try Sync again.", "If it repeats, send the support code and board serial log to the recorder team."),
            supportCode
        )
        rawFailure.contains("SYNC-WIFI-LOCATION-SERVICES") -> SyncFailurePresentation(
            "Turn on Location to find the recorder",
            "This Android 10/11 phone requires Location services while discovering nearby Wi-Fi networks.",
            listOf("Tap Turn on Location.", "Return to AI Recorder and retry Sync.", "Location data is not uploaded by the app."),
            supportCode
        )
        rawFailure.contains("SYNC-WIFI-HOTSPOT-NOT-VISIBLE") -> SyncFailurePresentation(
            "Recorder Wi-Fi was not visible",
            "The recorder accepted transfer mode, but its hotspot did not appear nearby.",
            listOf("Keep the recorder powered and stop any active recording.", "Wait a few seconds, then retry Sync.", "If it repeats, send the board serial log with this support code."),
            supportCode
        )
        rawFailure.contains("SYNC-WIFI") -> SyncFailurePresentation(
            "Couldn't join recorder Wi-Fi",
            "The phone did not connect to the recorder's temporary Wi-Fi network.",
            listOf("Keep the recorder powered and nearby.", "When Android asks, tap Connect and remain in AI Recorder.", "If recoder is saved but will not connect, forget it in Android Wi-Fi settings and retry with the password shown by the app."),
            supportCode
        )
        rawFailure.contains("SYNC-FTP-CONTROL") || rawFailure.contains("SYNC-FTP-CONNECT") -> SyncFailurePresentation(
            "Recorder transfer service was unavailable",
            "The phone reached recorder Wi-Fi, but the recorder was not ready to transfer recordings.",
            listOf("Keep the phone connected to recorder Wi-Fi.", "Wait a few seconds for the recorder transfer service to start.", "Try sync again. If it repeats, send the support report to the recorder team."),
            supportCode
        )
        rawFailure.contains("SYNC-FTP-LIST") || rawFailure.contains("SYNC-FTP-NLST") || rawFailure.contains("SYNC-FTP-SIZE") -> SyncFailurePresentation(
            "Couldn't read recorder recordings",
            "The recorder transfer service connected, but its recording list could not be read.",
            listOf("Make sure recording has been stopped before syncing.", "Try sync again without leaving recorder Wi-Fi.", "Share the support report if the same recording cannot be listed."),
            supportCode
        )
        rawFailure.contains("SYNC-FTP-RETR") || rawFailure.contains("SYNC-FTP-BYTES") || rawFailure.contains("SYNC-FTP-DOWNLOAD") || rawFailure.contains("SYNC-FTP-PASV") -> SyncFailurePresentation(
            "A recording could not finish downloading",
            "The connection was interrupted while copying audio from the recorder.",
            listOf("Keep the phone close to the recorder.", "Do not switch Wi-Fi networks or close the app during transfer.", "Try sync again. Completed recordings will not be downloaded twice."),
            supportCode
        )
        rawFailure.contains("SYNC-AUDIO-VALIDATE") -> SyncFailurePresentation(
            "Recorder audio format needs attention",
            "The file finished downloading, but its audio data could not be validated. This is not an FTP download interruption.",
            listOf("Confirm the board recording was fully stopped before syncing.", "Try syncing the same recording once more.", "If it repeats, share the support report: the recorder team needs to check file finalization and raw Opus framing."),
            supportCode
        )
        rawFailure.contains("SYNC-PROCESSING") -> SyncFailurePresentation(
            "Audio was saved but transcription could not start",
            "The recording remains on this phone and can be retried from Recordings.",
            listOf("Return to Recordings and check the processing status.", "Connect the phone to the internet.", "Use Retry on the recording if it is still pending."),
            supportCode
        )
        else -> SyncFailurePresentation(
            "Couldn't complete sync",
            "The recorder could not complete this transfer.",
            listOf("Keep the recorder nearby and make sure it is powered.", "Reconnect the recorder, then try sync again.", "Share the support report if the problem repeats."),
            supportCode
        )
    }
}

private fun shareSyncReport(context: android.content.Context, rawFailure: String) {
    val report = """
        AI Recorder sync support report
        App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Time: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}
        Sync detail: $rawFailure
    """.trimIndent()
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, report),
            "Share support report"
        )
    )
}
