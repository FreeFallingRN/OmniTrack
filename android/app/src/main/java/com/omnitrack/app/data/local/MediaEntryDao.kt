package com.omnitrack.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaEntryDao {
    @Query("SELECT * FROM media_entries ORDER BY isActiveTarget DESC, title ASC")
    fun getAllEntries(): Flow<List<MediaEntryEntity>>

    @Query("SELECT * FROM media_entries WHERE seriesId = :seriesId")
    suspend fun getEntriesForSeries(seriesId: String): List<MediaEntryEntity>

    @Query("SELECT * FROM media_entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: String): MediaEntryEntity?

    @Query("DELETE FROM media_entries")
    suspend fun deleteAll()

    @Query("UPDATE media_entries SET currentUnit = :newProgress WHERE id = :id")
    suspend fun updateProgress(id: String, newProgress: Double)

    @Query("UPDATE media_entries SET isActiveTarget = :isActive WHERE id = :id")
    suspend fun updateActiveTargetState(id: String, isActive: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MediaEntryEntity>)

    @androidx.room.Transaction
    suspend fun replaceAll(entries: List<MediaEntryEntity>) {
        deleteAll()
        insertAll(entries)
    }

    @Query("DELETE FROM media_entries WHERE id = :id")
    suspend fun deleteEntryById(id: String)
}
