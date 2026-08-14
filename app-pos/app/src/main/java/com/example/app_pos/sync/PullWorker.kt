package com.example.app_pos.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.app_pos.model.PullOutcome
import com.example.app_pos.model.Repository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reads the server's inbox from the background, so an approval raised while app-pos is
 * closed is already on screen when the merchant next opens it.
 *
 * The background half of a two-layer answer: the Onaylar screen polls every fifteen seconds
 * while it is open, which is what makes a card appear while the customer is still standing
 * at the counter. This is the slower net beneath it, at WorkManager's fifteen-MINUTE floor.
 *
 * **app-pos has this and app-mobile deliberately does not.** A terminal sits on a counter,
 * usually on mains power, and its whole job is to be ready for whatever the customer does
 * next. A phone runs on battery and cannot afford to wake up all day to ask a question
 * nobody is waiting on — so on that side, background work only drains the outbox.
 *
 * Neither layer is real-time delivery. That needs FCM (the `devices` table and the FAZ 8
 * plan already exist for it); when it lands, PullEngine does not change — only the trigger.
 */
@HiltWorker
class PullWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: Repository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = decide(repository)

    companion object {
        const val PERIODIC_WORK_NAME = "approvals-pull-periodic"

        /**
         * The Worker's behaviour as a plain function, testable without WorkerParameters —
         * the same arrangement, and for the same reason, as [SyncWorker.decide].
         */
        suspend fun decide(repository: Repository): Result {
            // No session, no question to ask: the endpoint answers "what is waiting on
            // YOU". The stakes are lower than the drain's identical guard (nothing can be
            // lost by reading), but retrying keeps the schedule alive until someone signs
            // in, instead of burning a wake-up on a guaranteed 401.
            if (!repository.isSessionValid()) return Result.retry()

            return try {
                // The book too, so a terminal reopened after a day already shows what other
                // devices booked. Its result does not decide the outcome: the inbox is the
                // time-critical half, and a book that is one interval stale is harmless.
                repository.refreshBook()

                when (repository.refreshApprovals()) {
                    // Storage now matches the server.
                    is PullOutcome.Refreshed -> Result.success()
                    // Could not ask. Hand back to WorkManager's backoff rather than spin.
                    is PullOutcome.Unreachable -> Result.retry()
                    // The server refused. Retrying the same request will not help, but
                    // never Result.failure(): that cancels the PERIODIC work permanently,
                    // and one bad answer would end all future pulls. The next scheduled run
                    // is the right recovery.
                    is PullOutcome.Failed -> Result.success()
                }
            } catch (t: Throwable) {
                Result.retry()
            }
        }
    }
}
