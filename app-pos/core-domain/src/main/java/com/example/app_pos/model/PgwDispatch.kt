package com.example.app_pos.model

/**
 * What the terminal must hand to the payment gateway right now, and how to close it off.
 *
 * Deliberately NOT a repository method that "delivers" a job. The delivery is an Android
 * intent, which the data layer cannot fire and must not know about — so the repository
 * answers with the work, the UI performs it, and the acknowledgement comes back through
 * [PgwDispatcher.acknowledge].
 *
 * The order matters and is the reason these are two calls rather than one: the intent goes
 * out FIRST and the ack second. That way a crash between them leaves the job pending and
 * it is retried — a receipt printed twice is bad, a receipt never printed is worse, and
 * only one of the two orderings can be recovered from by trying again.
 */
interface PgwDispatcher {

    /**
     * Work waiting for this terminal, oldest first.
     *
     * Returns an empty list when there is nothing to do AND when the server cannot be
     * reached: from the caller's side both mean "fire nothing", and a poll that raised an
     * error banner every time the signal dropped would be noise at a till. The job stays
     * on the server either way.
     */
    suspend fun pendingJobs(): List<PgwJob>

    /**
     * Says a job reached the gateway, so it stops being handed back.
     *
     * Idempotent server-side: the terminal acks AFTER firing the intent, so a lost
     * response leaves it retrying an ack for work that really was delivered.
     */
    suspend fun acknowledge(jobId: String)

    /**
     * Queues work for this terminal from elsewhere — a seller collecting payment from
     * their phone (path 4).
     *
     * Returns false when the server refused or could not be reached, so the screen can say
     * the till was not told rather than implying somebody is about to be charged.
     */
    suspend fun queueCollect(customerId: String, amountMinor: Long): Boolean
}
