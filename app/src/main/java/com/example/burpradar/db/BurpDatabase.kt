package com.example.burpradar.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BurpEvent::class], version = 1, exportSchema = false)
abstract class BurpDatabase : RoomDatabase() {
    abstract fun burpEventDao(): BurpEventDao

    companion object {
        @Volatile
        private var INSTANCE: BurpDatabase? = null

        fun getDatabase(context: Context): BurpDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BurpDatabase::class.java,
                    "burp_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
