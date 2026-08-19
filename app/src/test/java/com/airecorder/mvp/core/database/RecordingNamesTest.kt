package com.airecorder.mvp.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

class RecordingNamesTest {
    @Test
    fun `timestamp uses ios recording name format`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("20260813_052806", RecordingNames.timestamp(1_786_598_886_000L))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `recorder timestamp filename is preserved without extension`() {
        assertEquals(
            "20260813_052806",
            RecordingNames.fromRecorderFile("20260813_052806.opus", 0L)
        )
    }

    @Test
    fun `recorder filename is parsed as utc and can display in user timezone`() {
        val recordedAt = RecordingNames.utcTimestampMillisFromRecorderFile("20260813_052806.opus")
        val localFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.of("Asia/Shanghai"))

        assertEquals("20260813_132806", localFormatter.format(Instant.ofEpochMilli(recordedAt!!)))
    }

    @Test
    fun `invalid recorder filename does not create a recording time`() {
        assertNull(RecordingNames.utcTimestampMillisFromRecorderFile("meeting.opus"))
        assertNull(RecordingNames.utcTimestampMillisFromRecorderFile("20260230_120000.opus"))
    }

    @Test
    fun `only old generated titles are eligible for migration`() {
        assertEquals(true, RecordingNames.isLegacyGeneratedTitle("New recording 3"))
        assertEquals(true, RecordingNames.isLegacyGeneratedTitle("Bluetooth live preview"))
        assertEquals(false, RecordingNames.isLegacyGeneratedTitle("Customer interview"))
    }
}
