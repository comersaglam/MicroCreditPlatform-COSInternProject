package com.example.app_pos.model

/**
 * What came of a sign-in attempt.
 *
 * A sealed type rather than a Boolean because the caller has to tell three outcomes
 * apart, and each leads somewhere different: a wrong code should be retried on the same
 * screen, an unregistered number opens the sign-up prompt, and a dead network is nobody's
 * mistake. A Boolean collapsed all of those into "try again", which is the wrong advice
 * for two of the three.
 */
sealed interface SignInResult {

    /** Signed in; the session is on disk. */
    data object Success : SignInResult

    /**
     * The number has no account. The server does NOT auto-register on verify, so this is
     * a normal branch rather than an error: the caller registers, then signs in again.
     */
    data object NeedsRegister : SignInResult

    /** The code was wrong or expired — the one case where retyping helps. */
    data object InvalidCode : SignInResult

    /** The request never reached the server, or it failed in a way retrying may fix. */
    data object Unreachable : SignInResult

    /** Anything else, carrying the server's message when there is one to show. */
    data class Failed(val message: String? = null) : SignInResult
}
