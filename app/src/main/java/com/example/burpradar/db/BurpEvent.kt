package com.example.burpradar.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "burp_events")
data class BurpEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val videoPath: String,
    val durationMs: Long,
    val confidence: Float
)
