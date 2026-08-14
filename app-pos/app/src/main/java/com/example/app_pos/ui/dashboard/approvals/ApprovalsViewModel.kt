package com.example.app_pos.ui.dashboard.approvals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.DecisionOutcome
import com.example.app_pos.model.PendingApproval
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Onaylar — what is waiting on THIS shop's decision.
 *
 * The other end of a gate app-pos could previously only write through: the terminal sent
 * entries for the customer to approve, but a customer declaring a payment had nobody on
 * this side to confirm it. That line now ends here.
 *
 * Simpler than app-mobile's screen of the same name, and deliberately so: that app is one
 * account in two roles and has to split the list by which role you are on each card. A POS
 * is only ever the shop, so every card here is a customer asking and the shop answering.
 *
 * FOREGROUND POLLING: the app is a caller, never a listener — no FCM. These cards are
 * authored on the CUSTOMER's device, so nothing local can discover them; [poll] asks the
 * server while the screen is open and the Flow re-emits from Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ApprovalsViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    val items: StateFlow<List<PendingApproval>> =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList())
            else repo.observePendingApprovals(user.userId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Asks the server for the inbox, forever, while the caller's scope is alive.
     *
     * Driven from the Fragment's STARTED lifecycle rather than [viewModelScope]: a ViewModel
     * outlives the visible screen, and a loop tied to it would keep polling from a
     * backgrounded app.
     *
     * The result is deliberately ignored. Success rewrites Room and the Flow re-emits; a
     * failure leaves storage untouched by design. There is nothing useful to tell someone
     * looking at a list that is still correct, so a poll never raises an error banner.
     */
    suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            repo.refreshApprovals()
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Approve → the entry is written to the ledger (the single write point).
     *
     * [onResult] carries what the SERVER said, because not every answer is a success and
     * the differences matter: a card addressed to another account can never be approved, and
     * saying "approved" for it would be a lie. The list is a Flow, so the card disappears on
     * its own once the row is decided or dropped.
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
         * Fifteen SECONDS, against WorkManager's fifteen-MINUTE floor. A terminal sits on a
         * counter with the customer standing in front of it, so a card that took a quarter
         * of an hour to appear would be useless — the customer would already have left.
         */
        const val POLL_INTERVAL_MS = 15_000L
    }
}
