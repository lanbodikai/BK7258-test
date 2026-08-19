package com.airecorder.mvp.sync

import android.app.Application
import com.airecorder.mvp.core.ble.BleConnectionState
import com.airecorder.mvp.core.ble.RecorderBleClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface RecorderRecoveryState {
    data object Idle : RecorderRecoveryState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : RecorderRecoveryState
    data object Connected : RecorderRecoveryState
    data object NeedsUserAction : RecorderRecoveryState
}

/** Owns sync independently of any Activity or screen ViewModel. */
class SyncSessionController(
    private val application: Application,
    private val coordinator: SyncCoordinator,
    private val ble: RecorderBleClient,
    private val scope: CoroutineScope
) {
    private var syncJob: Job? = null
    private var recoveryJob: Job? = null
    private val _recovery = MutableStateFlow<RecorderRecoveryState>(RecorderRecoveryState.Idle)
    val recovery: StateFlow<RecorderRecoveryState> = _recovery

    val isSyncActive: Boolean
        get() = syncJob?.isActive == true || coordinator.state.value.isRunning

    @Synchronized
    fun start(deviceId: String?, scene: Int?): Boolean {
        if (isSyncActive) return false
        recoveryJob?.cancel()
        recoveryJob = null
        _recovery.value = RecorderRecoveryState.Idle
        syncJob = scope.launch {
            SyncForegroundService.start(application)
            try {
                withContext(Dispatchers.IO) { coordinator.sync(deviceId, scene) }
            } finally {
                application.stopService(android.content.Intent(application, SyncForegroundService::class.java))
                startRecovery(deviceId)
            }
        }
        return true
    }

    fun cancel() {
        syncJob?.cancel(CancellationException("Sync cancelled by user"))
    }

    /** Android can end an overlong dataSync foreground service. Leave the UI retryable. */
    fun cancelForSystemTimeout() {
        syncJob?.cancel(CancellationException("Sync ended by the Android transfer time limit"))
    }

    fun cancelRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
        _recovery.value = RecorderRecoveryState.Idle
    }

    fun markConnected() {
        recoveryJob?.cancel()
        recoveryJob = scope.launch { publishConnected() }
    }

    private fun startRecovery(deviceId: String?) {
        if (deviceId.isNullOrBlank()) return
        recoveryJob?.cancel()
        recoveryJob = scope.launch(Dispatchers.IO) {
            delay(RECOVERY_INITIAL_DELAY_MS)
            repeat(RECOVERY_ATTEMPTS) { index ->
                if (!isActive) return@launch
                if (ble.connection.value is BleConnectionState.Connected) {
                    publishConnected()
                    return@launch
                }
                _recovery.value = RecorderRecoveryState.Reconnecting(index + 1, RECOVERY_ATTEMPTS)
                val connected = runCatching {
                    withTimeout(RECOVERY_CONNECT_TIMEOUT_MS) { ble.connect(deviceId) }
                    ble.connection.value is BleConnectionState.Connected
                }.getOrDefault(false)
                if (connected) {
                    publishConnected()
                    return@launch
                }
                delay(RECOVERY_RETRY_DELAY_MS)
            }
            _recovery.value = RecorderRecoveryState.NeedsUserAction
        }
    }

    private suspend fun publishConnected() {
        _recovery.value = RecorderRecoveryState.Connected
        delay(RECOVERY_SUCCESS_VISIBLE_MS)
        if (_recovery.value is RecorderRecoveryState.Connected) {
            _recovery.value = RecorderRecoveryState.Idle
        }
    }

    private companion object {
        const val RECOVERY_ATTEMPTS = 24
        const val RECOVERY_INITIAL_DELAY_MS = 5_000L
        const val RECOVERY_CONNECT_TIMEOUT_MS = 16_000L
        const val RECOVERY_RETRY_DELAY_MS = 9_000L
        const val RECOVERY_SUCCESS_VISIBLE_MS = 3_000L
    }
}
