package com.omnitrack.app.data.repository

import android.content.Context
import com.omnitrack.app.data.SupabaseService
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.data.local.SeriesEntity
import com.omnitrack.app.service.ActionNotificationService
import com.omnitrack.app.worker.SyncWorker
import kotlinx.coroutines.flow.Flow

class SeriesRepository(private val db: AppDatabase) {

    val allSeries: Flow<List<SeriesEntity>> = db.seriesDao().getAllSeries()

    suspend fun refreshFromRemote() {
        val remoteList = SupabaseService.getSeries()
        val entities = remoteList.map { dto ->
            SeriesEntity(
                id = dto.id,
                title = dto.title,
                cleanTitle = dto.cleanTitle,
                type = dto.type,
                userStatus = dto.userStatus,
                publishingStatus = dto.publishingStatus,
                currentCumulativeUnit = dto.currentCumulativeUnit,
                totalUnits = dto.totalUnits,
                unitName = dto.unitName,
                rating = dto.rating,
                notes = dto.notes,
                isActiveTarget = dto.isActiveTarget,
                updatedAt = dto.updatedAt
            )
        }
        db.seriesDao().upsertSeries(entities)
    }

    suspend fun updateProgress(context: Context, series: SeriesEntity, delta: Double) {
        val prevUnit = series.currentCumulativeUnit
        val newUnit = (prevUnit + delta).coerceAtLeast(0.0)

        // 1. Update Room DB
        db.seriesDao().updateProgress(series.id, newUnit)

        // 2. If this series is the pinned target, update status bar notification
        val activeTarget = db.seriesDao().getActiveTarget()
        if (activeTarget?.id == series.id || series.isActiveTarget) {
            ActionNotificationService.updateNotification(
                context = context,
                seriesId = series.id,
                currentUnit = newUnit,
                title = series.title
            )
        }

        // 3. Enqueue background sync to Supabase
        SyncWorker.enqueue(
            context = context,
            seriesId = series.id,
            prevUnit = prevUnit,
            newUnit = newUnit,
            source = "android_ui"
        )
    }

    suspend fun setActiveTarget(seriesId: String) {
        db.seriesDao().setActiveTarget(seriesId)
    }
}