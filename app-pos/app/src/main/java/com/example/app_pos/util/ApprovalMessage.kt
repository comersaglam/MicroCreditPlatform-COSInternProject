package com.example.app_pos.util

import android.content.Context
import androidx.annotation.StringRes
import com.example.app_pos.R
import com.example.app_pos.model.DecisionOutcome

/**
 * The one place a [DecisionOutcome] becomes something a merchant reads.
 *
 * "Success" has no single wording here — it is "approved" on one button and "rejected" on
 * the other — so the caller supplies that line and this handles the rest.
 *
 * The two stale cases get their own wording rather than a generic error, because from the
 * merchant's side nothing went wrong: the card simply was not theirs to answer, or somebody
 * had answered it already. Calling that "failed" would send them looking for a problem that
 * does not exist. (app-mobile learned this on a device, where a card that could be neither
 * approved nor rejected looked broken while the system was behaving correctly.)
 */
fun DecisionOutcome.message(context: Context, @StringRes appliedRes: Int): String = when (this) {
    is DecisionOutcome.Applied -> context.getString(appliedRes)
    is DecisionOutcome.NotYours -> context.getString(R.string.decision_not_yours)
    is DecisionOutcome.AlreadyDecided -> context.getString(R.string.decision_already_decided)
    is DecisionOutcome.Unreachable -> context.getString(R.string.decision_unreachable)
    // Prefer the server's own explanation — it knows why it refused, and replacing that
    // with a generic line is how a fixable problem becomes a mystery.
    is DecisionOutcome.Failed -> message ?: context.getString(R.string.approval_failed)
}
