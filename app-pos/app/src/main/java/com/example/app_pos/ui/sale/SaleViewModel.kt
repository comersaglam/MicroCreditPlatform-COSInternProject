package com.example.app_pos.ui.sale

import androidx.lifecycle.ViewModel
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State shared by every screen of the sale flow.
 *
 * Scoped to the saleFlow nested graph (see nav_graph.xml), so all fragments in
 * the flow read and write the same instance, and leaving the flow destroys it —
 * the next sale starts clean without any manual reset.
 *
 * One flow serves both transaction types (txType): a DEBT started from the
 * payment app (amount handed over via Intent) and a PAYMENT started from a
 * customer's detail screen (amount keyed in on the in-app keypad). Both share the
 * same confirm → OTP → write pipeline.
 *
 * The amount lives in minor units (kuruş) as a Long, exactly like the ledger
 * stores it, so no floating point or string parsing is ever involved.
 */
class SaleViewModel : ViewModel() {

    /** DEBT (veresiye written) or PAYMENT (money collected). Set on entry. */
    var txType: TransactionType = TransactionType.DEBT
        private set

    fun setTxType(type: TransactionType) {
        txType = type
    }

    private val _amountMinor = MutableStateFlow(0L)

    /** Amount for this entry; from the Intent (DEBT) or the keypad (PAYMENT). */
    val amountMinor: StateFlow<Long> = _amountMinor.asStateFlow()

    /** Loads the amount handed over by the payment app (Intent extra, kuruş). */
    fun setAmount(minor: Long) {
        _amountMinor.value = minor
    }

    /**
     * The basket handed over by the PGW (mock-pos), if this sale came from a
     * payment-app handoff. Null for a PAYMENT keyed in on the in-app keypad.
     *
     * Set by KeypadFragment from the nav argument, and read at the write step, which
     * persists the basket and its items alongside the entry. Those two ends were written
     * long before anything joined them: for several turns this was set by nobody, so every
     * handoff booked money-only and no basket was ever stored (docs/deferred.md J).
     */
    var orderBody: OrderBody? = null
        private set

    /** Records the PGW basket for this sale (set on a CREDIT handoff). */
    fun setOrderBody(body: OrderBody?) {
        orderBody = body
    }

    // --- Keypad, for the in-app PAYMENT flow (mirrors mock-pos PaymentViewModel).
    // Digits shift in from the right the way a POS keypad behaves.

    /** Appends one digit; capped so a stuck key cannot overflow the Long. */
    fun onDigit(digit: Int) {
        val next = _amountMinor.value * 10 + digit
        if (next <= 999_999_999L) _amountMinor.value = next
    }

    /** Removes the last digit — the inverse of onDigit. */
    fun onBackspace() {
        _amountMinor.value /= 10
    }

    /** Resets the amount to zero. */
    fun onClear() {
        _amountMinor.value = 0L
    }

    /** An entry can only proceed with a non-zero amount. */
    fun hasAmount(): Boolean = _amountMinor.value > 0L

    private val _selectedCustomer = MutableStateFlow<SelectedCustomer?>(null)

    /** Who the entry will be written to; null until one is chosen. */
    val selectedCustomer: StateFlow<SelectedCustomer?> = _selectedCustomer.asStateFlow()

    /**
     * Records the chosen customer. isNew = true means it does not exist yet and
     * must be created (addCustomer) at write time; customerId is empty until then.
     */
    fun onCustomerSelected(
        customerId: String,
        displayName: String,
        phone: String,
        isNew: Boolean
    ) {
        _selectedCustomer.value = SelectedCustomer(customerId, displayName, phone, isNew)
    }
}

/**
 * The customer chosen for this sale.
 *
 * The balance is intentionally not carried — it would be stale by write time, so
 * the confirm step reads it fresh. Phone travels because it is the identity used
 * for OTP and, for a new customer, the record created at write time.
 */
data class SelectedCustomer(
    val customerId: String,
    val displayName: String,
    val phone: String,
    val isNew: Boolean
)
