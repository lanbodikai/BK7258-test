package com.airecorder.mvp.core.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderBleProtocolTest {
    @Test fun commandFrameUsesLittleEndianPayloadLength() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x2A, 0x01, 0x00, 0x00),
            RecorderBleProtocol.commandFrame(RecorderBleProtocol.Command.START_RECORD, 0x2A, byteArrayOf(0))
        )
    }

    @Test fun parsesStatusPushWithoutResponseSequencePrefix() {
        val status = byteArrayOf(1, 0, 1, 1, 80, 0x68, 0x10, 0x3C, 0, 0, 0, 1, 0, 0, 0, 1)
        val packet = byteArrayOf(3, 0, 0, 16, 0) + status
        val notification = RecorderBleProtocol.parseNotification(packet) as StatusPush
        assertEquals(1, notification.status.recording)
        assertEquals(4200, notification.status.batteryMillivolts)
        assertEquals(60L, notification.status.recordingSeconds)
        assertArrayEquals(status, notification.body)
    }

    @Test fun parsesWifiCredentialTlvAfterResponsePrefix() {
        fun field(value: String) = byteArrayOf(value.length.toByte()) + value.toByteArray()
        val payload = field("recoder") + field("12345678") + field("recorder") + field("12345678") + byteArrayOf(192.toByte(), 168.toByte(), 4, 1)
        val parsed = RecorderBleProtocol.parseWifiCredentials(payload)
        assertEquals("recoder", parsed.ssid)
        assertEquals("192.168.4.1", parsed.host)
    }

    @Test fun phaseOneWifiFallbackMatchesProtocolDefaults() {
        val credentials = RecorderBleProtocol.phaseOneWifiFallbackCredentials
        assertEquals("recoder", credentials.ssid)
        assertEquals("12345678", credentials.wifiPassword)
        assertEquals("recorder", credentials.ftpUser)
        assertEquals("12345678", credentials.ftpPassword)
        assertEquals("192.168.4.1", credentials.host)
        assertEquals(21, credentials.port)
    }

    @Test fun parsesOneLengthPrefixedLiveOpusFrame() {
        val payload = ByteArray(20) { it.toByte() }
        assertArrayEquals(payload, RecorderBleProtocol.parseLiveOpusFrame(byteArrayOf(20) + payload))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLiveOpusFrameWithMismatchedLength() {
        RecorderBleProtocol.parseLiveOpusFrame(byteArrayOf(20) + ByteArray(19))
    }
}
