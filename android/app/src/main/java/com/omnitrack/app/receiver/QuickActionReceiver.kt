package com.omnitrack.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omnitrack.app.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val seriesId = intent.getStringExtra("SERIES_ID") ?: return
        val action = intent.action ?: return
        val db = AppDatabase.getInstance(context)

        CoroutineScope(Dispatchers.IO).launch {
            when (action) {
                "ACTION_INCREMENT" -> {
                    // Update progress directly in local database
                    val dao = db.seriesDao()
                    // If you fetch current progress or increment by 1:
                    // dao.updateProgress(seriesId, newProgress)
                }
                "ACTION_DECREMENT" -> {
                    // Decrement progress logic
                }
            }
        }
    }
}