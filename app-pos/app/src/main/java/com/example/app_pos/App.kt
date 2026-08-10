package com.example.app_pos

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.app_pos.data.di.ApplicationScope
import com.example.app_pos.model.Repository
import com.example.app_pos.network.NetworkConfig
import com.example.app_pos.network.auth.TokenStore
import com.example.app_pos.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Hilt's entry point: the annotation generates the application-wide component every
 * @AndroidEntryPoint class and @HiltViewModel resolves against.
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
        // background coroutine would race that read: lose the race and a signed-in merchant
        // gets the login screen — the exact bug this phase exists to fix, appearing
        // intermittently, which is the worst way for it to appear.
        //
        // The cost is one small DataStore read on a file the process just opened anyway,
        // once per launch, at a point where no frame has been drawn yet.
        runBlocking { tokenStore.prime() }

        // Push anything the last session could not deliver, immediately — the terminal that
        // was offline yesterday catches up the moment it is opened with signal, rather than
        // waiting up to 15 minutes for the periodic job.
        //
        // Backgrounded, unlike prime(): nothing on screen depends on the result, and the
        // drain absorbs its own failures. Blocking here would delay the first frame for a
        // network round trip.
        appScope.launch { repository.syncNow() }

        // And the safety net: keep trying in the background, even while the app is closed.
        // This is what covers a terminal that is never reopened until long after signal
        // returns. KEEP means an existing schedule survives this call (see SyncScheduler).
        syncScheduler.schedulePeriodicSync()
    }
}
