package com.void.app

import android.app.Application
import com.void.app.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * VOID Application
 *
 * The app shell is MINIMAL - it just:
 * 1. Initializes Koin
 * 2. Sets up core infrastructure
 *
 * All actual logic lives in blocks.
 */
class VoidApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Initialize DI
        startKoin {
            androidContext(this@VoidApp)
            modules(appModule)
        }
    }
}
