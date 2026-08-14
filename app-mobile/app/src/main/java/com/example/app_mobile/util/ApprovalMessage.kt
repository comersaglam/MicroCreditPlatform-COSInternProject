package com.example.app_mobile.util

import android.content.Context
import androidx.annotation.StringRes
import com.example.app_mobile.R
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.DecisionOutcome

/**
 * The one place an [ApprovalOutcome] becomes something a user reads.
 *
 * Shared by every screen that sends a write through the approval gate, so the same result
 * cannot be worded as success on one screen and failure on another — which is how the
 * payment screen ended up announcing "payment received" for a request that reached nobody.
 *
 * Each branch says what is TRUE of the entry now, because that is what the user has to act
 * on: whether they still owe it, and whether anyone else has seen it yet.
 */
fun ApprovalOutcome.message(context: Context): String = when (this) {
    // Sent, but the counterparty still has to accept — not settled yet.
    is ApprovalOutcome.SentForApproval -> context.getString(R.string.approval_sent)
    // The counterparty has no app, so the server booked it straight away.
    is ApprovalOutcome.WrittenImmediately -> context.getString(R.string.approval_written)
    is ApprovalOutcome.NoCustomerRecord -> context.getString(R.string.approval_no_record)
    // Saved here only. Deliberately not phrased as plain success: nobody else has seen
    // it, and until it syncs the two sides can disagree.
    is ApprovalOutcome.QueuedOffline -> context.getString(R.string.approval_queued_offline)
    // Prefer the server's own explanation — it knows why it refused, and replacing that
    // with a generic line is how a fixable problem becomes a mystery.
    is ApprovalOutcome.Failed -> message ?: context.getString(R.string.approval_failed)
}

/**
 * The same idea for answering an approval, where "success" has no single wording — it is
 * "approved" on one button and "rejected" on the other, so the caller supplies it.
 *
 * The two stale cases get their own lines rather than a generic error, because from the
 * user's side nothing went wrong: the card simply was not theirs to answer, or somebody
 * had answered it already. Saying "failed" would send them looking for a problem that
 * does not exist.
 */
fun DecisionOutcome.message(context: Context, @StringRes appliedRes: Int): String = when (this) {
    is DecisionOutcome.Applied -> context.getString(appliedRes)
    is DecisionOutcome.NotYours -> context.getString(R.string.decision_not_yours)
    is DecisionOutcome.AlreadyDecided -> context.getString(R.string.decision_already_decided)
    is DecisionOutcome.Unreachable -> context.getString(R.string.decision_unreachable)
    is DecisionOutcome.Failed -> message ?: context.getString(R.string.approval_failed)
}
