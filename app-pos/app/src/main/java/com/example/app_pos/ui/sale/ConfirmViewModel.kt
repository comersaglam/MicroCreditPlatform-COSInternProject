package com.example.app_pos.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.Repository
import com.example.app_pos.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the confirm step: shows the selected customer's balance FRESH from the
 * ledger (not a copy carried from the pick step, which could be stale).
 *
 * The write is NOT here — it happens after OTP approval (see OtpFragment). This
 * screen only summarises what is about to happen and hands off to the OTP step.
 *
 * customerId is empty for a brand-new customer (created only at write time), so
 * the balance is treated as zero in that case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmViewModel(
    private val repo: Repository,
    private val customerId: String
) : ViewModel() {

    /** The customer's current balance with the signed-in seller; 0 if new. */
    val currentBalanceMinor: StateFlow<Long> =
        if (customerId.isEmpty()) {
            MutableStateFlow(0L)
        } else {
            repo.observeCurrentUser()
                .flatMapLatest { user ->
                    if (user == null) flowOf(emptyList())
                    else repo.observeTransactions(user.userId, customerId)
                }
                .map { txs ->
                    txs.sumOf { tx ->
                        when (tx.type) {
                            TransactionType.DEBT -> tx.amountMinor
                            TransactionType.INDEXATION -> tx.amountMinor
                            TransactionType.PAYMENT -> -tx.amountMinor
                        }
                    }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
        }
}
