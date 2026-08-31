package com.example.app_pos.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.data.OtpService
import com.example.app_pos.model.Repository
import com.example.app_pos.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.ApprovalOutcome
import com.example.app_pos.model.ApprovalStatus
import com.example.app_pos.model.CustomerCreateOutcome
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
enum class OtpStatus {
    SENDING,
    READY,
    VERIFYING,

    /**
     * The request reached the customer and the till is waiting for their answer.
     *
     * Its own state because the merchant must be able to see WHY nothing is happening, and
     * because this is the one step that can take minutes. The payment gateway is holding a
     * receipt open behind this screen, so closing early is not an option — but standing at
     * a frozen screen with no explanation is not one either.
     */
    AWAITING_APPROVAL,

    /** The customer declined. Nothing was written, and the gateway is told so. */
    REJECTED,

    DONE,
    ERROR,
    CUSTOMER_UNREACHABLE,

    /**
     * No signal, so the customer could not be asked at all.
     *
     * Distinct from [CUSTOMER_UNREACHABLE], which is about opening a new customer record.
     * This one is the approval gate itself: a veresiye cannot be booked offline, because
     * booking it anyway is exactly the unilateral write the gate exists to prevent.
     */
    APPROVAL_UNREACHABLE
}

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

    private val _collectAtGateway = MutableStateFlow<Long?>(null)

    /**
     * A payment the gateway should now take, in kuruş, or null when there is none.
     *
     * The intent itself is fired by the Fragment: a ViewModel has no Context and firing an
     * intent from here would tie the data flow to the Android framework. This is the
     * request; the screen performs it.
     */
    val collectAtGateway: StateFlow<Long?> = _collectAtGateway.asStateFlow()

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
            when (type) {
                // Path 1 — VERESİYE. The customer has to agree before anything is booked.
                TransactionType.DEBT ->
                    sendForApproval(sellerId, customerId, amountMinor, orderBody, onWritten)

                // Path 2 — TAHSİLAT. No gate, and that is not an omission: the money is
                // being handed to the shop, so there is nothing to book against the
                // customer without their consent. The entry is written and the gateway is
                // asked to take the payment.
                TransactionType.PAYMENT ->
                    collectPayment(sellerId, customerId, amountMinor, orderBody, onWritten)
            }
        }
    }

    /**
     * Path 1: sends the veresiye for the customer's approval and WAITS for their answer.
     *
     * Waiting is the deliberate choice. The payment gateway launched this flow and is
     * holding a receipt open for a success or a failure, so answering "sent" before the
     * customer decides would print a slip for a debt that may yet be refused. The merchant
     * can cancel out of the wait; the request stays raised and the customer can still
     * answer it, which is why cancelling reports a failure to the gateway rather than
     * pretending the sale completed.
     *
     * TODO(timeout): there is no upper bound on the wait yet. A customer who never opens
     *  their phone leaves the till on this screen until somebody cancels. Deliberately out
     *  of scope for this turn — a timeout needs a decision about what the gateway is told
     *  and whether the raised request is withdrawn, which is a product question.
     */
    private suspend fun sendForApproval(
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        orderBody: OrderBody?,
        onWritten: () -> Unit
    ) {
        val outcome = repo.requestApproval(
            sellerId = sellerId,
            customerId = customerId,
            amountMinor = amountMinor,
            type = TransactionType.DEBT,
            description = descriptionFor(TransactionType.DEBT),
            // The gateway's basket. This is the ONLY call path 1 makes, so leaving it out
            // here is what kept every handed-off basket out of the ledger: the entry is
            // written by the server when the customer approves, not by addTransaction.
            orderBody = orderBody
        )

        when (outcome) {
            // The customer holds the app: their card is up, and the till waits.
            is ApprovalOutcome.SentForApproval -> awaitDecision(outcome.approvalId, onWritten)

            // Nobody could tap approve, so the server booked it over the SMS-OTP branch.
            // Already in the ledger — the sale is done.
            ApprovalOutcome.WrittenImmediately -> {
                _status.value = OtpStatus.DONE
                onWritten()
            }

            // No signal. Nothing was written anywhere, so the sale stops rather than
            // completing on a promise nobody made.
            ApprovalOutcome.Unreachable -> _status.value = OtpStatus.APPROVAL_UNREACHABLE

            else -> _status.value = OtpStatus.ERROR
        }
    }

    /**
     * Polls until the customer answers.
     *
     * Faster than the approvals inbox polls (five seconds against fifteen) because somebody
     * is standing at the till watching this happen, and the gateway is waiting behind it.
     * An unknown answer keeps the loop running: not being able to reach the server is not
     * the same as being refused, and treating it as one would cancel a sale the customer
     * may already have approved.
     */
    private suspend fun awaitDecision(approvalId: String, onWritten: () -> Unit) {
        _status.value = OtpStatus.AWAITING_APPROVAL
        while (currentCoroutineContext().isActive) {
            when (repo.approvalStatus(approvalId)) {
                ApprovalStatus.APPROVED -> {
                    // The SERVER wrote the entry when the customer approved; the local copy
                    // arrives with the next book pull. Nothing is written here — doing so
                    // would put a second row in the ledger under a different id, which the
                    // insert-IGNORE cannot deduplicate.
                    _status.value = OtpStatus.DONE
                    onWritten()
                    return
                }

                ApprovalStatus.REJECTED -> {
                    _status.value = OtpStatus.REJECTED
                    return
                }

                // PENDING, or an answer this build could not read. Keep waiting.
                else -> delay(APPROVAL_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Path 2: books the payment and asks the gateway to take it.
     *
     * The entry is written first and locally, so a till with no signal still records the
     * payment — this half stays offline-first, unlike the approval path, because nobody
     * else's consent is required to accept money.
     *
     * The gateway is then told, and the outcome of THAT is deliberately not waited on. The
     * decision (recorded in the plan) is that a payment handed to the gateway counts as
     * taken: this is a mock gateway today, and the real handshake — listening for the
     * gateway's confirmation before booking — is a separate piece of work.
     *
     * TODO(pgw-handshake): wait for the gateway's real confirmation before writing, once
     *  the integration exists. Today the intent going out IS the confirmation.
     */
    private suspend fun collectPayment(
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        orderBody: OrderBody?,
        onWritten: () -> Unit
    ) {
        // orderBody is present only for a basket handoff; a keyed-in payment passes null.
        repo.addTransaction(
            Transaction(
                transactionId = UUID.randomUUID().toString(),
                sellerId = sellerId,
                customerId = customerId,
                amountMinor = amountMinor,
                type = TransactionType.PAYMENT,
                description = descriptionFor(TransactionType.PAYMENT),
                createdAt = createdAtFormat().format(Date())
            ),
            orderBody = orderBody
        )

        // The entry is safely in Room, so the screen can finish NOW. Reporting DONE before
        // the push is the offline-first rule in one line: the merchant is never made to
        // wait for a network round trip to close a sale.
        _status.value = OtpStatus.DONE
        _collectAtGateway.value = amountMinor
        onWritten()

        // Hand the push to WorkManager rather than running it here.
        //
        // onWritten() above closes the sale flow, which clears this ViewModel — a coroutine
        // started here would be cancelled mid-request, and even an application-scoped one
        // dies if the merchant swipes the app away. A work request survives both, and its
        // network constraint means an offline device is not woken to fail.
        syncScheduler.syncNow()
    }

    private fun descriptionFor(type: TransactionType): String =
        when (type) {
            TransactionType.DEBT -> "Veresiye"
            TransactionType.PAYMENT -> "Ödeme"
        }

    private companion object {
        /**
         * Five SECONDS, against the fifteen the approvals inbox uses.
         *
         * A merchant and a customer are standing at the till watching this, and the payment
         * gateway is holding a receipt open behind it — so the wait is the sale's own
         * latency, not background housekeeping. The inbox can afford to be lazier because
         * nobody is blocked on it.
         */
        const val APPROVAL_POLL_INTERVAL_MS = 5_000L

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
