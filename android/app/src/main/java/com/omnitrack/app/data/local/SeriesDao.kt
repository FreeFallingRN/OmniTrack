package com.omnitrack.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {

    @Query("SELECT * FROM series ORDER BY isActiveTarget DESC, title ASC")
    fun getAllSeries(): Flow<List<SeriesEntity>>

    @Query("UPDATE series SET currentCumulativeUnit = :newProgress WHERE id = :id")
    suspend fun updateProgress(id: String, newProgress: Double)

    @Query("UPDATE series SET isActiveTarget = :isActive WHERE id = :id")
    suspend fun updateActiveTargetState(id: String, isActive: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: SeriesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(seriesList: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE id = :id")
    suspend fun deleteSeriesById(id: String)
}