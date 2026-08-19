package com.example.app_mobile.ui.approvals

import androidx.annotation.StringRes
import com.example.app_pos.model.PendingApproval

/**
 * One row of the approvals list: a section header or an approval card.
 *
 * The list is grouped by MY role on each request, so the header is data rather than a
 * fixed view — an empty section simply contributes no rows, and DiffUtil animates
 * headers and cards through the same [id].
 */
sealed interface ApprovalListItem {
    /** Stable identity, so DiffUtil can tell rows apart across submits. */
    val id: String

    data class Header(@param:StringRes val titleRes: Int) : ApprovalListItem {
        override val id: String get() = "header_$titleRes"
    }

    /**
     * An approval with everything the card renders already decided: the colour tone
     * and the counterparty's phone are resolved in the ViewModel, so the adapter stays
     * a pure renderer (the same split every other adapter here follows).
     */
    data class Card(
        val approval: PendingApproval,
        val tone: ApprovalTone,
        val counterpartyPhone: String,
        /**
         * The line stating WHO is asking WHAT — four wordings, since the same request
         * reads oppositely from each end. Resolved here because it depends on my role,
         * which the adapter does not know.
         */
        @param:StringRes val kindRes: Int
    ) : ApprovalListItem {
        override val id: String get() = approval.approvalId
    }
}

/**
 * How an approval reads to the signed-in user, as a colour.
 *
 * Direction is what moves from my side: on a veresiye that is goods/credit (the shop
 * gives, the customer receives), on a payment it is money (the shop receives, the
 * customer gives). Incoming is green, outgoing red.
 *
 * Intensity says whether cash actually moved: a veresiye is only a ledger entry so it
 * stays faint, a payment is real money changing hands so it reads strong.
 */
enum class ApprovalTone { INCOMING_FAINT, INCOMING_STRONG, OUTGOING_FAINT, OUTGOING_STRONG }
