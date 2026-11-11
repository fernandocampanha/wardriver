package com.wardriver.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: NetworkRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<NetworkRecord>)

    @Query("SELECT * FROM networks ORDER BY bssid ASC")
    suspend fun getAll(): List<NetworkRecord>

    @Query("SELECT * FROM networks WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY bssid ASC")
    suspend fun getAllWithCoordinates(): List<NetworkRecord>

    @Query("SELECT COUNT(*) FROM networks")
    suspend fun count(): Int

    @Query("SELECT * FROM networks WHERE lastSeenAtMs >= :since AND lastSeenAtMs <= :until ORDER BY lastSeenAtMs ASC, bssid ASC")
    suspend fun getSeenBetween(since: Long, until: Long): List<NetworkRecord>

    @Query("SELECT COUNT(*) FROM networks")
    fun observeChanges(): Flow<Int>
}
