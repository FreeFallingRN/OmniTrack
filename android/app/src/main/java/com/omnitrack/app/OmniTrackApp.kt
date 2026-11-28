package com.omnitrack.app

import android.app.Application
import com.omnitrack.app.data.AppLogger

class OmniTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(this)
    }
}