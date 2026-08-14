package com.example.app_mobile.ui.debts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.SellerDebt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
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
}
