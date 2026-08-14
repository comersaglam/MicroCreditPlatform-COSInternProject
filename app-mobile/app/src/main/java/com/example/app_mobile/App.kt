package com.example.app_mobile

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.app_mobile.sync.SyncScheduler
import com.example.app_pos.data.di.ApplicationScope
import com.example.app_pos.model.Repository
import com.example.app_pos.network.NetworkConfig
import com.example.app_pos.network.auth.TokenStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * The Hilt entry point: this annotation generates the application-level component every
 * @AndroidEntryPoint and @HiltViewModel resolves against.
 *
 * It also supplies WorkManager's configuration. That is not optional plumbing — WorkManager's
 * own startup initializer is removed in the manifest precisely so it reads this instead, and
 * picks up the factory that can build an @HiltWorker.
 */
@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var repository: Repository
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** Process-lived, so a drain is not cancelled by whatever screen happens to close. */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    /**
     * Read lazily by WorkManager on first use — after onCreate, so the injected factory is
     * set by then. Without this, workers with constructor dependencies cannot be built and
     * fail at runtime rather than at compile time.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            // Reuses the network module's build flag rather than switching on buildConfig
            // generation for :app just to learn the same fact.
            .setMinimumLoggingLevel(if (NetworkConfig.isDebug) Log.DEBUG else Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Deliberately BLOCKING, and it is the narrow case where that is right.
        //
        // MainActivity picks its navigation start destination from isSessionValid() in
        // onCreate, before the nav graph exists, so it cannot await anything. Priming in a
        // background coroutine would race that read: lose the race and a signed-in user
        // gets the login screen — an intermittent bug, which is the worst way for it to
        // appear. (app-pos hit exactly this; see its own note.)
        //
        // The cost is one small DataStore read on a file the process just opened anyway,
        // once per launch, at a point where no frame has been drawn yet.
        runBlocking { tokenStore.prime() }

        // Push anything the last session could not deliver, immediately — a phone that was
        // offline yesterday catches up the moment it is opened with signal, rather than
        // waiting up to 15 minutes for the periodic job.
        //
        // Backgrounded, unlike prime(): nothing on screen depends on the result, and the
        // drain absorbs its own failures. Blocking here would delay the first frame for a
        // network round trip.
        // Both directions, once, at launch: push what we owe the server, then read what it
        // has for us. The pull matters most right here — the device no longer seeds itself,
        // so on a fresh install this is what puts anything on the screen at all.
        appScope.launch {
            repository.syncNow()
            repository.refreshApprovals()
            repository.refreshMyLedger()
        }

        // And the safety net: keep trying in the background, even while the app is closed.
        // KEEP means an existing schedule survives this call (see SyncScheduler).
        //
        // Draining only — app-mobile never polls the server from the background, because a
        // phone on battery cannot afford to. Reading incoming changes is a foreground job.
        syncScheduler.schedulePeriodicSync()
    }
}
