package com.example.burpradar.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BurpEventDao {
    @Query("SELECT * FROM burp_events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<BurpEvent>>

    @Insert
    suspend fun insertEvent(event: BurpEvent): Long

    @Query("DELETE FROM burp_events WHERE id = :id")
    suspend fun deleteEvent(id: Int)

    @Query("DELETE FROM burp_events")
    suspend fun deleteAllEvents()

    @Query("SELECT * FROM burp_events ORDER BY timestamp ASC")
    suspend fun getAllEventsAscending(): List<BurpEvent>
}
