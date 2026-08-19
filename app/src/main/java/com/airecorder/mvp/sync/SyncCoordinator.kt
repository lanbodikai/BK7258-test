package com.airecorder.mvp.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.airecorder.mvp.core.audio.RawOpusFrames
import com.airecorder.mvp.core.ble.RecorderBleClient
import com.airecorder.mvp.core.ble.RecorderBleProtocol
import com.airecorder.mvp.core.database.RecordingRepository
import com.airecorder.mvp.core.ftp.PassiveFtpClient
import com.airecorder.mvp.core.ftp.FtpDataMode
import com.airecorder.mvp.core.wifi.RecorderWifiConnector
import com.airecorder.mvp.core.wifi.RecorderWifiJoinPhase
import com.airecorder.mvp.processing.ProcessingScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface SyncState {
    data object Idle : SyncState
    data object PreparingRecorder : SyncState
    data class JoiningRecorderWifi(
        val ssid: String,
        val wifiPassword: String,
        val phase: RecorderWifiJoinPhase = RecorderWifiJoinPhase.STARTING
    ) : SyncState
    data class ConnectingToRecorderFiles(val attempt: Int, val maxAttempts: Int) : SyncState
    data object ListingFiles : SyncState
    data class Downloading(
        val fileName: String,
        val currentFile: Int,
        val totalFiles: Int,
        val fileTransferred: Long,
        val fileBytes: Long,
        val overallTransferred: Long,
        val overallBytes: Long,
        val retryAttempt: Int = 0,
        val dataMode: String = "",
        val transferStartedAtMillis: Long = 0L
    ) : SyncState
    data object LeavingRecorderWifi : SyncState
    data object WaitingForInternet : SyncState
    data object WaitingForBleRecovery : SyncState
    data class Complete(
        val importedCount: Int,
        val alreadyOnPhoneCount: Int,
        val recoveredRecordingCount: Int = 0,
        val discardedAudioFrames: Int = 0,
        val waitingForInternet: Boolean = false
    ) : SyncState
    data object Cancelled : SyncState
    data class Failed(val message: String) : SyncState
}

class SyncCoordinator(
    private val context: Context,
    private val ble: RecorderBleClient,
    private val recordings: RecordingRepository,
    private val processing: ProcessingScheduler
) {
    private val wifi = RecorderWifiConnector(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state
    private var lastForegroundProgressBucket = -1

    fun clearOutcome() {
        if (!_state.value.isRunning) _state.value = SyncState.Idle
    }

    suspend fun sync(deviceId: String?, scene: Int?) {
        var stage = SyncStage.START
        val importedRecordingIds = mutableListOf<String>()
        var alreadyOnPhoneCount = 0
        var recoveredRecordingCount = 0
        var discardedAudioFrames = 0
        try {
            require(!deviceId.isNullOrBlank()) { "${SyncStage.START}: no recorder is selected" }
            stage = SyncStage.BLE_HANDOFF
            publish(SyncState.PreparingRecorder)
            val credentials = ble.startWifiHandoff()
            stage = SyncStage.BLE_DISCONNECT
            ble.disconnect()
            stage = SyncStage.WIFI_AP_WAIT
            publish(SyncState.JoiningRecorderWifi(credentials.ssid, credentials.wifiPassword))
            // The recorder is allowed to disconnect BLE before its SoftAP is ready.
            delay(WIFI_AP_STARTUP_DELAY_MS)
            stage = SyncStage.WIFI_JOIN
            wifi.join(credentials) { phase ->
                publish(SyncState.JoiningRecorderWifi(credentials.ssid, credentials.wifiPassword, phase))
            }.use { lease ->
                val ftpPort = credentials.port.takeIf { it in 1..65_535 } ?: run {
                    Log.w(TAG, "SYNC-WIFI-CREDENTIALS-PORT-001: invalid FTP port ${credentials.port}; using ${RecorderBleProtocol.DEFAULT_FTP_PORT}")
                    RecorderBleProtocol.DEFAULT_FTP_PORT
                }
                stage = "${SyncStage.FTP_CONNECT}: ${credentials.host}:$ftpPort"
                val ftp = PassiveFtpClient(
                    socketFactory = lease.network.socketFactory,
                    host = credentials.host,
                    username = credentials.ftpUser,
                    password = credentials.ftpPassword,
                    activeDataAddress = lease.localIpv4Address,
                    // Phase-one board firmware specifies PASV. Active mode remains
                    // available only as a compatibility fallback inside the client.
                    preferredDataMode = FtpDataMode.PASSIVE
                )
                ftp.use {
                    connectFtpWithRetry(ftp) { attempt ->
                        publish(SyncState.ConnectingToRecorderFiles(attempt, FTP_RETRY_ATTEMPTS))
                    }
                    stage = SyncStage.FTP_LIST
                    publish(SyncState.ListingFiles)
                    val files = ftpOperationWithRetry(ftp, "LIST") { _ -> ftp.listRecordings() }
                    val filesToDownload = buildList {
                        files.forEach { remote ->
                            if (recordings.isRemoteImported(deviceId, remote.name, remote.bytes)) {
                                alreadyOnPhoneCount += 1
                                Log.i(TAG, "Skipping previously imported recorder file ${remote.name} (${remote.bytes} bytes)")
                            } else {
                                add(remote)
                            }
                        }
                    }
                    val totalDownloadBytes = filesToDownload.sumOf { it.bytes }
                    var completedDownloadBytes = 0L
                    filesToDownload.forEachIndexed { index, remote ->
                        stage = "${SyncStage.FTP_DOWNLOAD}: ${remote.name}"
                        val temporary = File(context.cacheDir, "sync/${remote.name}.download").apply { parentFile?.mkdirs() }
                        ftpOperationWithRetry(ftp, "RETR ${remote.name}") { attempt ->
                            val transferStartedAtMillis = SystemClock.elapsedRealtime()
                            fun publishDownloadProgress(transferred: Long) = publish(SyncState.Downloading(
                                fileName = remote.name,
                                currentFile = index + 1,
                                totalFiles = filesToDownload.size,
                                fileTransferred = transferred,
                                fileBytes = remote.bytes,
                                overallTransferred = completedDownloadBytes + transferred,
                                overallBytes = totalDownloadBytes,
                                retryAttempt = attempt,
                                dataMode = ftp.activeDataMode.name,
                                transferStartedAtMillis = transferStartedAtMillis
                            ))

                            publishDownloadProgress(0)
                            var lastProgressAt = SystemClock.elapsedRealtime()
                            ftp.download(remote, temporary) { transferred ->
                                val now = SystemClock.elapsedRealtime()
                                if (transferred == remote.bytes || now - lastProgressAt >= DOWNLOAD_PROGRESS_INTERVAL_MS) {
                                    lastProgressAt = now
                                    publishDownloadProgress(transferred)
                                }
                            }
                        }
                        stage = "${SyncStage.AUDIO_VALIDATE}: ${remote.name}"
                        var importFile = temporary
                        val validation = try {
                            RawOpusFrames.validate(temporary)
                        } catch (strictValidationFailure: IllegalArgumentException) {
                            val recovered = RawOpusFrames.recoverFixed80ByteSlots(temporary)
                                ?: throw strictValidationFailure
                            temporary.delete()
                            importFile = recovered.recoveredFile
                            recoveredRecordingCount += 1
                            discardedAudioFrames += recovered.discardedFrames
                            Log.w(
                                TAG,
                                "SYNC-AUDIO-RECOVER-014: ${remote.name}; removed ${recovered.discardedFrames} damaged 20 ms frame(s)"
                            )
                            recovered.validation
                        }
                        stage = "${SyncStage.LOCAL_IMPORT}: ${remote.name}"
                        val recording = recordings.importDownloaded(
                            deviceId = deviceId,
                            remoteName = remote.name,
                            downloadedFile = importFile,
                            durationMillis = validation.durationMillis,
                            scene = scene,
                            sourceByteSize = remote.bytes
                        )
                        importedRecordingIds += recording.id
                        completedDownloadBytes += remote.bytes
                    }
                }
                stage = SyncStage.WIFI_RELEASE
                publish(SyncState.LeavingRecorderWifi)
            }
            val uniqueImportedRecordingIds = importedRecordingIds.distinct()
            val internetReady = if (uniqueImportedRecordingIds.isNotEmpty()) {
                publish(SyncState.WaitingForInternet)
                awaitValidatedInternet()
            } else true
            uniqueImportedRecordingIds.forEach { recordingId ->
                stage = "${SyncStage.PROCESSING_QUEUE}: $recordingId"
                processing.schedule(recordingId)
            }
            // The board restores BLE advertising after its FTP idle timeout.
            // File synchronization itself is complete once WiFi is released.
            publish(SyncState.Complete(
                importedCount = uniqueImportedRecordingIds.size,
                alreadyOnPhoneCount = alreadyOnPhoneCount,
                recoveredRecordingCount = recoveredRecordingCount,
                discardedAudioFrames = discardedAudioFrames,
                waitingForInternet = !internetReady
            ))
        } catch (error: CancellationException) {
            publish(SyncState.Cancelled)
            throw error
        } catch (error: Exception) {
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            Log.e(TAG, "Sync failed during $stage: $detail", error)
            publish(SyncState.Failed("$stage | $detail"))
        }
    }

    private suspend fun awaitValidatedInternet(): Boolean = withTimeoutOrNull(INTERNET_RECOVERY_WAIT_MS) {
        while (currentCoroutineContext().isActive) {
            val available = connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } == true
            }
            if (available) return@withTimeoutOrNull true
            delay(INTERNET_RECOVERY_POLL_MS)
        }
        false
    } == true

    private fun publish(value: SyncState) {
        _state.value = value
        val progress = (value as? SyncState.Downloading)?.let { state ->
            if (state.overallBytes > 0L) ((state.overallTransferred * 100) / state.overallBytes).toInt() else 0
        }
        val progressBucket = progress?.div(5)
        val shouldUpdateNotification = value !is SyncState.Downloading ||
            progressBucket != lastForegroundProgressBucket || progress == 100
        if (!shouldUpdateNotification) return
        lastForegroundProgressBucket = progressBucket ?: -1
        SyncForegroundService.update(context, value.notificationText(), progress)
    }

    private suspend fun connectFtpWithRetry(ftp: PassiveFtpClient, onAttempt: (Int) -> Unit) {
        var lastError: Exception? = null
        repeat(FTP_RETRY_ATTEMPTS) { attempt ->
            try {
                if (attempt > 0) delay(FTP_RETRY_BACKOFF_MS * attempt)
                onAttempt(attempt + 1)
                if (attempt == 0) ftp.connect() else ftp.reconnect()
                return
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastError = error
                Log.w(TAG, "FTP control connection attempt ${attempt + 1}/$FTP_RETRY_ATTEMPTS failed", error)
            }
        }
        throw lastError ?: IllegalStateException("FTP control connection failed")
    }

    private suspend fun <T> ftpOperationWithRetry(
        ftp: PassiveFtpClient,
        operation: String,
        action: (Int) -> T
    ): T {
        var lastError: Exception? = null
        repeat(FTP_RETRY_ATTEMPTS) { attempt ->
            try {
                if (attempt > 0) {
                    delay(FTP_RETRY_BACKOFF_MS * attempt)
                    ftp.reconnect()
                }
                return action(attempt)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastError = error
                Log.w(TAG, "FTP $operation attempt ${attempt + 1}/$FTP_RETRY_ATTEMPTS failed", error)
            }
        }
        throw lastError ?: IllegalStateException("FTP $operation failed")
    }

    private companion object {
        const val TAG = "SyncCoordinator"
        const val WIFI_AP_STARTUP_DELAY_MS = 2_000L
        const val FTP_RETRY_ATTEMPTS = 6
        const val FTP_RETRY_BACKOFF_MS = 500L
        const val DOWNLOAD_PROGRESS_INTERVAL_MS = 250L
        const val INTERNET_RECOVERY_WAIT_MS = 10_000L
        const val INTERNET_RECOVERY_POLL_MS = 500L
    }
}

private fun SyncState.notificationText(): String = when (this) {
    SyncState.Idle -> "Ready to sync"
    SyncState.PreparingRecorder -> "Preparing recorder"
    is SyncState.JoiningRecorderWifi -> "Connecting to recorder Wi-Fi"
    is SyncState.ConnectingToRecorderFiles -> "Connecting to recorder recordings"
    SyncState.ListingFiles -> "Finding recordings"
    is SyncState.Downloading -> "Downloading ${currentFile} of $totalFiles"
    SyncState.LeavingRecorderWifi -> "Saving recordings to this phone"
    SyncState.WaitingForInternet -> "Waiting for internet to start transcription"
    SyncState.WaitingForBleRecovery -> "Finishing sync"
    is SyncState.Complete -> "Sync complete"
    SyncState.Cancelled -> "Sync cancelled"
    is SyncState.Failed -> "Sync needs attention"
}

val SyncState.isRunning: Boolean
    get() = when (this) {
        SyncState.Idle,
        is SyncState.Complete,
        SyncState.Cancelled,
        is SyncState.Failed -> false
        else -> true
    }

private object SyncStage {
    const val START = "SYNC-START-001"
    const val BLE_HANDOFF = "SYNC-BLE-HANDOFF-002"
    const val BLE_DISCONNECT = "SYNC-BLE-DISCONNECT-003"
    const val WIFI_AP_WAIT = "SYNC-WIFI-AP-WAIT-004"
    const val WIFI_JOIN = "SYNC-WIFI-JOIN-005"
    const val FTP_CONNECT = "SYNC-FTP-CONNECT-006"
    const val FTP_LIST = "SYNC-FTP-LIST-007"
    const val FTP_DOWNLOAD = "SYNC-FTP-DOWNLOAD-008"
    const val AUDIO_VALIDATE = "SYNC-AUDIO-VALIDATE-009"
    const val LOCAL_IMPORT = "SYNC-LOCAL-IMPORT-010"
    const val PROCESSING_QUEUE = "SYNC-PROCESSING-QUEUE-011"
    const val WIFI_RELEASE = "SYNC-WIFI-RELEASE-012"
    const val BLE_RECOVERY = "SYNC-BLE-RECOVERY-013"
}
