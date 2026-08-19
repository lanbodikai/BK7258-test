package com.airecorder.mvp.core.audio

import java.io.File
import java.io.InputStream

/** The recorder's files are raw framed Opus, not Ogg Opus containers. */
object RawOpusFrames {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val SAMPLES_PER_FRAME = 320
    const val MIN_PAYLOAD_BYTES = 20
    const val MAX_PAYLOAD_BYTES = 80
    private const val FIXED_SLOT_PAYLOAD_BYTES = 80
    private const val FIXED_SLOT_BYTES = FIXED_SLOT_PAYLOAD_BYTES + 1
    private const val MIN_FIXED_SLOT_RECOVERY_PERCENT = 95L

    data class Validation(val frames: Int, val payloadBytes: Long) {
        val durationMillis: Long = frames * 20L
    }

    data class FixedSlotRecovery(
        val recoveredFile: File,
        val validation: Validation,
        val discardedFrames: Int
    )

    fun validate(file: File): Validation = file.inputStream().use(::validate)

    fun validate(input: InputStream): Validation {
        val source = input.buffered()
        source.mark(4)
        val signature = ByteArray(4)
        val signatureBytes = source.read(signature)
        source.reset()
        require(!(signatureBytes == 4 && signature.contentEquals(OGG_SIGNATURE))) {
            "Recorder file is Ogg Opus, but this recorder profile requires raw framed Opus"
        }
        var frames = 0
        var payloadBytes = 0L
        var offset = 0L
        while (true) {
            val frameOffset = offset
            val length = source.read()
            if (length == -1) break
            offset += 1
            require(length in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES) {
                "Invalid Opus frame length $length at byte $frameOffset after $frames complete frame(s)"
            }
            var remaining = length
            while (remaining > 0) {
                val read = source.read(ByteArray(minOf(remaining, 4096)))
                require(read != -1) {
                    "Truncated Opus frame at byte $frameOffset with $remaining payload byte(s) missing"
                }
                remaining -= read
                offset += read
            }
            frames += 1
            payloadBytes += length
        }
        require(frames > 0) { "Recording contains no Opus frames" }
        return Validation(frames, payloadBytes)
    }

    /**
     * Some early board firmware writes fixed 81-byte slots but intermittently corrupts the
     * one-byte 80-byte-payload length prefix. Recover only this highly specific shape and drop
     * the damaged slots rather than inventing unknown Opus packets.
     */
    fun recoverFixed80ByteSlots(source: File): FixedSlotRecovery? {
        if (!source.isFile || source.length() == 0L || source.length() % FIXED_SLOT_BYTES != 0L) return null

        val totalSlots = source.length() / FIXED_SLOT_BYTES
        val recovered = File(source.parentFile, "${source.name}.recovered")
        recovered.delete()
        val slot = ByteArray(FIXED_SLOT_BYTES)
        var acceptedSlots = 0L

        source.inputStream().buffered().use { input ->
            recovered.outputStream().buffered().use { output ->
                repeat(totalSlots.toInt()) {
                    input.readFully(slot)
                    if ((slot[0].toInt() and 0xFF) == FIXED_SLOT_PAYLOAD_BYTES) {
                        output.write(slot)
                        acceptedSlots += 1
                    }
                }
            }
        }

        if (acceptedSlots == 0L || acceptedSlots * 100L < totalSlots * MIN_FIXED_SLOT_RECOVERY_PERCENT) {
            recovered.delete()
            return null
        }

        return try {
            FixedSlotRecovery(
                recoveredFile = recovered,
                validation = validate(recovered),
                discardedFrames = (totalSlots - acceptedSlots).toInt()
            )
        } catch (_: IllegalArgumentException) {
            recovered.delete()
            null
        }
    }

    private val OGG_SIGNATURE = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
}

private fun InputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val read = read(target, offset, target.size - offset)
        require(read != -1) { "Unexpected end of fixed recorder slot" }
        offset += read
    }
}

/** Implement with a bundled libopus JNI bridge before enabling production playback. */
interface RawOpusDecoder {
    fun decodeToWav(source: File, destination: File): File
}

class MissingOpusDecoderException : IllegalStateException(
    "Raw Opus decoding requires the production libopus JNI implementation."
)
