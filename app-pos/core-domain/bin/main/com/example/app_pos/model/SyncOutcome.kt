package com.example.app_pos.model

/**
 * What one attempt at pushing the unsent queue accomplished.
 *
 * In the domain module because [Repository.syncNow] returns it, so the background sync can
 * decide whether to try again without depending on a concrete data-layer class.
 *
 * The three counts are exactly the decisions the sync makes about a queued write, and they
 * are deliberately distinct: [retryable] means "still ours to deliver", while [dropped]
 * means "the server refused in a way repeating cannot fix". Collapsing them into one
 * "failed" number would hide the difference between a queue that is waiting and one that is
 * losing entries.
 */
data class SyncOutcome(
    /** Accepted by the server and removed from the queue. */
    val sent: Int = 0,
    /** Left queued to try again — offline, a timeout, or a server-side fault. */
    val retryable: Int = 0,
    /** Removed without being accepted; retrying the same request cannot succeed. */
    val dropped: Int = 0
) {
    val attempted: Int get() = sent + retryable + dropped
}
