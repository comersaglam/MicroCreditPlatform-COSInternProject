package com.example.app_pos.pgw

import android.content.Context
import com.example.app_pos.model.PgwDispatcher
import com.example.app_pos.model.PgwJob
import com.example.app_pos.model.PgwJobKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects work the server left for this terminal and hands it to the payment gateway.
 *
 * The reason this exists at all is that the arrow only points one way. Paths 3, 4 and 5
 * all START on a phone — the seller's or the buyer's — and END at the gateway, which only
 * a POS can reach. The server cannot call a terminal (NAT, no push channel yet), so it
 * writes the work down and this comes and asks.
 *
 * ORDER MATTERS, and it is the whole safety argument: the intent is fired FIRST and the
 * job acked SECOND. A crash between the two leaves the job pending, so it is retried and
 * the receipt prints twice — annoying, and recoverable. The other ordering loses the
 * receipt entirely, which is not. At-least-once is the right way round here.
 */
@Singleton
class PgwJobRunner @Inject constructor(private val dispatcher: PgwDispatcher) {

    /**
     * Polls for work until the caller's scope is cancelled.
     *
     * Driven from a STARTED lifecycle rather than an application scope: firing a gateway
     * intent while the terminal is in somebody's pocket would launch a payment screen out
     * of nowhere. Work missed while backgrounded is not lost — it stays PENDING on the
     * server and arrives on the next poll.
     */
    suspend fun poll(context: Context) {
        while (currentCoroutineContext().isActive) {
            dispatcher.pendingJobs().forEach { job -> deliver(context, job) }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Hands one job to the gateway, then closes it off.
     *
     * A job the gateway could not accept is deliberately NOT acked: it stays pending and
     * is retried on the next poll, which is the correct behaviour when the gateway is
     * simply not installed yet or was busy.
     */
    private suspend fun deliver(context: Context, job: PgwJob) {
        val delivered = when (job.kind) {
            // Money already agreed and booked — print the slip. The server's orderBody is
            // passed through untouched; it is the gateway's contract, not ours.
            PgwJobKind.RECEIPT ->
                PgwBridge.printReceipt(context, job.amountMinor, job.orderBody)

            // Money NOT yet taken — open the gateway so a card can be charged.
            PgwJobKind.COLLECT -> PgwBridge.collectPayment(context, job.amountMinor)
        }

        if (delivered) dispatcher.acknowledge(job.jobId)
    }

    private companion object {
        /**
         * Five SECONDS, matching the till's approval wait rather than the fifteen the
         * approvals inbox uses. A job here means somebody on a phone has already completed
         * their half and is waiting on the terminal to finish the sale.
         */
        const val POLL_INTERVAL_MS = 5_000L
    }
}
