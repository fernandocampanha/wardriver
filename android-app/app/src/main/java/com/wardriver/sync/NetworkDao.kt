package com.wardriver.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class PositionBounds(
    val minLat: Double?,
    val maxLat: Double?,
    val minLon: Double?,
    val maxLon: Double?,
)

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

    @Query("SELECT * FROM networks WHERE lat IS NULL OR lon IS NULL")
    suspend fun getAllWithoutCoordinates(): List<NetworkRecord>

    // mapa: carrega so as redes mais proximas do ponto informado (localizacao do usuario ou
    // centro do bbox conhecido), em vez da tabela inteira - evita o travamento com volume grande.
    @Query(
        """
        SELECT * FROM networks
        WHERE lat IS NOT NULL AND lon IS NOT NULL
        ORDER BY ((lat - :lat) * (lat - :lat)) + ((lon - :lon) * (lon - :lon)) ASC
        LIMIT :limit
        """
    )
    suspend fun getNearest(lat: Double, lon: Double, limit: Int): List<NetworkRecord>

    // mapa: carga sob demanda do que esta dentro do viewport atual, conforme o usuario navega.
    @Query(
        """
        SELECT * FROM networks
        WHERE lat IS NOT NULL AND lon IS NOT NULL
          AND lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        ORDER BY bssid ASC
        LIMIT :limit
        """
    )
    suspend fun getInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int): List<NetworkRecord>

    @Query("SELECT MIN(lat) AS minLat, MAX(lat) AS maxLat, MIN(lon) AS minLon, MAX(lon) AS maxLon FROM networks WHERE lat IS NOT NULL AND lon IS NOT NULL")
    suspend fun getPositionBounds(): PositionBounds

    @Query("SELECT COUNT(*) FROM networks WHERE lat IS NOT NULL AND lon IS NOT NULL")
    suspend fun countWithPosition(): Int

    @Query("SELECT COUNT(*) FROM networks")
    suspend fun count(): Int

    @Query("SELECT * FROM networks WHERE lastSeenAtMs >= :since AND lastSeenAtMs <= :until ORDER BY lastSeenAtMs ASC, bssid ASC")
    suspend fun getSeenBetween(since: Long, until: Long): List<NetworkRecord>

    @Query("SELECT COUNT(*) FROM networks")
    fun observeChanges(): Flow<Int>
}
