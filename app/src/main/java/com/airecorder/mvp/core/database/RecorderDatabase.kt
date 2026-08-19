package com.airecorder.mvp.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import net.sqlcipher.database.SupportFactory
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class TransferState { DISCOVERED, DOWNLOADING, VALIDATING, READY, FAILED }
enum class ProcessingState { NOT_QUEUED, QUEUED, UPLOADING, TRANSCRIBING, SUMMARIZING, COMPLETE, FAILED }
enum class AudioFormat { RAW_OPUS, OGG_OPUS }
enum class LocalCacheState { CACHED, EVICTED }

data class LocalCacheCandidate(val id: String, val bytes: Long, val lastAccessedAt: Long)

object LocalCachePolicy {
    fun selectEvictions(currentBytes: Long, maxBytes: Long, candidates: List<LocalCacheCandidate>): List<String> {
        require(currentBytes >= 0) { "Current cache size cannot be negative" }
        require(maxBytes > 0) { "Local cache limit must be positive" }
        require(candidates.all { it.bytes >= 0 }) { "Cache entry size cannot be negative" }
        if (currentBytes <= maxBytes) return emptyList()

        var remaining = currentBytes
        val selected = mutableListOf<String>()
        for (candidate in candidates.sortedBy { it.lastAccessedAt }) {
            if (remaining <= maxBytes) break
            selected += candidate.id
            remaining -= candidate.bytes
        }
        return selected
    }
}

class DatabaseConverters {
    @TypeConverter fun transferStateToString(value: TransferState): String = value.name
    @TypeConverter fun transferStateFromString(value: String): TransferState = TransferState.valueOf(value)
    @TypeConverter fun processingStateToString(value: ProcessingState): String = value.name
    @TypeConverter fun processingStateFromString(value: String): ProcessingState = ProcessingState.valueOf(value)
    @TypeConverter fun audioFormatToString(value: AudioFormat): String = value.name
    @TypeConverter fun audioFormatFromString(value: String): AudioFormat = AudioFormat.valueOf(value)
    @TypeConverter fun localCacheStateToString(value: LocalCacheState): String = value.name
    @TypeConverter fun localCacheStateFromString(value: String): LocalCacheState = LocalCacheState.valueOf(value)
}

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val hardwareId: String? = null,
    val firmwareVersion: String? = null,
    val protocolVersion: String? = null,
    val lastSeenAt: Long
)

@Entity(
    tableName = "recordings",
    indices = [Index(value = ["sourceFingerprint"], unique = true), Index(value = ["createdAt"])]
)
data class RecordingEntity(
    @PrimaryKey val id: String,
    val deviceId: String?,
    val remoteName: String,
    val sourceFingerprint: String?,
    val rawAudioPath: String?,
    val audioFormat: AudioFormat,
    val title: String,
    val createdAt: Long,
    val durationMillis: Long,
    val byteSize: Long,
    val scene: Int?,
    val transferState: TransferState,
    val processingState: ProcessingState,
    val failureMessage: String? = null,
    val localCacheState: LocalCacheState = LocalCacheState.CACHED,
    val lastAccessedAt: Long = 0L
)

@Entity(tableName = "processing_jobs", indices = [Index(value = ["recordingId"], unique = true)])
data class ProcessingJobEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val backendJobId: String? = null,
    val state: ProcessingState,
    val attempts: Int = 0,
    val updatedAt: Long,
    val error: String? = null
)

@Entity(tableName = "transcript_segments", indices = [Index(value = ["recordingId", "startMillis"])])
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val startMillis: Long,
    val endMillis: Long,
    val speaker: String?,
    val text: String
)

@Entity(tableName = "action_items", indices = [Index(value = ["recordingId"])])
data class ActionItemEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val text: String,
    val completed: Boolean = false
)

@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val recordingId: String,
    val template: String,
    val title: String,
    val content: String
)

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(device: DeviceEntity)
    @Query("SELECT * FROM devices WHERE id = :id") suspend fun find(id: String): DeviceEntity?
    @Query("SELECT * FROM devices ORDER BY lastSeenAt DESC LIMIT 1") suspend fun mostRecentlySeen(): DeviceEntity?
    @Query("UPDATE devices SET displayName = :displayName WHERE id = :id") suspend fun rename(id: String, displayName: String)
}

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(recording: RecordingEntity)
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC") fun observeAll(): Flow<List<RecordingEntity>>
    @Query("SELECT * FROM recordings WHERE id = :id") suspend fun find(id: String): RecordingEntity?
    @Query("SELECT * FROM recordings WHERE id = :id") fun observe(id: String): Flow<RecordingEntity?>
    @Query("SELECT * FROM recordings WHERE sourceFingerprint = :fingerprint LIMIT 1") suspend fun findByFingerprint(fingerprint: String): RecordingEntity?
    @Query("SELECT * FROM recordings WHERE deviceId = :deviceId AND remoteName = :remoteName AND byteSize = :byteSize LIMIT 1")
    suspend fun findImportedRemote(deviceId: String, remoteName: String, byteSize: Long): RecordingEntity?
    @Query("SELECT title FROM recordings") suspend fun allTitles(): List<String>
    @Query("SELECT * FROM recordings WHERE title = 'Bluetooth live preview' OR title LIKE 'New recording %'")
    suspend fun legacyGeneratedTitles(): List<RecordingEntity>
    @Query("SELECT * FROM recordings WHERE deviceId IS NOT NULL AND (sourceFingerprint IS NULL OR sourceFingerprint NOT LIKE 'ble-live:%')")
    suspend fun importedRecorderFiles(): List<RecordingEntity>
    @Query("SELECT * FROM recordings WHERE sourceFingerprint LIKE 'ble-live:%' AND processingState = 'NOT_QUEUED'")
    suspend fun unqueuedBleLivePreviews(): List<RecordingEntity>
    @Query("UPDATE recordings SET title = :title WHERE id = :id") suspend fun rename(id: String, title: String)
    @Query("UPDATE recordings SET createdAt = :createdAt WHERE id = :id")
    suspend fun updateCreatedAt(id: String, createdAt: Long)
    @Query("UPDATE recordings SET transferState = :transfer, processingState = :processing, failureMessage = :error WHERE id = :id")
    suspend fun updateState(id: String, transfer: TransferState, processing: ProcessingState, error: String?)
    @Query("UPDATE recordings SET lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long)
    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM recordings WHERE localCacheState = 'CACHED' AND rawAudioPath IS NOT NULL")
    suspend fun cachedAudioBytes(): Long
    @Query("SELECT * FROM recordings WHERE localCacheState = 'CACHED' AND processingState = 'COMPLETE' AND rawAudioPath IS NOT NULL ORDER BY lastAccessedAt ASC")
    suspend fun cacheEvictionCandidates(): List<RecordingEntity>
    @Query("UPDATE recordings SET rawAudioPath = NULL, localCacheState = 'EVICTED' WHERE id = :id")
    suspend fun markCacheEvicted(id: String)
    @Query("UPDATE recordings SET localCacheState = 'CACHED', lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun markCacheCached(id: String, timestamp: Long)
    @Query("DELETE FROM recordings WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface ProcessingJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(job: ProcessingJobEntity)
    @Query("SELECT * FROM processing_jobs WHERE recordingId = :recordingId") suspend fun findForRecording(recordingId: String): ProcessingJobEntity?
    @Query("DELETE FROM processing_jobs WHERE recordingId = :recordingId") suspend fun deleteForRecording(recordingId: String)
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMillis") fun observe(recordingId: String): Flow<List<TranscriptSegmentEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(segments: List<TranscriptSegmentEntity>)
    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId") suspend fun deleteForRecording(recordingId: String)
}

@Dao
interface ActionItemDao {
    @Query("SELECT * FROM action_items WHERE recordingId = :recordingId") fun observe(recordingId: String): Flow<List<ActionItemEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceAll(items: List<ActionItemEntity>)
    @Query("DELETE FROM action_items WHERE recordingId = :recordingId") suspend fun deleteForRecording(recordingId: String)
    @Query("UPDATE action_items SET completed = :completed WHERE id = :id") suspend fun setCompleted(id: String, completed: Boolean)
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM summaries WHERE recordingId = :recordingId") fun observe(recordingId: String): Flow<SummaryEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(summary: SummaryEntity)
    @Query("DELETE FROM summaries WHERE recordingId = :recordingId") suspend fun deleteForRecording(recordingId: String)
}

@Database(
    entities = [DeviceEntity::class, RecordingEntity::class, ProcessingJobEntity::class, TranscriptSegmentEntity::class, ActionItemEntity::class, SummaryEntity::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class RecorderDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun recordingDao(): RecordingDao
    abstract fun processingJobDao(): ProcessingJobDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun actionItemDao(): ActionItemDao
    abstract fun summaryDao(): SummaryDao

    companion object {
        fun create(context: Context): RecorderDatabase {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val preferences = EncryptedSharedPreferences.create(
                context,
                "recorder_database_key",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val passphrase = preferences.getString("passphrase", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("passphrase", it).apply()
            }
            return Room.databaseBuilder(context, RecorderDatabase::class.java, "ai-recorder.db")
                .openHelperFactory(SupportFactory(passphrase.toByteArray(Charsets.UTF_8)))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE recordings ADD COLUMN audioFormat TEXT NOT NULL DEFAULT 'RAW_OPUS'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE recordings ADD COLUMN localCacheState TEXT NOT NULL DEFAULT 'CACHED'")
                database.execSQL("ALTER TABLE recordings ADD COLUMN lastAccessedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE recordings SET lastAccessedAt = createdAt WHERE lastAccessedAt = 0")
            }
        }
    }
}

class RecordingRepository(private val database: RecorderDatabase, private val appFilesDir: File) {
    fun observeLibrary(): Flow<List<RecordingEntity>> = database.recordingDao().observeAll()
    fun observeDetail(id: String): Flow<RecordingDetail> = kotlinx.coroutines.flow.combine(
        database.recordingDao().observe(id),
        database.transcriptDao().observe(id),
        database.summaryDao().observe(id),
        database.actionItemDao().observe(id)
    ) { recording, transcript, summary, actions -> RecordingDetail(recording, transcript, summary, actions) }

    suspend fun rename(recordingId: String, title: String) = database.recordingDao().rename(recordingId, title)
    suspend fun setActionItemCompleted(actionId: String, completed: Boolean) = database.actionItemDao().setCompleted(actionId, completed)

    suspend fun importDownloaded(
        deviceId: String?,
        remoteName: String,
        downloadedFile: File,
        durationMillis: Long,
        scene: Int?,
        sourceByteSize: Long = downloadedFile.length()
    ): RecordingEntity {
        val fingerprint = downloadedFile.sha256()
        database.recordingDao().findByFingerprint(fingerprint)?.let {
            downloadedFile.delete()
            return it
        }
        val target = File(appFilesDir, "recordings/$fingerprint.opus").apply { parentFile?.mkdirs() }
        if (!downloadedFile.renameTo(target)) downloadedFile.copyTo(target, overwrite = true).also { downloadedFile.delete() }
        val importedAt = System.currentTimeMillis()
        val createdAt = RecordingNames.utcTimestampMillisFromRecorderFile(remoteName) ?: importedAt
        val recording = RecordingEntity(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId,
            remoteName = remoteName,
            sourceFingerprint = fingerprint,
            rawAudioPath = target.absolutePath,
            audioFormat = AudioFormat.RAW_OPUS,
            title = RecordingNames.fromRecorderFile(remoteName, createdAt),
            createdAt = createdAt,
            durationMillis = durationMillis,
            byteSize = sourceByteSize,
            scene = scene,
            transferState = TransferState.READY,
            processingState = ProcessingState.NOT_QUEUED,
            lastAccessedAt = importedAt
        )
        database.recordingDao().insert(recording)
        return recording
    }

    suspend fun importPhoneTestRecording(recordedFile: File, durationMillis: Long): RecordingEntity {
        val fingerprint = recordedFile.sha256()
        database.recordingDao().findByFingerprint(fingerprint)?.let {
            recordedFile.delete()
            return it
        }
        val target = File(appFilesDir, "recordings/$fingerprint.ogg").apply { parentFile?.mkdirs() }
        if (!recordedFile.renameTo(target)) recordedFile.copyTo(target, overwrite = true).also { recordedFile.delete() }
        val createdAt = System.currentTimeMillis()
        val recording = RecordingEntity(
            id = UUID.randomUUID().toString(),
            deviceId = null,
            remoteName = "phone-test-$createdAt.ogg",
            sourceFingerprint = fingerprint,
            rawAudioPath = target.absolutePath,
            audioFormat = AudioFormat.OGG_OPUS,
            title = RecordingNames.timestamp(createdAt),
            createdAt = createdAt,
            durationMillis = durationMillis,
            byteSize = target.length(),
            scene = null,
            transferState = TransferState.READY,
            processingState = ProcessingState.NOT_QUEUED,
            lastAccessedAt = createdAt
        )
        database.recordingDao().insert(recording)
        return recording
    }

    suspend fun importBleLivePreview(
        deviceId: String?,
        previewFile: File,
        durationMillis: Long,
        scene: Int?,
        startedAtMillis: Long
    ): RecordingEntity {
        val rawFingerprint = previewFile.sha256()
        // A BLE preview can be byte-for-byte equal to a later TF-card recording.
        // Its identity must stay separate so the final file is not skipped as a duplicate.
        val fingerprint = "ble-live:$rawFingerprint"
        database.recordingDao().findByFingerprint(fingerprint)?.let {
            previewFile.delete()
            return it
        }
        val target = File(appFilesDir, "recordings/live-$rawFingerprint.opus").apply { parentFile?.mkdirs() }
        if (!previewFile.renameTo(target)) previewFile.copyTo(target, overwrite = true).also { previewFile.delete() }
        val recording = RecordingEntity(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId,
            remoteName = "${RecordingNames.timestamp(startedAtMillis)}.opus",
            sourceFingerprint = fingerprint,
            rawAudioPath = target.absolutePath,
            audioFormat = AudioFormat.RAW_OPUS,
            title = RecordingNames.timestamp(startedAtMillis),
            createdAt = startedAtMillis,
            durationMillis = durationMillis,
            byteSize = target.length(),
            scene = scene,
            transferState = TransferState.READY,
            processingState = ProcessingState.NOT_QUEUED,
            lastAccessedAt = System.currentTimeMillis()
        )
        database.recordingDao().insert(recording)
        return recording
    }

    suspend fun markProcessing(recordingId: String, state: ProcessingState, error: String? = null) {
        val current = requireNotNull(database.recordingDao().find(recordingId))
        database.recordingDao().updateState(recordingId, current.transferState, state, error)
    }

    suspend fun find(recordingId: String): RecordingEntity? = database.recordingDao().find(recordingId)

    suspend fun migrateLegacyGeneratedTitles() {
        database.recordingDao().legacyGeneratedTitles().forEach { recording ->
            if (RecordingNames.isLegacyGeneratedTitle(recording.title)) {
                database.recordingDao().rename(
                    recording.id,
                    RecordingNames.fromRecorderFile(recording.remoteName, recording.createdAt)
                )
            }
        }
    }

    suspend fun migrateRecorderFileTimesFromUtcNames() {
        database.recordingDao().importedRecorderFiles().forEach { recording ->
            val recordedAt = RecordingNames.utcTimestampMillisFromRecorderFile(recording.remoteName)
                ?: return@forEach
            if (recordedAt != recording.createdAt) {
                database.recordingDao().updateCreatedAt(recording.id, recordedAt)
            }
        }
    }

    suspend fun unqueuedBleLivePreviews(): List<RecordingEntity> =
        database.recordingDao().unqueuedBleLivePreviews()

    suspend fun isRemoteImported(deviceId: String, remoteName: String, byteSize: Long): Boolean =
        database.recordingDao().findImportedRemote(deviceId, remoteName, byteSize) != null

    suspend fun touch(recordingId: String) {
        database.recordingDao().touch(recordingId, System.currentTimeMillis())
    }

    suspend fun markCacheCached(recordingId: String) {
        database.recordingDao().markCacheCached(recordingId, System.currentTimeMillis())
    }

    suspend fun evictLocalCache(recordingId: String): Boolean {
        val recording = database.recordingDao().find(recordingId) ?: return false
        val path = recording.rawAudioPath?.let(::File)
        if (path?.exists() == true && !path.delete()) return false
        // LRU applies to large audio files. Keep the small transcript and
        // summary available locally; the backend intentionally deletes audio.
        database.recordingDao().markCacheEvicted(recordingId)
        return true
    }

    suspend fun enforceLocalCache(maxBytes: Long = DEFAULT_LOCAL_CACHE_BYTES): Int {
        require(maxBytes > 0) { "Local cache limit must be positive" }
        var cachedBytes = database.recordingDao().cachedAudioBytes()
        if (cachedBytes <= maxBytes) return 0

        val eligible = database.recordingDao().cacheEvictionCandidates().mapNotNull { candidate ->
            val job = database.processingJobDao().findForRecording(candidate.id)
            if (job?.backendJobId == null || job.state != ProcessingState.COMPLETE) return@mapNotNull null
            val bytes = candidate.rawAudioPath?.let(::File)?.takeIf(File::exists)?.length() ?: candidate.byteSize
            LocalCacheCandidate(candidate.id, bytes, candidate.lastAccessedAt)
        }
        val selected = LocalCachePolicy.selectEvictions(cachedBytes, maxBytes, eligible)
        var evicted = 0
        selected.forEach { recordingId ->
            if (evictLocalCache(recordingId)) evicted += 1
        }
        return evicted
    }

    suspend fun delete(recordingId: String) {
        database.recordingDao().find(recordingId)?.rawAudioPath?.let { File(it).delete() }
        database.transcriptDao().deleteForRecording(recordingId)
        database.actionItemDao().deleteForRecording(recordingId)
        database.summaryDao().deleteForRecording(recordingId)
        database.processingJobDao().deleteForRecording(recordingId)
        database.recordingDao().delete(recordingId)
    }

    private companion object {
        const val DEFAULT_LOCAL_CACHE_BYTES = 512L * 1024L * 1024L
    }
}

data class RecordingDetail(
    val recording: RecordingEntity?,
    val transcript: List<TranscriptSegmentEntity>,
    val summary: SummaryEntity?,
    val actionItems: List<ActionItemEntity>
)

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
