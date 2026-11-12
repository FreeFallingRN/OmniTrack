package com.omnitrack.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val seriesId = intent.getStringExtra("SERIES_ID") ?: return
        val action = intent.action ?: return
        CoroutineScope(Dispatchers.IO).launch {
            when (action) {
                "ACTION_INCREMENT" -> {
                    // Quick actions are handled by the notification service.
                }
                "ACTION_DECREMENT" -> {
                    // Decrement progress logic
                }
            }
        }
    }
}