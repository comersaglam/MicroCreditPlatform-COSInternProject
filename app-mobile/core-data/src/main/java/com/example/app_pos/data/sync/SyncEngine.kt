package com.example.app_pos.data.sync

import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.network.ApiResult
import com.example.app_pos.network.isRetryable
import com.example.app_pos.network.dto.TransactionCreateDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the offline outbox: takes the writes Room already accepted and pushes them to the
 * server, one at a time, oldest first.
 *
 * This is where the network finally enters the write path — and note where it does NOT:
 * booking an entry still only touches Room, so the user is never blocked by signal.
 * Sending is a separate, later, retryable act.
 *
 * Three rules do the real work:
 *
 *  1. **Order.** Oldest first, and a retryable failure STOPS the run. If the network is
 *     down, entry two will fail for the same reason entry one did; hammering the rest only
 *     inflates every retryCount and burns battery.
 *  2. **What to do with a failure** comes from [isRetryable], defined once on ApiResult:
 *     a lost connection or a 5xx keeps the row, a 4xx removes it. Retrying a request the
 *     server has permanently refused would loop forever; dropping one it merely could not
 *     answer would lose a veresiye.
 *  3. **Deleting is safe because the send is idempotent.** The Idempotency-Key is the
 *     entry's own transaction id, so if the response was lost after the server committed,
 *     the resend replays the original write rather than booking a second one.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val local: LocalSource,
    private val remote: RemoteDataSource,
    private val moshi: Moshi
) {

    // One drain at a time. Two concurrent runs would read the same rows and send each
    // entry twice; the server would deduplicate them, but both runs would then race to
    // delete the same row and inflate each other's retry counts.
    private val mutex = Mutex()

    private val payloadAdapter = moshi.adapter(TransactionCreateDto::class.java)

    /**
     * Sends every queued write it can, and reports what happened.
     *
     * Never throws: RemoteDataSource returns failures as values, and a payload this client
     * cannot even parse is treated as permanently broken rather than allowed to kill the
     * run. A drain that crashes would leave the queue stuck for good.
     */
    suspend fun drainOutbox(): SyncOutcome = mutex.withLock {
        var outcome = SyncOutcome()

        for (entry in local.pendingOutbox()) {
            val body = runCatching { payloadAdapter.fromJson(entry.payload) }.getOrNull()
            if (body == null) {
                // Unparseable: written by an older version of the app, or corrupted. It can
                // never be sent, so keeping it would block the queue head forever.
                local.deleteOutbox(entry.id)
                outcome = outcome.copy(dropped = outcome.dropped + 1)
                continue
            }

            when (val result = remote.createTransaction(body, idempotencyKey = entry.transactionId)) {
                is ApiResult.Success -> {
                    local.deleteOutbox(entry.id)
                    outcome = outcome.copy(sent = outcome.sent + 1)
                }

                else -> {
                    if (result.isRetryable()) {
                        local.recordOutboxFailure(entry.id)
                        // Stop the run: whatever blocked this entry blocks the rest too.
                        return@withLock outcome.copy(retryable = outcome.retryable + 1)
                    }
                    // A refusal retrying cannot fix (4xx, or an answer we could not use).
                    // The entry stays in the local ledger — it is append-only and the
                    // merchant already saw it — but it stops consuming the queue.
                    local.deleteOutbox(entry.id)
                    outcome = outcome.copy(dropped = outcome.dropped + 1)
                }
            }
        }

        outcome
    }
}
