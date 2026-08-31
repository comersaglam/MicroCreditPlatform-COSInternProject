package com.example.app_mobile.ui.debts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_mobile.util.BalanceSeries
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.SellerDebt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The buyer's debt list: one row per shop they owe, plus the grand total.
 *
 * The mirror of app-pos's CustomersViewModel — scoped to the signed-in user
 * instead of a seller. flatMapLatest follows the current user so a re-login shows
 * the right person's debts; a ledger write (an approval, a payment) re-emits live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DebtsViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    val debts: StateFlow<List<SellerDebt>> =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else repo.observeMyDebtsBySeller(user.userId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalDebtMinor: StateFlow<Long> =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(0L) else repo.observeMyTotalDebtMinor(user.userId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * The last twelve closing balances across every shop, for the trend line.
     *
     * Reads the entries rather than the per-shop totals above, because a total says where
     * the figure stands and this has to say where it has been.
     */
    val totalSeries: StateFlow<List<Long>> =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else repo.observeAllForBuyer(user.userId)
        }.map { BalanceSeries.monthly(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Pulls the ledger from the server while this screen is open.
     *
     * Needed because the flows above read Room, and Room only holds what a pull put there —
     * the device stopped seeding itself in Turn 39. Without this the screen is empty on a
     * fresh install no matter what the server holds.
     *
     * Slower than the approvals poll on purpose: a debt changes when somebody approves
     * something, and that approval already arrives through its own fifteen-second poll. This
     * is the catch-up for entries written elsewhere, not a live feed.
     */
    suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            repo.refreshMyLedger()
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
