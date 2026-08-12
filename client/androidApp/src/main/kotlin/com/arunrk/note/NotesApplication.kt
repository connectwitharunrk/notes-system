package com.arunrk.note

import android.app.Application
import com.arunrk.note.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

/**
 * Koin starts here rather than in the Activity: the graph owns process-lifetime
 * singletons (database, HTTP client), and re-creating them on every
 * configuration change would leak connections.
 */
class NotesApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        initKoin(context = applicationContext) {
            androidLogger()
            androidContext(this@NotesApplication)
        }
    }
}
