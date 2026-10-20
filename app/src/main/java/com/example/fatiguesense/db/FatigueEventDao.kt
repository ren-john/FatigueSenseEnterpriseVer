package com.example.fatiguesense.enterprise.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FatigueEventDao {

    /** Insert a new event. Returns the row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: FatigueEventEntity): Long

    /** All events, newest first — emits on every DB change. */
    @Query("SELECT * FROM fatigue_events ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<FatigueEventEntity>>

    /** All events as a one-shot snapshot (for export). */
    @Query("SELECT * FROM fatigue_events ORDER BY timestampMs DESC")
    suspend fun getAll(): List<FatigueEventEntity>

    /** Delete all rows. */
    @Query("DELETE FROM fatigue_events")
    suspend fun deleteAll()
}
