package com.example.app_mobile.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.CustomerCreateOutcome
import com.example.app_pos.model.PhoneFormat
import com.example.app_mobile.util.BalanceSeries
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_mobile.util.matchesQuery
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerLookup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which customers the list should show. */
enum class CustomerFilter { ALL, WITH_DEBT }

/**
 * The seller's customer list (once the signed-in user is a seller). The mirror of
 * app-pos's CustomersViewModel — scoped to the signed-in user as the sellerId.
 * Search + filter apply on top of the repository Flow, so a ledger write updates
 * balances here live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(CustomerFilter.ALL)

    private val sellerCustomers =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else repo.observeCustomers(user.userId)
        }

    val customers: StateFlow<List<Customer>> =
        combine(sellerCustomers, query, filter) { all, q, f ->
            all
                // Matches the phone too: a customer with no name was unreachable through
                // search, so the row hardest to recognise was also the one that could not
                // be looked up. See Customer.matchesQuery.
                .filter { it.matchesQuery(q) }
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

    /**
     * The last twelve closing balances for the whole book, for the trend line.
     *
     * Reads every entry rather than the per-customer balances: the total sums all of
     * them, so its history has to as well.
     */
    val totalSeries: StateFlow<List<Long>> =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else repo.observeAllForSeller(user.userId)
        }.map { BalanceSeries.monthly(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Adding a customer resolves to one of three cases, because a phone number means
     * something different to each seller (see [CustomerLookup]). None of them writes a
     * ledger entry here: ownership lives in the ledger, so the caller continues to the
     * detail screen where the first veresiye is booked — that is what puts the person
     * in this book.
     */
    sealed interface AddCustomerResult {
        /** Created; open the detail screen for this id. */
        data class Added(val customerId: String, val displayName: String) : AddCustomerResult

        /** Known to another shop — con11firm before adopting the existing record. */
        data class ConfirmKnown(val existing: Customer) : AddCustomerResult

        /** Already in this seller's book; they should pick them from the list. */
        object AlreadyMine : AddCustomerResult

        /** The number could not be read as a phone number. */
        object InvalidPhone : AddCustomerResult

        /**
         * The server was never reached, so no record was opened — here or there.
         *
         * Distinct from [InvalidPhone] because nothing is wrong with what was typed, and
         * distinct from a generic failure because the remedy is simply to try again with a
         * connection. The record cannot be created locally: the server mints the id.
         */
        object Unreachable : AddCustomerResult

        /** The server refused, and said why. */
        data class Failed(val message: String) : AddCustomerResult
    }

    /**
     * Resolves [phone] against this seller's book and creates a record only when the
     * person is genuinely new. A second record for an existing person would split their
     * history, so the other two cases reuse or reject instead.
     */
    fun addCustomer(name: String, phone: String, onResult: (AddCustomerResult) -> Unit) {
        val stored = PhoneFormat.toStored(phone) ?: return onResult(AddCustomerResult.InvalidPhone)
        val sellerId = repo.currentUserId() ?: return
        viewModelScope.launch {
            val result = when (val lookup = repo.lookupCustomerForSeller(sellerId, stored)) {
                is CustomerLookup.AlreadyMine -> AddCustomerResult.AlreadyMine
                is CustomerLookup.KnownToOtherSeller -> AddCustomerResult.ConfirmKnown(lookup.existing)
                CustomerLookup.New -> when (val outcome = repo.addCustomer(name, stored)) {
                    is CustomerCreateOutcome.Created ->
                        AddCustomerResult.Added(outcome.customerId, name.trim())
                    // The lookup above said New, so this means the server knows something
                    // this device does not. Its record is the right one either way.
                    is CustomerCreateOutcome.AlreadyExists ->
                        AddCustomerResult.Added(outcome.customerId, name.trim())
                    CustomerCreateOutcome.Unreachable -> AddCustomerResult.Unreachable
                    is CustomerCreateOutcome.Failed -> AddCustomerResult.Failed(outcome.message)
                }
            }
            onResult(result)
        }
    }

    /**
     * Pulls this user's book from the server while the screen is open.
     *
     * The flows above read Room, and until this existed nothing server-side ever wrote the
     * seller half of that table — the list showed only what this install had created itself,
     * which is how it came up empty on a clean device. The buyer screen has had its own poll
     * since Turn 40 (see DebtsViewModel.poll); this is the missing other half.
     *
     * Same thirty seconds, and for the same reason: entries booked through an approval
     * already arrive on the approvals poll, so this is the catch-up for writes made
     * elsewhere, not a live feed. A buyer-only account polls harmlessly — the server refuses
     * and the pull reports nothing refreshed.
     */
    suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            repo.refreshBook()
            delay(POLL_INTERVAL_MS)
        }
    }

    fun onSearchChanged(newQuery: String) { query.value = newQuery }
    fun onFilterChanged(newFilter: CustomerFilter) { filter.value = newFilter }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
