package com.example.app_pos.model

/**
 * What one attempt at reading the server accomplished — the mirror of [SyncOutcome], which
 * says the same thing about the write direction.
 *
 * A sealed type rather than counts, because the caller's decision differs per branch and
 * one of them is genuinely dangerous to get wrong: [Unreachable] must NOT be treated as
 * "the server has nothing", or a phone with no signal would empty its own inbox. Counts
 * cannot express that difference; a shape can.
 */
sealed interface PullOutcome {

    /**
     * The local table now matches the server. [count] is what came back, and zero is a
     * legitimate answer — it means everything was decided elsewhere, which is exactly the
     * case the pull exists to notice.
     */
    data class Refreshed(val count: Int) : PullOutcome

    /**
     * The server could not be asked. Local rows are left ALONE: this device does not know
     * what changed, and dropping cards on a bad connection would invent an answer nobody
     * gave. Retrying later is the right move.
     */
    data object Unreachable : PullOutcome

    /** The server answered, and refused. Retrying the same request will not help. */
    data class Failed(val message: String) : PullOutcome
}
