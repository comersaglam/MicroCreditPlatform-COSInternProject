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
 * FOREGROUND POLLING (mock): the app is a caller, never a listener — no FCM. While this
 * screen is open the repository Flow re-emits, which is what a poll loop would observe.
 * TODO(FAZ 4): a WorkManager background poll refreshes this while the app is closed.
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
}
