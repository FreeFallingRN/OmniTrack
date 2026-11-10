package com.omnitrack.app.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    val title: String,
    val cleanTitle: String?,
    val type: String,
    val userStatus: String,
    val publishingStatus: String,
    val currentCumulativeUnit: Double,
    val totalUnits: Double?,
    val unitName: String,
    val rating: Int?,
    val notes: String?,
    val isActiveTarget: Boolean,
    val updatedAt: String
)

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY updatedAt DESC")
    fun getAllSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id LIMIT 1")
    suspend fun getSeriesById(id: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE isActiveTarget = 1 LIMIT 1")
    suspend fun getActiveTarget(): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(series: List<SeriesEntity>)

    @Query("UPDATE series SET currentCumulativeUnit = :newUnit, updatedAt = CURRENT_TIMESTAMP WHERE id = :id")
    suspend fun updateProgress(id: String, newUnit: Double)

    @Query("UPDATE series SET isActiveTarget = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setActiveTarget(id: String)
}

@Database(entities = [SeriesEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun seriesDao(): SeriesDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "omnitrack_local.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}