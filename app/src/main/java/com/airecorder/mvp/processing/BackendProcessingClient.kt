package com.airecorder.mvp.processing

import com.airecorder.mvp.core.database.ProcessingState
import com.airecorder.mvp.core.database.RecordingEntity
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class TranscriptPart(val startMillis: Long, val endMillis: Long, val speaker: String?, val text: String)
sealed interface BackendOutcome {
    data class Pending(val jobId: String, val state: ProcessingState) : BackendOutcome
    data class Complete(val jobId: String, val transcript: List<TranscriptPart>, val summaryTitle: String?, val summary: String?, val actionItems: List<String>) : BackendOutcome
}

class BackendPermanentFailure(message: String) : IllegalStateException(message)

/**
 * The phone never holds an STT or LLM credential. The service issues a single-use upload URL
 * and owns all cloud-provider calls.
 */
class BackendProcessingClient(
    private val baseUrl: String,
    private val connectionFactory: HttpConnectionFactory = HttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    }
) {
    suspend fun process(recording: RecordingEntity, audio: File, existingJobId: String?): BackendOutcome {
        if (existingJobId == null) {
            val jobId = createJob(recording, audio)
            return awaitInitialCompletion(jobId)
        }
        return readJob(existingJobId)
    }

    fun restore(jobId: String): BackendOutcome = readJob(jobId)

    private fun createJob(recording: RecordingEntity, audio: File): String {
        val payload = JSONObject().apply {
            put("clientRecordingId", recording.id)
            put("sha256", recording.sourceFingerprint)
            put("byteSize", audio.length())
            put("languages", JSONArray(listOf("zh-CN", "en-US")))
            put("audioFormat", when (recording.audioFormat) {
                com.airecorder.mvp.core.database.AudioFormat.RAW_OPUS -> "raw_opus"
                com.airecorder.mvp.core.database.AudioFormat.OGG_OPUS -> "ogg_opus"
            })
        }
        val response = request("POST", "/v1/jobs", payload.toString())
        val json = JSONObject(response.body)
        val jobId = json.getString("jobId")
        upload(json.getString("uploadUrl"), audio, json.getString("uploadContentType"))
        request("POST", "/v1/jobs/$jobId/complete-upload", "{}")
        return jobId
    }

    private fun readJob(jobId: String): BackendOutcome {
        val response = request("GET", "/v1/jobs/$jobId", null)
        val json = JSONObject(response.body)
        return when (json.getString("state")) {
            "queued" -> BackendOutcome.Pending(jobId, ProcessingState.QUEUED)
            "uploading" -> BackendOutcome.Pending(jobId, ProcessingState.UPLOADING)
            "transcribing" -> BackendOutcome.Pending(jobId, ProcessingState.TRANSCRIBING)
            "summarizing" -> BackendOutcome.Pending(jobId, ProcessingState.SUMMARIZING)
            "complete" -> BackendOutcome.Complete(
                jobId = jobId,
                transcript = json.getJSONArray("transcript").toTranscript(),
                summaryTitle = json.optJSONObject("summary")?.optString("title")?.takeIf { it.isNotBlank() },
                summary = json.optJSONObject("summary")?.optString("content")?.takeIf { it.isNotBlank() },
                actionItems = json.optJSONArray("actionItems")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()
            )
            "failed" -> throw BackendPermanentFailure(json.optString("error", "AI processing failed"))
            else -> throw BackendPermanentFailure("Unknown backend job state")
        }
    }

    private suspend fun awaitInitialCompletion(jobId: String): BackendOutcome {
        var latest: BackendOutcome.Pending = BackendOutcome.Pending(jobId, ProcessingState.QUEUED)
        repeat(INITIAL_POLL_ATTEMPTS) {
            delay(INITIAL_POLL_INTERVAL_MILLIS)
            when (val outcome = readJob(jobId)) {
                is BackendOutcome.Complete -> return outcome
                is BackendOutcome.Pending -> latest = outcome
            }
        }
        return latest
    }

    private fun upload(uploadUrl: String, file: File, contentType: String) {
        val connection = connectionFactory.open(URL(uploadUrl))
        connection.requestMethod = "PUT"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.setFixedLengthStreamingMode(file.length())
        connection.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (connection.responseCode !in 200..299) throw IllegalStateException("Audio upload failed with HTTP ${connection.responseCode}")
        connection.disconnect()
    }

    private fun request(method: String, path: String, body: String?): HttpResponse {
        val connection = connectionFactory.open(URL(baseUrl.trimEnd('/') + path))
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(body) }
        }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) throw BackendPermanentFailure("Backend request failed with HTTP $code: $response")
        return HttpResponse(response)
    }

    private data class HttpResponse(val body: String)

    private companion object {
        const val INITIAL_POLL_ATTEMPTS = 10
        const val INITIAL_POLL_INTERVAL_MILLIS = 2_000L
    }
}

private fun JSONArray.toTranscript(): List<TranscriptPart> = List(length()) { index ->
    val item = getJSONObject(index)
    TranscriptPart(item.getLong("startMillis"), item.getLong("endMillis"), item.optString("speaker").takeIf { it.isNotBlank() }, item.getString("text"))
}
