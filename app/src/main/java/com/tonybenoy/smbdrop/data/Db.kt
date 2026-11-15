package com.tonybenoy.smbdrop.data

import android.content.Context
import androidx.sqlite.execSQL
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity
data class Destination(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String,
    val host: String,
    val share: String,
    val basePath: String = "",
    val username: String = "",
    val domain: String = "",
    val guest: Boolean = false,
    val secret: String = "",          // Keystore-encrypted, base64
    val colour: Int = 0xFF3D7BFF.toInt(),
    val lastSeen: Long = 0,
    val online: Boolean = true,
    val lastIp: String = "",
)

@Entity
data class RoutingRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val position: Int = 0,
    val kind: String,                 // MIME | EXT | APP
    val match: String,                // "image/,video/" | "pdf,docx" | "com.scanner.app"
    val destinationId: Long,
    val pathTemplate: String,
    val enabled: Boolean = true,
)

@Entity
data class BackupSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val type: String,                 // CAMERA | SCREENSHOTS | FOLDER
    val treeUri: String? = null,
    val destinationId: Long = 0,      // 0 = use routing rules
    val pathTemplate: String = "Photos/{yyyy}/{MM}",
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val enabled: Boolean = true,
    val onlyNewFrom: Long = 0,        // epoch seconds; 0 = everything
    val freeUpDays: Int = 0,          // 0 = never
    val lastRun: Long = 0,
    val backedUp: Int = 0,
)

object TState {
    const val QUEUED = "QUEUED"; const val ACTIVE = "ACTIVE"; const val PAUSED = "PAUSED"
    const val WAITING = "WAITING"; const val FAILED = "FAILED"; const val DONE = "DONE"
}

@Entity(indices = [Index("state"), Index("sourceKey")])
data class TransferItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String = "up",     // up | down
    val sourceUri: String,            // up: content/file uri; down: remote path
    val destinationId: Long,
    val targetPath: String,           // up: remote path incl. file name; down: file name
    val name: String,
    val size: Long = -1,
    val bytesDone: Long = 0,
    val state: String = TState.QUEUED,
    val error: String? = null,
    val attempts: Int = 0,
    val batchId: Long = 0,
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val conflict: String = "KEEP_BOTH", // SKIP | KEEP_BOTH | REPLACE
    val mobileOk: Boolean = false,
    val needsCharging: Boolean = false,
    val sourceKey: String? = null,
    val sourceModified: Long = 0,
    val mime: String? = null,
    val speed: Long = 0,
    val origin: String = "share",
    val deleteSourceAfter: Boolean = false,
)

@Entity
data class TransferBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val origin: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity
data class UploadedFile(
    @PrimaryKey val sourceKey: String,
    val size: Long,
    val modified: Long,
    val remotePath: String,
    val destinationId: Long,
    val uploadedAt: Long = System.currentTimeMillis(),
    val sourceUri: String? = null,
)

@Entity
data class Favourite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val destinationId: Long,
    val path: String,
)

@Entity
data class OfflineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val destinationId: Long,
    val path: String,
    val isDir: Boolean,
    val name: String,
    val lastSync: Long = 0,
    val bytes: Long = 0,
    val error: String? = null,
)

@Dao
interface OfflineDao {
    @Query("SELECT * FROM OfflineItem ORDER BY name") fun all(): Flow<List<OfflineItem>>
    @Query("SELECT * FROM OfflineItem") suspend fun list(): List<OfflineItem>
    @Query("SELECT * FROM OfflineItem WHERE destinationId=:d AND path=:p LIMIT 1") suspend fun find(d: Long, p: String): OfflineItem?
    @Insert suspend fun insert(o: OfflineItem): Long
    @Update suspend fun update(o: OfflineItem)
    @Delete suspend fun delete(o: OfflineItem)
}

@Dao
interface DestinationDao {
    @Query("SELECT * FROM Destination ORDER BY id") fun all(): Flow<List<Destination>>
    @Query("SELECT * FROM Destination ORDER BY id") suspend fun list(): List<Destination>
    @Query("SELECT * FROM Destination WHERE id=:id") suspend fun get(id: Long): Destination?
    @Insert suspend fun insert(d: Destination): Long
    @Update suspend fun update(d: Destination)
    @Query("DELETE FROM Destination WHERE id=:id") suspend fun delete(id: Long)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM RoutingRule ORDER BY position, id") fun all(): Flow<List<RoutingRule>>
    @Query("SELECT * FROM RoutingRule WHERE enabled=1 ORDER BY position, id") suspend fun enabled(): List<RoutingRule>
    @Insert suspend fun insert(r: RoutingRule): Long
    @Update suspend fun update(r: RoutingRule)
    @Delete suspend fun delete(r: RoutingRule)
    @Query("DELETE FROM RoutingRule WHERE destinationId=:d") suspend fun deleteForDest(d: Long)
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM BackupSource ORDER BY id") fun all(): Flow<List<BackupSource>>
    @Query("SELECT * FROM BackupSource WHERE enabled=1") suspend fun enabled(): List<BackupSource>
    @Query("SELECT * FROM BackupSource WHERE id=:id") suspend fun get(id: Long): BackupSource?
    @Insert suspend fun insert(b: BackupSource): Long
    @Update suspend fun update(b: BackupSource)
    @Delete suspend fun delete(b: BackupSource)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM TransferItem ORDER BY priority DESC, id") fun all(): Flow<List<TransferItem>>
    @Query("SELECT * FROM TransferItem WHERE state IN ('QUEUED','ACTIVE','PAUSED','WAITING','FAILED') ORDER BY priority DESC, id")
    fun open(): Flow<List<TransferItem>>
    @Query("SELECT * FROM TransferItem WHERE state='DONE' ORDER BY id DESC LIMIT 60") fun recentDone(): Flow<List<TransferItem>>
    @Query("SELECT * FROM TransferItem WHERE state='QUEUED' ORDER BY priority DESC, id LIMIT 200") suspend fun queued(): List<TransferItem>
    @Query("SELECT * FROM TransferItem WHERE id=:id") suspend fun get(id: Long): TransferItem?
    @Query("SELECT state FROM TransferItem WHERE id=:id") suspend fun stateOf(id: Long): String?
    @Insert suspend fun insert(t: TransferItem): Long
    @Insert suspend fun insertAll(t: List<TransferItem>): List<Long>
    @Update suspend fun update(t: TransferItem)
    @Query("UPDATE TransferItem SET bytesDone=:b, speed=:s WHERE id=:id") suspend fun progress(id: Long, b: Long, s: Long)
    @Query("UPDATE TransferItem SET state=:state, error=:err WHERE id=:id") suspend fun setState(id: Long, state: String, err: String? = null)
    @Query("UPDATE TransferItem SET state='QUEUED', error=NULL WHERE state IN ('WAITING') ") suspend fun wakeWaiting()
    @Query("UPDATE TransferItem SET state='QUEUED', error=NULL WHERE id=:id AND state='WAITING'") suspend fun wakeWaitingOne(id: Long)
    @Query("UPDATE TransferItem SET state='QUEUED' WHERE state='ACTIVE'") suspend fun resetActive()
    @Query("UPDATE TransferItem SET state='PAUSED' WHERE state IN ('QUEUED','ACTIVE','WAITING')") suspend fun pauseAll()
    @Query("UPDATE TransferItem SET state='QUEUED' WHERE state='PAUSED'") suspend fun resumeAll()
    @Query("UPDATE TransferItem SET state='QUEUED', error=NULL, attempts=0 WHERE state='FAILED'") suspend fun retryAllFailed()
    @Query("UPDATE TransferItem SET state='QUEUED', error=NULL, attempts=0 WHERE id=:id") suspend fun requeue(id: Long)
    @Query("UPDATE TransferItem SET priority=(SELECT IFNULL(MAX(priority),0)+1 FROM TransferItem), state='QUEUED' WHERE id=:id") suspend fun sendNow(id: Long)
    @Query("DELETE FROM TransferItem WHERE id=:id") suspend fun delete(id: Long)
    @Query("DELETE FROM TransferItem WHERE state='DONE'") suspend fun clearDone()
    @Query("DELETE FROM TransferItem WHERE state IN ('QUEUED','ACTIVE','PAUSED','WAITING','FAILED')") suspend fun clearOpen()
    @Query("DELETE FROM TransferItem WHERE state='FAILED'") suspend fun clearFailedItems()
    @Query("SELECT * FROM TransferItem WHERE state IN ('QUEUED','ACTIVE','PAUSED','WAITING','FAILED') AND sourceUri LIKE 'file:%'") suspend fun openFileItems(): List<TransferItem>
    @Query("SELECT COUNT(*) FROM TransferItem WHERE sourceKey=:key AND state!='DONE' AND state!='FAILED'") suspend fun pendingWithKey(key: String): Int
    @Query("SELECT COUNT(*) FROM TransferItem WHERE state='QUEUED' OR state='ACTIVE'") suspend fun runnableCount(): Int
    @Query("SELECT COUNT(*) FROM TransferItem WHERE state='ACTIVE'") fun activeCount(): Flow<Int>
    @Query("SELECT * FROM TransferBatch WHERE id=:id") suspend fun batch(id: Long): TransferBatch?
    @Insert suspend fun insertBatch(b: TransferBatch): Long
}

@Dao
interface UploadedDao {
    @Query("SELECT * FROM UploadedFile WHERE sourceKey=:key") suspend fun get(key: String): UploadedFile?
    @Query("SELECT sourceKey FROM UploadedFile WHERE destinationId=:d") suspend fun keys(d: Long): List<String>
    @Query("SELECT * FROM UploadedFile WHERE sourceUri IS NOT NULL AND uploadedAt<:before AND sourceKey LIKE 'media:%'")
    suspend fun olderThan(before: Long): List<UploadedFile>
    @Query("SELECT COUNT(*) FROM UploadedFile WHERE uploadedAt>:since") fun countSince(since: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM UploadedFile") suspend fun total(): Int
    @Query("SELECT * FROM UploadedFile ORDER BY uploadedAt DESC LIMIT 18") fun recent(): Flow<List<UploadedFile>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(u: UploadedFile)
    @Query("DELETE FROM UploadedFile WHERE sourceKey=:key") suspend fun remove(key: String)
}

@Dao
interface FavDao {
    @Query("SELECT * FROM Favourite") fun all(): Flow<List<Favourite>>
    @Insert suspend fun insert(f: Favourite)
    @Query("DELETE FROM Favourite WHERE id=:id") suspend fun delete(id: Long)
}

@Database(
    entities = [Destination::class, RoutingRule::class, BackupSource::class, TransferItem::class,
        TransferBatch::class, UploadedFile::class, Favourite::class, OfflineItem::class],
    version = 2, exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun destinations(): DestinationDao
    abstract fun rules(): RuleDao
    abstract fun backups(): BackupDao
    abstract fun transfers(): TransferDao
    abstract fun uploaded(): UploadedDao
    abstract fun favourites(): FavDao
    abstract fun offline(): OfflineDao

    companion object {
        /** 1 → 2: adds the offline-copies table. Existing servers, rules and history are kept. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS `OfflineItem` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `destinationId` INTEGER NOT NULL, `path` TEXT NOT NULL, `isDir` INTEGER NOT NULL, `name` TEXT NOT NULL, `lastSync` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, `error` TEXT)")
            }
        }
        fun build(c: Context) = Room.databaseBuilder(c, AppDb::class.java, "smbdrop.db")
            .addMigrations(MIGRATION_1_2).fallbackToDestructiveMigrationOnDowngrade(true).build()
    }
}
