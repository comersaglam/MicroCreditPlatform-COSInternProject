package com.example.app_mobile.ui.customerdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which ledger entries the history should show. */
enum class TransactionFilter { ALL, DEBT, PAYMENT }

/**
 * One customer's ledger with the signed-in SELLER, plus the write actions (veresiye /
 * payment) that go through the approval gate. Mirror of app-pos's CustomerDetailViewModel;
 * the difference is the write is a popup → requestApproval, not a keypad sale flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    private val repo: Repository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // See SellerDetailViewModel: the nav argument arrives through SavedStateHandle, which
    // both removes the factory and carries the id across process death.
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val allTransactions =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList())
            else repo.observeTransactions(user.userId, customerId)
        }

    // The customer record behind this screen. Looked up once (a suspend DB read now)
    // and exposed as state, so the fragment stays a pure renderer instead of doing the
    // read itself — a `by lazy` block cannot host a suspend call.
    private val customer: StateFlow<Customer?> =
        repo.observeCurrentUser().flatMapLatest { user ->
            flow { emit(if (user == null) null else repo.findCustomerById(user.userId, customerId)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Phone for the header; empty until the lookup lands. */
    val phone: StateFlow<String> =
        customer.map { it?.phone.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Whether the customer holds the app — decides "sent for approval" vs "written". */
    val isClaimed: StateFlow<Boolean> =
        customer.map { it?.claimStatus == ClaimStatus.CLAIMED }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val filter = MutableStateFlow(TransactionFilter.ALL)

    val transactions: StateFlow<List<Transaction>> =
        combine(allTransactions, filter) { txs, f ->
            when (f) {
                TransactionFilter.ALL -> txs
                // Indexation counts as debt here too -- see SellerDetailViewModel. The
                // seller looking at what a customer owes must see the same rows the
                // customer does.
                TransactionFilter.DEBT -> txs.filter {
                    it.type == TransactionType.DEBT || it.type == TransactionType.INDEXATION
                }
                TransactionFilter.PAYMENT -> txs.filter { it.type == TransactionType.PAYMENT }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Must agree with core-domain/Ledger.kt::balanceOf and with the server's sum.
    val balanceMinor: StateFlow<Long> =
        allTransactions.map { txs ->
            txs.sumOf { tx ->
                when (tx.type) {
                    TransactionType.DEBT -> tx.amountMinor
                    TransactionType.INDEXATION -> tx.amountMinor
                    TransactionType.PAYMENT -> -tx.amountMinor
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun onFilterChanged(newFilter: TransactionFilter) { filter.value = newFilter }

    /**
     * Books a veresiye for this customer, through the approval gate (path 3).
     *
     * An app-holding customer gets a pending approval; an app-less one is written
     * immediately (mock SMS-OTP). Once approved, the SERVER leaves a receipt job for this
     * shop's terminal — the gateway cannot be reached from a phone.
     */
    fun writeDebt(
        amountMinor: Long,
        description: String,
        onResult: (ApprovalOutcome) -> Unit
    ) {
        if (amountMinor <= 0) return onResult(ApprovalOutcome.Failed())
        val sellerId = repo.currentUserId() ?: return onResult(ApprovalOutcome.Failed())
        viewModelScope.launch {
            // The SERVER decides whether this waits for approval or is booked now, so the
            // screen has to be told rather than predicting from the local claim flag —
            // that flag is stale on a fresh install and was reporting the wrong branch.
            onResult(
                repo.requestApproval(
                    fromUserId = sellerId,
                    sellerId = sellerId,
                    customerId = customerId,
                    amountMinor = amountMinor,
                    type = TransactionType.DEBT,
                    description = description
                )
            )
        }
    }

    /**
     * Sends a payment to this shop's till for the customer to settle by card (path 4).
     *
     * NOT an approval, and not a ledger write. The gate exists so nobody books an entry
     * against the other party unilaterally; being paid at your own till is the opposite
     * situation — the customer consents by handing over a card. And nothing is booked here
     * because nobody has paid yet: the entry appears when the gateway takes the money.
     *
     * [onResult] is false when the till was not told, so the screen can say so rather than
     * implying somebody is about to be charged.
     */
    fun collectAtTerminal(amountMinor: Long, onResult: (Boolean) -> Unit) {
        if (amountMinor <= 0) return onResult(false)
        viewModelScope.launch { onResult(repo.collectAtTerminal(customerId, amountMinor)) }
    }
}
