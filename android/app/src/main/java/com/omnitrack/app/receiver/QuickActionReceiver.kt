package com.omnitrack.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omnitrack.app.data.local.AppDatabase
import com.omnitrack.app.service.ActionNotificationService
import com.omnitrack.app.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val seriesId = intent.getStringExtra("SERIES_ID") ?: return
        val delta = intent.getDoubleExtra("DELTA", 0.0)

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val series = db.seriesDao().getSeriesById(seriesId)

                val prevUnit = series?.currentCumulativeUnit ?: 0.0
                val newUnit = (prevUnit + delta).coerceAtLeast(0.0)

                // 1. Instant local Room DB mutation
                db.seriesDao().updateProgress(seriesId, newUnit)

                // 2. Update persistent notification UI
                ActionNotificationService.updateNotification(
                    context = context,
                    seriesId = seriesId,
                    currentUnit = newUnit,
                    title = series?.title ?: "Reading Session"
                )

                // 3. Enqueue sync to Supabase with precise prev/new units
                SyncWorker.enqueue(
                    context = context,
                    seriesId = seriesId,
                    prevUnit = prevUnit,
                    newUnit = newUnit,
                    source = "android_quick_action"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}