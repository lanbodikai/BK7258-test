package com.airecorder.mvp.core.ble

/** A validated 20 ms Opus payload received from the recorder's A103 notify characteristic. */
data class LiveOpusFrame(
    val payload: ByteArray,
    val receivedAtMillis: Long
)

sealed interface LivePreviewState {
    data object Off : LivePreviewState
    data object Starting : LivePreviewState
    data class Receiving(
        val receivedFrames: Long,
        val receivedPayloadBytes: Long,
        val lastFrameAtMillis: Long
    ) : LivePreviewState {
        val receivedDurationMillis: Long = receivedFrames * FRAME_DURATION_MILLIS
    }
    data object Stopping : LivePreviewState
    data class Unavailable(val message: String) : LivePreviewState

    companion object {
        const val FRAME_DURATION_MILLIS = 20L
    }
}

val LivePreviewState.isActive: Boolean
    get() = this is LivePreviewState.Starting || this is LivePreviewState.Receiving || this is LivePreviewState.Stopping
