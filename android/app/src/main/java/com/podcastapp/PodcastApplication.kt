package com.podcastapp

import android.app.Application
import com.podcastapp.data.ServiceLocator

class PodcastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
