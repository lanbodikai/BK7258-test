package com.airecorder.mvp.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class RawOpusFramesTest {
    @Test fun validatesConcatenatedFramesAndEstimatesDuration() {
        val bytes = byteArrayOf(20) + ByteArray(20) + byteArrayOf(21) + ByteArray(21)
        val validation = RawOpusFrames.validate(ByteArrayInputStream(bytes))
        assertEquals(2, validation.frames)
        assertEquals(40L, validation.durationMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedFrame() {
        RawOpusFrames.validate(ByteArrayInputStream(byteArrayOf(20, 1, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOggOpusContainerForBoardRecordings() {
        RawOpusFrames.validate(ByteArrayInputStream(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())))
    }

    @Test fun recoversMostlyValidFixed80ByteSlotsByDroppingDamagedSlots() {
        val source = File.createTempFile("recorder-fixed-slots", ".opus")
        val slots = ByteArray(100 * 81)
        repeat(100) { index ->
            val offset = index * 81
            slots[offset] = 80
            java.util.Arrays.fill(slots, offset + 1, offset + 81, (index + 1).toByte())
        }
        listOf(4, 11, 27, 63, 88).forEach { index -> slots[index * 81] = 123 }
        source.writeBytes(slots)

        val recoveryCandidate = RawOpusFrames.recoverFixed80ByteSlots(source)
        assertNotNull(recoveryCandidate)
        val recovery = requireNotNull(recoveryCandidate)
        assertEquals(95, recovery.validation.frames)
        assertEquals(5, recovery.discardedFrames)
        assertEquals(95L * 81L, recovery.recoveredFile.length())

        recovery.recoveredFile.delete()
        source.delete()
    }

    @Test fun rejectsFixedSlotRecoveryWhenTooManySlotsAreDamaged() {
        val source = File.createTempFile("recorder-fixed-slots", ".opus")
        val slots = ByteArray(100 * 81)
        repeat(100) { index -> slots[index * 81] = 80 }
        repeat(6) { index -> slots[index * 81] = 123 }
        source.writeBytes(slots)

        assertNull(RawOpusFrames.recoverFixed80ByteSlots(source))
        source.delete()
    }
}
