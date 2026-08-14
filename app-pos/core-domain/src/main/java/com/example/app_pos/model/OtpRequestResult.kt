package com.example.app_pos.model

/**
 * What happened when a code was requested — step 1 of signing in.
 *
 * A Boolean used to stand here, and it could not tell "the server refused this number"
 * apart from "the server was never reached". Both became `false`, and the screen said
 * "Geçersiz numara" either way — so a pulled USB cable or a dropped connection was
 * reported to the user as a typo in a number that was perfectly valid.
 *
 * The same lesson as [ApprovalOutcome] and [SignInResult]: a Boolean cannot carry a
 * reason, and the reason is what decides what the user should do next.
 */
sealed interface OtpRequestResult {

    /** The code is on its way. */
    data object Sent : OtpRequestResult

    /** The server answered and would not send one — the number itself is the problem. */
    data object Refused : OtpRequestResult

    /** The server was never reached. Nobody's mistake; trying again is the right move. */
    data object Unreachable : OtpRequestResult
}
