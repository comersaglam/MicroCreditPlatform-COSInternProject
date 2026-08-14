package com.example.app_mobile.ui.approvals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_mobile.R
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Onaylar — every request waiting on the signed-in user, in BOTH directions: entries a
 * shop wants to book on them, and payments their own customers want confirmed. The two
 * read very differently, so the list is split into sections by role.
 *
 * FOREGROUND POLLING: the app is a caller, never a listener — no FCM. The cards are
 * authored on the COUNTERPARTY's device, so nothing local can discover them; [poll] asks
 * the server while this screen is open and the Flow above re-emits from Room.
 *
 * Polling only in the foreground is deliberate, and the asymmetry with app-pos is the
 * point: this is a battery-powered phone, so background work here drains the outbox and
 * nothing more. A POS sits on a counter and can afford to listen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ApprovalsViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    val items: StateFlow<List<ApprovalListItem>> =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList())
            else repo.observePendingApprovals(user.userId).map { approvals ->
                buildItems(approvals, user.userId)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Asks the server for the inbox, forever, while the caller's scope is alive.
     *
     * Driven from the Fragment's STARTED lifecycle rather than [viewModelScope]: a
     * ViewModel outlives the visible screen, and a loop tied to it would keep hitting the
     * network from a backgrounded app — the exact battery cost this app avoids by not
     * polling in a Worker.
     *
     * The result is deliberately ignored. Every outcome is already handled where it
     * matters: success rewrites Room and the Flow re-emits, and a failure leaves storage
     * untouched by design. There is nothing useful to say to someone looking at a list that
     * is still correct, so a poll never raises an error banner.
     */
    suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            repo.refreshApprovals()
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Groups by MY role and prepends a header to each non-empty group.
     *
     * Role comes from the approval itself — am I the shop on it? — which is the same
     * test the repository uses when routing a request, so no extra field is needed.
     */
    private suspend fun buildItems(
        approvals: List<PendingApproval>,
        userId: String
    ): List<ApprovalListItem> {
        val (asSeller, asBuyer) = approvals.partition { it.sellerId == userId }
        return buildList {
            if (asSeller.isNotEmpty()) {
                add(ApprovalListItem.Header(R.string.approvals_section_as_seller))
                addAll(asSeller.map { it.toCard(userId, isSeller = true) })
            }
            if (asBuyer.isNotEmpty()) {
                add(ApprovalListItem.Header(R.string.approvals_section_as_buyer))
                addAll(asBuyer.map { it.toCard(userId, isSeller = false) })
            }
        }
    }

    private suspend fun PendingApproval.toCard(userId: String, isSeller: Boolean) =
        ApprovalListItem.Card(
            approval = this,
            // What moves, from my side. On a veresiye it is goods/credit: the shop
            // gives them up (out), the customer receives them (in) — faint, since no
            // cash has moved yet. On a payment it is money: it reaches the shop (in)
            // and leaves the customer (out) — strong, because that is real cash.
            tone = when {
                type == TransactionType.DEBT ->
                    if (isSeller) ApprovalTone.OUTGOING_FAINT else ApprovalTone.INCOMING_FAINT
                else ->
                    if (isSeller) ApprovalTone.INCOMING_STRONG else ApprovalTone.OUTGOING_STRONG
            },
            counterpartyPhone = counterpartyPhone(userId, isSeller)
        )

    /**
     * How to reach the other side. The name is already denormalised onto the approval;
     * the phone is looked up here instead of stored, so the card needs no schema change.
     *
     * findCustomerById also computes a balance we ignore — irrelevant for the handful of
     * rows a pending list holds, and a leaner query would be premature.
     */
    private suspend fun PendingApproval.counterpartyPhone(userId: String, isSeller: Boolean): String =
        if (isSeller) repo.findCustomerById(userId, customerId)?.phone.orEmpty()
        else repo.shopPhoneOf(sellerId).orEmpty()

    /**
     * Approve → the entry is written to the ledger (single write point).
     *
     * [onResult] carries what the SERVER said, because not every answer is a success and
     * the differences matter: a card addressed to someone else can never be approved, and
     * telling the user "approved" for it would be a lie. The list is a Flow, so the card
     * disappears on its own once the row is decided or dropped.
     */
    fun approve(approvalId: String, onResult: (DecisionOutcome) -> Unit) {
        viewModelScope.launch { onResult(repo.approvePending(approvalId)) }
    }

    /** Reject → the request is closed, nothing written. */
    fun reject(approvalId: String, onResult: (DecisionOutcome) -> Unit) {
        viewModelScope.launch { onResult(repo.rejectPending(approvalId)) }
    }

    private companion object {
        /**
         * Fifteen SECONDS, against WorkManager's fifteen-MINUTE floor. Affordable only
         * because it runs while someone is looking at the screen: an approval is answered
         * in a conversation, so a card arriving a quarter of an hour late would be useless.
         */
        const val POLL_INTERVAL_MS = 15_000L
    }
}
