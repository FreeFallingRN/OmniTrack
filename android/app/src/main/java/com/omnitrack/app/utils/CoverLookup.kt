package com.omnitrack.app.utils

import android.content.Context
import com.omnitrack.app.utils.scraper.ExternalMediaScraper
import kotlinx.coroutines.runBlocking

object CoverLookup {
    fun findOpenGraphImage(sourceUrl: String, context: Context? = null): String? {
        return runBlocking {
            ExternalMediaScraper.findCoverImage(sourceUrl, context)
        }
    }
}