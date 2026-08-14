package com.example.app_pos.ui.dashboard.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.Customer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Which customers the list should show. */
enum class CustomerFilter { ALL, WITH_DEBT }

/**
 * Everything the dashboard's customer tab needs: the visible customers, the
 * total receivable, and the current search/filter selection.
 *
 * The customer list comes from the repository as a Flow, so a ledger write (new
 * veresiye entry) updates the balances here live. Search and filter are applied
 * on top of that Flow — in the ViewModel, not the fragment — so the screen stays
 * a pure renderer and this logic stays unit-testable without Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(CustomerFilter.ALL)

    // The list is scoped to the signed-in seller: follow the current user, then
    // switch to that seller's customer flow (flatMapLatest cancels the old seller's
    // flow when the user changes). An empty user yields an empty list.
    private val sellerCustomers =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else repo.observeCustomers(user.userId)
        }

    val customers: StateFlow<List<Customer>> =
        combine(sellerCustomers, query, filter) { all, q, f ->
            all
                .filter { it.displayName.contains(q, ignoreCase = true) }
                .filter { customer ->
                    when (f) {
                        CustomerFilter.ALL -> true
                        CustomerFilter.WITH_DEBT -> customer.balanceMinor > 0
                    }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalReceivableMinor: StateFlow<Long> =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(0L) else repo.observeTotalReceivableMinor(user.userId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun onSearchChanged(newQuery: String) {
        query.value = newQuery
    }

    fun onFilterChanged(newFilter: CustomerFilter) {
        filter.value = newFilter
    }

    /**
     * Pulls the book from the server while this screen is open.
     *
     * Needed because the flows above read Room, and Room only holds what a pull put there —
     * the terminal stopped seeding itself in Turn 39. Without this the list is empty on a
     * fresh install no matter what the server holds.
     *
     * Thirty seconds, not fifteen: a customer list changes when somebody books an entry, and
     * an entry raised elsewhere already arrives through the approvals poll. This is the
     * catch-up for writes made on another terminal, not a live feed.
     */
    suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            repo.refreshBook()
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
