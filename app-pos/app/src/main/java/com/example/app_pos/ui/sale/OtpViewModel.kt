package com.example.app_pos.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.data.OtpService
import com.example.app_pos.model.Repository
import com.example.app_pos.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.CustomerCreateOutcome
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Where the OTP step is in its request → verify → write lifecycle.
 *
 * CUSTOMER_UNREACHABLE is separate from ERROR because it is not the merchant's mistake and
 * it has its own remedy: opening a NEW customer record needs the server (it mints the id),
 * so with no signal the sale cannot start — while a sale to an existing customer still
 * writes offline. Folding it into ERROR would tell the merchant something went wrong
 * without telling them the one thing that would let them carry on.
 */
enum class OtpStatus { SENDING, READY, VERIFYING, DONE, ERROR, CUSTOMER_UNREACHABLE }

/**
 * Runs the customer-approval step and, on approval, performs the write.
 *
 * The write lives here (not on the confirm screen) because it must happen only
 * after OTP succeeds. It is the single place that appends to the ledger: for a
 * new customer it creates the record first (addCustomer), then writes the entry.
 * A UUID transactionId keeps a retry from applying the same entry twice.
 */
@HiltViewModel
class OtpViewModel @Inject constructor(
    private val repo: Repository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {
    private val _status = MutableStateFlow(OtpStatus.SENDING)
    val status: StateFlow<OtpStatus> = _status.asStateFlow()

    /**
     * Asks the customer to approve. hasApp routes app-push vs SMS (both mocked
     * for now); the branch already exists so the real service slots in later.
     */
    fun sendOtp(phone: String, hasApp: Boolean) {
        _status.value = OtpStatus.SENDING
        viewModelScope.launch {
            val sent = OtpService.requestOtp(phone, hasApp)
            _status.value = if (sent) OtpStatus.READY else OtpStatus.ERROR
        }
    }

    /**
     * Verifies the code and, on success, writes the entry. Creates the customer
     * first when new. onWritten receives the resolved customerId (for messaging).
     */
    fun verifyAndWrite(
        phone: String,
        code: String,
        hasApp: Boolean,
        isNew: Boolean,
        displayName: String,
        knownCustomerId: String,
        amountMinor: Long,
        type: TransactionType,
        orderBody: OrderBody? = null,
        onWritten: () -> Unit
    ) {
        _status.value = OtpStatus.VERIFYING
        viewModelScope.launch {
            val ok = OtpService.verifyOtp(phone, code, hasApp)
            if (!ok) {
                _status.value = OtpStatus.ERROR
                return@launch
            }
            // The login gate guarantees a signed-in seller here; the null-check is
            // defensive. The entry is booked to this seller's ledger.
            val sellerId = repo.currentSellerId() ?: run {
                _status.value = OtpStatus.ERROR
                return@launch
            }
            // A new customer must exist ON THE SERVER before anything is booked against
            // them: the id comes from there, and an entry written against an id the server
            // does not know is refused, dropped from the outbox, and lost while still
            // showing on this screen. So a failure here stops the sale instead of writing.
            val customerId = if (isNew) {
                when (val outcome = repo.addCustomer(displayName, phone)) {
                    is CustomerCreateOutcome.Created -> outcome.customerId
                    // The person was already in this book (a 409, or a retry after a lost
                    // response). Their existing record is the right one to write against.
                    is CustomerCreateOutcome.AlreadyExists -> outcome.customerId
                    CustomerCreateOutcome.Unreachable -> {
                        _status.value = OtpStatus.CUSTOMER_UNREACHABLE
                        return@launch
                    }
                    // The server refused (an invalid number, not a seller). Retrying the
                    // same request cannot fix it, so this is the generic failure.
                    is CustomerCreateOutcome.Failed -> {
                        _status.value = OtpStatus.ERROR
                        return@launch
                    }
                }
            } else {
                knownCustomerId
            }
            // orderBody is present only for a basket handoff (DEBT from the PGW); when
            // set, the basket + its items are persisted and linked. Money-only passes null.
            repo.addTransaction(
                Transaction(
                    transactionId = UUID.randomUUID().toString(),
                    sellerId = sellerId,
                    customerId = customerId,
                    amountMinor = amountMinor,
                    type = type,
                    description = descriptionFor(type),
                    createdAt = createdAtFormat().format(Date())
                ),
                orderBody = orderBody
            )
            // The entry is safely in Room, so the screen can finish NOW. Reporting DONE
            // before the push is the offline-first rule in one line: the merchant is never
            // made to wait for a network round trip to close a sale.
            _status.value = OtpStatus.DONE
            onWritten()

            // Hand the push to WorkManager rather than running it here.
            //
            // onWritten() above closes the sale flow, which clears this ViewModel — a
            // coroutine started here would be cancelled mid-request, and even an
            // application-scoped one dies if the merchant swipes the app away. A work
            // request survives both, and its network constraint means an offline device is
            // not woken to fail. The entry is already safe in Room either way; this only
            // decides how promptly it leaves.
            syncScheduler.syncNow()
        }
    }

    private fun descriptionFor(type: TransactionType): String =
        when (type) {
            TransactionType.DEBT -> "Veresiye"
            TransactionType.PAYMENT -> "Ödeme"
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
