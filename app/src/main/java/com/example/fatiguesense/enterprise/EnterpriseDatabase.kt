package com.example.fatiguesense.enterprise.enterprise

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Separate database: local legacy log clearing cannot delete the upload journal.
@Entity(tableName = "devices", primaryKeys = ["orgId", "vehicleId"])
data class PairedDevice(val orgId: String, val vehicleId: String, val espId: String,
    val lastKnownIp: String, val mode: String, val pairedAt: Long, val lastVerifiedAt: Long)

@Entity(tableName = "events", indices = [Index(value = ["uid", "orgId", "uploaded"])])
data class EnterpriseEvent(@PrimaryKey val eventId: String, val uid: String, val orgId: String,
    val vehicleId: String, val deviceId: String, val sessionId: String, val timestamp: Long,
    val triggerType: String, val ear: Float?, val mar: Float?, val durationMs: Long? = null,
    val status: String = "open", val revision: Int = 1, val uploaded: Boolean = false,
    val failure: String? = null)

@Entity(tableName = "sessions")
data class LocalSession(@PrimaryKey val sessionId: String, val uid: String, val orgId: String,
    val endedAt: Long? = null, val uploaded: Boolean = false)

@Entity(tableName = "sync_status", primaryKeys = ["uid", "orgId"])
data class SyncStatus(val uid: String, val orgId: String, val message: String, val lastSuccess: Long? = null)

@Dao
interface EnterpriseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSync(status: SyncStatus)
    @Query("SELECT * FROM sync_status WHERE uid=:uid AND orgId=:org LIMIT 1")
    fun observeSync(uid: String, org: String): Flow<SyncStatus?>
    @Query("SELECT * FROM sync_status WHERE uid=:uid AND orgId=:org LIMIT 1")
    suspend fun sync(uid: String, org: String): SyncStatus?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun saveSession(session: LocalSession)
    @Query("UPDATE sessions SET endedAt=:time WHERE sessionId=:id AND endedAt IS NULL")
    suspend fun closeSession(id: String, time: Long)
    @Query("UPDATE sessions SET endedAt=:time WHERE endedAt IS NULL")
    suspend fun closeOrphanSessions(time: Long)
    @Query("SELECT * FROM sessions WHERE uid=:uid AND orgId=:org AND endedAt IS NOT NULL AND uploaded=0")
    suspend fun pendingClosures(uid: String, org: String): List<LocalSession>
    @Query("UPDATE sessions SET uploaded=1 WHERE sessionId=:id") suspend fun acknowledgeSession(id: String)
    @Query("SELECT * FROM events WHERE uid=:uid AND orgId=:org ORDER BY timestamp DESC")
    suspend fun history(uid: String, org: String): List<EnterpriseEvent>
    @Query("DELETE FROM events WHERE uid=:uid AND orgId=:org AND uploaded=1 AND status!='open'")
    suspend fun clearUploaded(uid: String, org: String)
    @Query("DELETE FROM devices WHERE orgId=:org AND vehicleId=:vehicle")
    suspend fun forgetDevice(org: String, vehicle: String)
    @Query("SELECT * FROM devices WHERE orgId=:org AND vehicleId=:vehicle LIMIT 1")
    suspend fun device(org: String, vehicle: String): PairedDevice?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDevice(device: PairedDevice)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveEvent(event: EnterpriseEvent)
    @Query("SELECT * FROM events WHERE uid=:uid AND orgId=:org AND uploaded=0 AND failure IS NULL ORDER BY timestamp LIMIT 100")
    suspend fun pending(uid: String, org: String): List<EnterpriseEvent>
    @Query("SELECT * FROM events WHERE uid=:uid AND orgId=:org ORDER BY timestamp DESC")
    fun observe(uid: String, org: String): Flow<List<EnterpriseEvent>>
    @Query("UPDATE events SET uploaded=1 WHERE eventId=:id AND revision=:revision")
    suspend fun acknowledge(id: String, revision: Int)
    @Query("UPDATE events SET failure=:reason WHERE eventId=:id AND revision=:revision")
    suspend fun fail(id: String, revision: Int, reason: String)
    @Query("UPDATE events SET status='interrupted', revision=revision+1, uploaded=0 WHERE status='open'")
    suspend fun interruptOrphans()
}

@Database(entities = [PairedDevice::class, EnterpriseEvent::class, LocalSession::class, SyncStatus::class], version = 1, exportSchema = true)
abstract class EnterpriseDatabase : RoomDatabase() {
    abstract fun dao(): EnterpriseDao
    companion object {
        @Volatile private var instance: EnterpriseDatabase? = null
        fun get(context: Context): EnterpriseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, EnterpriseDatabase::class.java,
                "enterprise.db").build().also { instance = it }
        }
    }
}
