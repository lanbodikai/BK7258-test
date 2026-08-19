package com.airecorder.mvp.core.ble

import com.airecorder.mvp.core.audio.RawOpusFrames
import com.airecorder.mvp.core.database.RecordingEntity
import com.airecorder.mvp.core.database.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.util.UUID

/**
 * Persists the board's A103 notifications in the same raw framed Opus layout used
 * by the recorder's TF-card files: [one-byte length][Opus payload]. These files
 * are previews because BLE notifications have no retransmission or completeness
 * guarantee in the phase-one protocol.
 */
class BleLiveStreamRecorder(
    private val appFilesDir: File,
    private val recordings: RecordingRepository,
    private val frames: kotlinx.coroutines.flow.SharedFlow<LiveOpusFrame>,
    private val publicExporter: PublicBlePreviewExporter
) {
    private var collector: Job? = null
    private var output: BufferedOutputStream? = null
    private var temporaryFile: File? = null
    private var startedAtMillis = 0L
    private var writtenFrames = 0L
    @Volatile private var saving = false

    val isSaving: Boolean
        get() = saving

    fun start(scope: CoroutineScope) {
        check(collector == null) { "BLE-LIVE-SAVE-STATE-001 | Bluetooth live save is already active" }
        val file = File(appFilesDir, "live-preview/${System.currentTimeMillis()}-${UUID.randomUUID()}.opus")
        file.parentFile?.mkdirs()
        temporaryFile = file
        output = file.outputStream().buffered(BUFFER_SIZE)
        startedAtMillis = System.currentTimeMillis()
        writtenFrames = 0L
        saving = true
        collector = scope.launch(Dispatchers.IO) {
            frames.collect { frame -> writeFrame(frame) }
        }
    }

    suspend fun stop(deviceId: String?, scene: Int?): SavedBleLivePreview? {
        val job = collector ?: return null
        collector = null
        saving = false
        job.cancel()
        job.join()

        val file = temporaryFile
        temporaryFile = null
        output?.runCatching { flush() }
        output?.runCatching { close() }
        output = null

        if (file == null || writtenFrames == 0L || !file.isFile || file.length() == 0L) {
            file?.delete()
            return null
        }

        val validation = try {
            RawOpusFrames.validate(file)
        } catch (error: IllegalArgumentException) {
            file.delete()
            throw IllegalStateException("BLE-LIVE-SAVE-VALIDATE-002 | ${error.message}", error)
        }
        val recording = recordings.importBleLivePreview(
            deviceId = deviceId,
            previewFile = file,
            durationMillis = validation.durationMillis,
            scene = scene,
            startedAtMillis = startedAtMillis
        )
        val publicExport = runCatching { publicExporter.export(File(requireNotNull(recording.rawAudioPath)), startedAtMillis) }
        return SavedBleLivePreview(
            recording = recording,
            publicUri = publicExport.getOrNull(),
            publicExportError = publicExport.exceptionOrNull()?.message
        )
    }

    suspend fun discard() {
        val job = collector
        collector = null
        saving = false
        job?.cancel()
        job?.join()
        output?.runCatching { close() }
        output = null
        temporaryFile?.delete()
        temporaryFile = null
        writtenFrames = 0L
    }

    fun abandon() {
        collector?.cancel()
        collector = null
        saving = false
        output?.runCatching { close() }
        output = null
        temporaryFile?.delete()
        temporaryFile = null
    }

    private fun writeFrame(frame: LiveOpusFrame) {
        val stream = output ?: return
        // The protocol parser already bounds payloads to a single unsigned byte.
        stream.write(frame.payload.size)
        stream.write(frame.payload)
        writtenFrames += 1
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

data class SavedBleLivePreview(
    val recording: RecordingEntity,
    val publicUri: android.net.Uri?,
    val publicExportError: String?
)
