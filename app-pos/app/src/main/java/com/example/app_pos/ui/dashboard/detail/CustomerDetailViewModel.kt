package com.example.app_pos.ui.dashboard.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.example.app_pos.model.Repository
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import com.example.app_pos.sync.SyncScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

/** Which ledger entries the history should show. */
enum class TransactionFilter { ALL, DEBT, PAYMENT }

/**
 * One payment to hand to the gateway: the amount, and who it is from.
 *
 * The name is nullable because the gateway request is worth sending without one —
 * `PgwBridge` leaves the customerInfo block out entirely rather than naming nobody.
 */
data class GatewayCollect(
    val amountMinor: Long,
    val customerName: String?
)

/**
 * Ledger history for one customer.
 *
 * Entries come from the repository as a Flow, so a new veresiye entry shows up
 * here live. The balance is recomputed from those entries — never read from a
 * stored field — so the append-only rule holds on every screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    private val repo: Repository,
    private val syncScheduler: SyncScheduler,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /**
     * Payments the gateway should now collect.
     *
     * A SharedFlow rather than a StateFlow: this is an event, not a state. A StateFlow would
     * replay its last value to a fragment returning from the gateway and charge the card a
     * second time — the exact bug the payment path cannot afford. extraBufferCapacity keeps
     * the non-suspending emit from dropping the event when nothing is collecting yet.
     *
     * The customer travels WITH the event rather than being read off a StateFlow at the
     * call site: those are suspend DB reads exposed with an empty initial value, so a dialog
     * confirmed quickly would find one still empty and the gateway request would go out
     * unnamed — silently, which is the worst kind.
     */
    private val _collectAtGateway = MutableSharedFlow<GatewayCollect>(extraBufferCapacity = 1)
    val collectAtGateway: SharedFlow<GatewayCollect> = _collectAtGateway.asSharedFlow()

    // Navigation puts the destination's arguments into the SavedStateHandle under their
    // declared names, so the nav arg arrives without a hand-written factory — and survives
    // process death, which the factory's captured value did not.
    private val customerId: String = checkNotNull(savedStateHandle["customerId"]) {
        "customerDetailFragment requires a customerId argument"
    }

    // The customer's phone: identity for the pay flow and how the merchant tells two
    // same-named customers apart. Looked up once (a suspend DB read) and exposed as a
    // StateFlow so the fragment stays a pure renderer. Seller-independent (only the
    // phone is used); the current seller just satisfies the lookup signature.
    val phone: StateFlow<String> =
        repo.observeCurrentUser().flatMapLatest { user ->
            flow {
                emit(
                    if (user == null) ""
                    else repo.findCustomerById(user.userId, customerId)?.phone.orEmpty()
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // Scoped to the signed-in seller: this customer's history with THIS seller only.
    private val allTransactions =
        repo.observeCurrentUser().flatMapLatest { user ->
            if (user == null) flowOf(emptyList())
            else repo.observeTransactions(user.userId, customerId)
        }

    private val filter = MutableStateFlow(TransactionFilter.ALL)

    val transactions: StateFlow<List<Transaction>> =
        combine(allTransactions, filter) { txs, f ->
            when (f) {
                TransactionFilter.ALL -> txs
                TransactionFilter.DEBT -> txs.filter { it.type == TransactionType.DEBT }
                TransactionFilter.PAYMENT -> txs.filter { it.type == TransactionType.PAYMENT }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sum of the entries: DEBT adds, PAYMENT subtracts; updates on every write. */
    val balanceMinor: StateFlow<Long> =
        allTransactions.map { txs ->
            txs.sumOf { tx ->
                when (tx.type) {
                    TransactionType.DEBT -> tx.amountMinor
                    TransactionType.PAYMENT -> -tx.amountMinor
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun onFilterChanged(newFilter: TransactionFilter) {
        filter.value = newFilter
    }

    /**
     * Path 2 — TAHSİLAT, taken straight from this screen.
     *
     * The amount used to be keyed into the sale flow's own keypad and then walked through
     * confirm and an OTP screen before the gateway was ever called. All three screens were
     * ceremony: the money is being handed TO the shop, so there is nothing to book against
     * the customer without their consent, and the customer approves by presenting their card
     * AT the gateway. The OTP step in particular verified nothing — it accepted any code.
     *
     * So the entry is written and the gateway is asked to take the payment, in that order.
     * The write comes first and locally, which keeps this half offline-first: a till with no
     * signal still records the payment, and [SyncScheduler] delivers it when there is a
     * connection. The gateway call itself is fired by the fragment — starting an activity
     * needs a Context, which a ViewModel must not hold — so this only reports the amount.
     *
     * TODO(pgw-handshake): the gateway's answer is still not awaited; the intent going out
     *  IS the confirmation, exactly as in OtpViewModel.collectPayment. Unchanged here.
     */
    fun collectPayment(amountMinor: Long) {
        viewModelScope.launch {
            // The login gate guarantees a signed-in seller; the null check is defensive.
            val sellerId = repo.currentSellerId() ?: return@launch
            // Read the customer fresh instead of trusting the [phone] StateFlow, whose first
            // value arrives asynchronously — see the note on _collectAtGateway.
            val customer = repo.findCustomerById(sellerId, customerId)
            repo.addTransaction(
                Transaction(
                    transactionId = UUID.randomUUID().toString(),
                    sellerId = sellerId,
                    customerId = customerId,
                    amountMinor = amountMinor,
                    type = TransactionType.PAYMENT,
                    description = "Ödeme",
                    createdAt = createdAtFormat().format(Date())
                )
            )
            // Stored locally, so the gateway can be called and the screen can move on. The
            // push to the server is WorkManager's job: it outlives this ViewModel and the
            // app being swiped away, and its network constraint keeps an offline device
            // from being woken only to fail.
            syncScheduler.syncNow()
            _collectAtGateway.emit(
                GatewayCollect(amountMinor = amountMinor, customerName = customer?.displayName)
            )
        }
    }

    private companion object {
        /**
         * ISO-8601 UTC — the format the wire contract uses and the DAOs sort on. A new
         * formatter per call because SimpleDateFormat is not thread-safe.
         */
        fun createdAtFormat(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
}
