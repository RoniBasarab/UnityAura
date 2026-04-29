package com.unityaura.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY eventTimestamp ASC")
    suspend fun getAllEvents(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun getEventCount(): Int

    @Query("DELETE FROM events WHERE uuid IN (SELECT uuid FROM events ORDER BY eventTimestamp ASC LIMIT :count)")
    suspend fun deleteOldestEvents(count: Int)

    @Query("DELETE FROM events WHERE uuid IN (:uuids)")
    suspend fun deleteEventsByUuids(uuids: List<String>)

    @Query("SELECT COUNT(*) FROM events")
    fun observeEventCount(): Flow<Int>
}
