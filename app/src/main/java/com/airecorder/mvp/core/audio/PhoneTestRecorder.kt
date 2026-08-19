package com.airecorder.mvp.core.audio

import android.media.MediaRecorder
import java.io.File

data class PhoneTestCapture(val file: File, val durationMillis: Long)

/** Captures a standard Ogg Opus file for the in-app phone test path. */
class PhoneTestRecorder(private val cacheDir: File) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMillis: Long = 0

    fun start() {
        check(recorder == null) { "Phone recording is already active" }
        val output = File(cacheDir, "phone-test/${System.currentTimeMillis()}.ogg").apply { parentFile?.mkdirs() }
        try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            outputFile = output
            startedAtMillis = System.currentTimeMillis()
        } catch (error: Exception) {
            release()
            output.delete()
            throw error
        }
    }

    fun stop(): PhoneTestCapture {
        val activeRecorder = checkNotNull(recorder) { "No phone recording is active" }
        val output = checkNotNull(outputFile)
        val duration = System.currentTimeMillis() - startedAtMillis
        try {
            activeRecorder.stop()
        } catch (error: RuntimeException) {
            output.delete()
            throw IllegalStateException("Record for at least one second before stopping", error)
        } finally {
            release()
        }
        if (output.length() == 0L) {
            output.delete()
            throw IllegalStateException("Phone recording did not produce audio")
        }
        return PhoneTestCapture(output, duration)
    }

    fun cancel() {
        val output = outputFile
        runCatching { recorder?.stop() }
        release()
        output?.delete()
    }

    private fun release() {
        recorder?.reset()
        recorder?.release()
        recorder = null
        outputFile = null
        startedAtMillis = 0
    }
}
