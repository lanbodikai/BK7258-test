package com.airecorder.mvp.core.ble

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.airecorder.mvp.core.database.RecordingNames
import java.io.File
import java.io.IOException

/** Publishes a copy of a completed BLE preview where users can retrieve it. */
class PublicBlePreviewExporter(private val context: Context) {
    fun export(sourceFile: File, startedAtMillis: Long): Uri {
        check(sourceFile.isFile && sourceFile.length() > 0L) {
            "BLE-LIVE-PUBLIC-SAVE-001 | Bluetooth preview source file is unavailable"
        }

        val displayName = "${RecordingNames.timestamp(startedAtMillis)}.opus"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            // Board Opus uses a raw length-prefixed stream rather than an Ogg container.
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/AI Recorder"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("BLE-LIVE-PUBLIC-SAVE-002 | Android could not create Downloads/AI Recorder/$displayName")

        try {
            context.contentResolver.openOutputStream(uri, "w")?.buffered(BUFFER_SIZE).use { destination ->
                requireNotNull(destination) {
                    "BLE-LIVE-PUBLIC-SAVE-003 | Android could not open the public preview file"
                }
                sourceFile.inputStream().buffered(BUFFER_SIZE).use { source -> source.copyTo(destination, BUFFER_SIZE) }
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw IOException("BLE-LIVE-PUBLIC-SAVE-004 | Could not save Bluetooth preview to Downloads", error)
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
