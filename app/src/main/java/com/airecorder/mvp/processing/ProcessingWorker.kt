package com.airecorder.mvp.processing

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.airecorder.mvp.BuildConfig
import com.airecorder.mvp.RecorderApplication
import com.airecorder.mvp.core.database.ActionItemEntity
import com.airecorder.mvp.core.database.ProcessingJobEntity
import com.airecorder.mvp.core.database.ProcessingState
import com.airecorder.mvp.core.database.RecorderDatabase
import com.airecorder.mvp.core.database.RecordingRepository
import com.airecorder.mvp.core.database.SummaryEntity
import com.airecorder.mvp.core.database.TranscriptSegmentEntity
import java.io.File
import java.util.UUID

class ProcessingScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)

    fun schedule(recordingId: String) {
        val request = OneTimeWorkRequestBuilder<ProcessingWorker>()
            .setInputData(androidx.work.workDataOf(ProcessingWorker.RECORDING_ID to recordingId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, java.time.Duration.ofSeconds(10))
            .build()
        manager.enqueueUniqueWork("process-recording-$recordingId", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(recordingId: String) {
        manager.cancelUniqueWork("process-recording-$recordingId")
    }

    fun scheduleRestore(recordingId: String) {
        val request = OneTimeWorkRequestBuilder<ProcessingWorker>()
            .setInputData(androidx.work.workDataOf(
                ProcessingWorker.RECORDING_ID to recordingId,
                ProcessingWorker.RESTORE_CACHE to true
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, java.time.Duration.ofSeconds(10))
            .build()
        manager.enqueueUniqueWork("restore-recording-$recordingId", ExistingWorkPolicy.KEEP, request)
    }
}

class ProcessingWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(RECORDING_ID) ?: return Result.failure()
        val app = applicationContext as RecorderApplication
        val database = app.container.database
        val repository = app.container.recordings
        val recording = database.recordingDao().find(recordingId) ?: return Result.failure()
        if (BuildConfig.AI_BACKEND_URL.contains("example.invalid")) {
            repository.markProcessing(recordingId, ProcessingState.FAILED, "AI backend is not configured")
            return Result.failure()
        }
        val currentJob = database.processingJobDao().findForRecording(recordingId)
            ?: ProcessingJobEntity(UUID.randomUUID().toString(), recordingId, state = ProcessingState.QUEUED, updatedAt = System.currentTimeMillis())
        database.processingJobDao().upsert(currentJob)

        if (inputData.getBoolean(RESTORE_CACHE, false) || recording.localCacheState == com.airecorder.mvp.core.database.LocalCacheState.EVICTED) {
            return restoreFromCloud(recordingId, currentJob, database, repository)
        }

        val audio = recording.rawAudioPath?.let(::File)?.takeIf(File::exists) ?: run {
            repository.markProcessing(recordingId, ProcessingState.FAILED, "Downloaded audio file is missing")
            return Result.failure()
        }

        return try {
            repository.markProcessing(recordingId, ProcessingState.UPLOADING)
            val client = BackendProcessingClient(
                BuildConfig.AI_BACKEND_URL,
                AndroidCloudConnectionFactory(applicationContext)
            )
            when (val outcome = client.process(recording, audio, currentJob.backendJobId)) {
                is BackendOutcome.Pending -> {
                    database.processingJobDao().upsert(currentJob.copy(
                        backendJobId = outcome.jobId,
                        state = outcome.state,
                        attempts = runAttemptCount + 1,
                        updatedAt = System.currentTimeMillis()
                    ))
                    repository.markProcessing(recordingId, outcome.state)
                    Result.retry()
                }
                is BackendOutcome.Complete -> {
                    saveCompleteOutcome(recordingId, currentJob, outcome, database, repository)
                    repository.enforceLocalCache()
                    Result.success()
                }
            }
        } catch (failure: BackendPermanentFailure) {
            repository.markProcessing(recordingId, ProcessingState.FAILED, failure.message)
            Result.failure()
        } catch (failure: Exception) {
            repository.markProcessing(
                recordingId,
                ProcessingState.QUEUED,
                failure.message ?: "Waiting to retry cloud processing"
            )
            Result.retry()
        }
    }

    private suspend fun restoreFromCloud(
        recordingId: String,
        currentJob: ProcessingJobEntity,
        database: RecorderDatabase,
        repository: RecordingRepository
    ): Result {
        val backendJobId = currentJob.backendJobId ?: run {
            repository.markProcessing(recordingId, ProcessingState.FAILED, "Cloud processing result is unavailable")
            return Result.failure()
        }
        return try {
            when (val outcome = BackendProcessingClient(
                BuildConfig.AI_BACKEND_URL,
                AndroidCloudConnectionFactory(applicationContext)
            ).restore(backendJobId)) {
                is BackendOutcome.Pending -> {
                    database.processingJobDao().upsert(currentJob.copy(
                        state = outcome.state,
                        updatedAt = System.currentTimeMillis()
                    ))
                    Result.retry()
                }
                is BackendOutcome.Complete -> {
                    saveCompleteOutcome(recordingId, currentJob, outcome, database, repository)
                    Result.success()
                }
            }
        } catch (failure: BackendPermanentFailure) {
            repository.markProcessing(recordingId, ProcessingState.FAILED, failure.message)
            Result.failure()
        } catch (failure: Exception) {
            repository.markProcessing(
                recordingId,
                ProcessingState.QUEUED,
                failure.message ?: "Waiting to retry cloud processing"
            )
            Result.retry()
        }
    }

    private suspend fun saveCompleteOutcome(
        recordingId: String,
        currentJob: ProcessingJobEntity,
        outcome: BackendOutcome.Complete,
        database: RecorderDatabase,
        repository: RecordingRepository
    ) {
        database.transcriptDao().deleteForRecording(recordingId)
        database.transcriptDao().replaceAll(outcome.transcript.map {
            TranscriptSegmentEntity(UUID.randomUUID().toString(), recordingId, it.startMillis, it.endMillis, it.speaker, it.text)
        })
        if (outcome.summaryTitle != null && outcome.summary != null) {
            database.summaryDao().upsert(SummaryEntity(recordingId, "meeting", outcome.summaryTitle, outcome.summary))
        }
        database.actionItemDao().deleteForRecording(recordingId)
        database.actionItemDao().replaceAll(outcome.actionItems.map {
            ActionItemEntity(UUID.randomUUID().toString(), recordingId, it)
        })
        database.processingJobDao().upsert(currentJob.copy(
            backendJobId = outcome.jobId,
            state = ProcessingState.COMPLETE,
            updatedAt = System.currentTimeMillis()
        ))
        repository.markCacheCached(recordingId)
        repository.markProcessing(recordingId, ProcessingState.COMPLETE)
    }

    companion object {
        const val RECORDING_ID = "recording_id"
        const val RESTORE_CACHE = "restore_cache"
    }
}
