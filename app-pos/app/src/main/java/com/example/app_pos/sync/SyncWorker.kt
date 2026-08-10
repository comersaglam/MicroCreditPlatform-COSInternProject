package com.example.app_pos.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.app_pos.model.Repository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drains the outbox from the background, so writes reach the server even while app-pos is
 * closed.
 *
 * Until now the queue only emptied at app start and right after a sale. That left the case
 * this Worker exists for: a terminal that spent the day without signal, was closed, and
 * never reopened before signal returned. WorkManager wakes it instead.
 *
 * It lives in :app rather than :core-data on purpose. :core-data knows about storage and the
 * network; WHEN to sync is an application decision, and making a library module depend on
 * WorkManager would bake a scheduling policy into the data layer. (@HiltWorker also binds to
 * the component @HiltAndroidApp generates, which is an :app concern.)
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: Repository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = decide(repository)

    companion object {
        /** Shared by the periodic schedule and the one-off after a sale (see SyncScheduler). */
        const val PERIODIC_WORK_NAME = "outbox-sync-periodic"
        const val ONE_TIME_WORK_NAME = "outbox-sync-now"

        /**
         * The whole of the Worker's behaviour, as a plain function.
         *
         * Separated from [doWork] only so it can be tested: WorkerParameters has no public
         * constructor, so a SyncWorker cannot be built in a JVM test, and running one for
         * real would mean Robolectric plus a scheduler to check four branches. This keeps
         * the decision — the part with the data-loss risk — verifiable at unit-test cost.
         */
        suspend fun decide(repository: Repository): Result {
            // No session, no attempt — and this guard is the whole reason the Worker cannot
            // just call syncNow().
            //
            // Without a token the server answers 401. SyncEngine classifies 401 as a 4xx,
            // i.e. "the server refused and retrying cannot help", and DELETES the queued
            // entry. So a background sync while signed out would silently destroy unsent
            // veresiye records. Retrying leaves the queue untouched until someone signs in.
            if (!repository.isSessionValid()) return Result.retry()

            return try {
                val outcome = repository.syncNow()
                // Something was left behind (offline, a 5xx). Hand back to WorkManager,
                // which applies its own exponential backoff rather than us spinning.
                if (outcome.retryable > 0) Result.retry() else Result.success()
            } catch (t: Throwable) {
                // Never Result.failure(): that drops the work permanently and the queue
                // would stay stuck for good. An unexpected fault means "try again later",
                // which is also what a crash mid-drain leaves behind — entries still queued.
                Result.retry()
            }
        }
    }
}
