package com.airecorder.mvp.core.database

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object RecordingNames {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val recorderUtcFormatter = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val timestampPattern = Regex("^\\d{8}_\\d{6}$")
    private val legacyNumberedTitle = Regex("^New recording \\d+$")

    fun timestamp(timestampMillis: Long): String = Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(timestampFormatter)

    fun fromRecorderFile(remoteName: String, fallbackTimestampMillis: Long): String {
        val baseName = File(remoteName).nameWithoutExtension
        return baseName.takeIf(timestampPattern::matches) ?: timestamp(fallbackTimestampMillis)
    }

    fun utcTimestampMillisFromRecorderFile(remoteName: String): Long? {
        val baseName = File(remoteName).nameWithoutExtension
        if (!timestampPattern.matches(baseName)) return null
        return runCatching {
            LocalDateTime.parse(baseName, recorderUtcFormatter)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()
    }

    fun isLegacyGeneratedTitle(title: String): Boolean =
        title == "Bluetooth live preview" || legacyNumberedTitle.matches(title)
}
