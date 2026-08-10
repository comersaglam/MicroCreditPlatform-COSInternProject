package com.example.app_pos.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides WHEN the outbox is drained, and is the only place that knows WorkManager exists.
 *
 * Keeping the scheduling here means the data layer never learns about it: :core-data can
 * store and send, while the policy for how often to try stays an application concern that
 * can change without touching a repository.
 *
 * Both schedules carry the same constraint — a connected network — so a terminal with no
 * signal is never woken to fail. The queue simply waits.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * The safety net: try every so often, forever.
     *
     * 15 minutes because that is WorkManager's floor — a shorter period is silently raised,
     * so asking for less would only misrepresent what actually happens. The OS batches the
     * wake-up with other work, so the real interval is "15 minutes or later", which is the
     * right trade for a queue that is rarely urgent.
     *
     * KEEP rather than UPDATE: this runs on every app start, and UPDATE would restart the
     * period each time. On a POS that is opened many times a day the job would be perpetually
     * rescheduled and never actually run.
     */
    fun schedulePeriodicSync() {
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
        )
    }

    /**
     * Push now — used right after a sale, when the entry is fresh and the merchant is
     * plausibly online.
     *
     * Handed to WorkManager rather than run in a coroutine because it must survive the app
     * closing: a shopkeeper who finishes a sale and immediately swipes the app away would
     * otherwise cut the request off mid-flight. The entry would stay queued (nothing is
     * lost), but it would sit there until the next drain for no reason.
     *
     * KEEP, so tapping through several sales quickly coalesces into one run instead of
     * queueing a redundant job per sale — the drain sends the whole queue anyway.
     */
    fun syncNow() {
        workManager.enqueueUniqueWork(
            SyncWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .build()
        )
    }

    private val networkConstraint: Constraints
        get() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    private companion object {
        const val PERIOD_MINUTES = 15L
    }
}
