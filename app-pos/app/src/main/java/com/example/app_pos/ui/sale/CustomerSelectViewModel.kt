package com.example.app_pos.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.util.matchesQuery
import com.example.app_pos.model.Customer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Search state for the "pick a customer" step of a sale.
 *
 * Deliberately narrower than CustomersViewModel: no total receivable, no debt
 * filter. During a sale the only question is *who* the entry belongs to — the
 * overview belongs to the dashboard.
 *
 * Customers come from the repository as a Flow, so balances shown at selection
 * are current. The filtering rule lives here rather than in the fragment, so it
 * can be unit tested and the fragment only renders what it is given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CustomerSelectViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {
    private val _query = MutableStateFlow("")

    /** The raw query, so the screen can tell "not searched yet" from "no hits". */
    val query: StateFlow<String> = _query

    // The signed-in seller's own customers (see CustomersViewModel for the pattern).
    private val sellerCustomers =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else repo.observeCustomers(user.userId)
        }

    /** The seller's customers, narrowed by whatever has been typed. */
    val matches: StateFlow<List<Customer>> =
        combine(sellerCustomers, _query) { all, q ->
            // A blank query shows the WHOLE book, and typing narrows it.
            //
            // This used to return an empty list until the merchant typed, on the reasoning
            // that a full list invites tapping the wrong row. In practice it did the
            // opposite: arriving from a mock-pos handoff, the merchant faced a blank screen
            // and had to type a name from memory — guessing, with no way to check a
            // spelling. Seeing the names is what actually prevents picking the wrong one.
            if (q.isEmpty()) all
            // Matches the phone too: a customer with no name was unreachable through
            // search, so the row hardest to recognise was also the one that could not be
            // looked up. See Customer.matchesQuery.
            else all.filter { it.matchesQuery(q) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // "Add '<name>' as a new customer" is offered whenever something is typed;
    // the fragment reads that straight off `query` (duplicate names are fine —
    // identity is the phone, captured on the next screen).

    fun onSearchChanged(newQuery: String) {
        _query.value = newQuery.trim()
    }
}
