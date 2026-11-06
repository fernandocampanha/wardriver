package com.wardriver.sync

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NetworkRecord::class], version = 1, exportSchema = false)
abstract class WardriverDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao

    companion object {
        @Volatile private var instance: WardriverDatabase? = null

        fun getInstance(context: Context): WardriverDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WardriverDatabase::class.java,
                    "wardriver.db"
                ).build().also { instance = it }
            }
    }
}
