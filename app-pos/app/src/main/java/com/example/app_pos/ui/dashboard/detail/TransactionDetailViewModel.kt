package com.example.app_pos.ui.dashboard.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.Repository
import com.example.app_pos.model.TransactionDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the detail screen is showing at any moment.
 *
 * Three states, not two: [Loading] is distinct from [Missing] because a database read that
 * has not finished and a row that is not on this device look identical if they share a
 * state, and the screen would flash "not found" on every open.
 */
sealed interface TransactionDetailUiState {
    data object Loading : TransactionDetailUiState
    data class Content(val detail: TransactionDetail) : TransactionDetailUiState

    /**
     * The id is not in this device's ledger.
     *
     * Distinct from a Content whose basket is null: that is an entry with nothing to
     * itemise, which is ordinary. This is the entry itself being absent, which after a
     * database rebuild is possible and still not an error worth alarming anyone about.
     */
    data object Missing : TransactionDetailUiState
}

/**
 * One ledger entry and the basket it was rung up from.
 *
 * Read ONCE, not observed. The other detail screens expose Flows because their balances
 * move as entries land; an entry itself never changes, so a Flow here would be a
 * subscription that can never fire. The single read is also what keeps the two halves
 * consistent: repository.transactionDetail returns them together, from one database
 * transaction.
 */
@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val repo: Repository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Navigation puts the destination's arguments into the SavedStateHandle under their
    // declared names, so the nav arg arrives without a hand-written factory — and survives
    // process death, which a factory's captured value would not.
    private val transactionId: String = checkNotNull(savedStateHandle["transactionId"]) {
        "transactionDetailFragment requires a transactionId argument"
    }

    private val _uiState = MutableStateFlow<TransactionDetailUiState>(
        TransactionDetailUiState.Loading
    )
    val uiState: StateFlow<TransactionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val detail = repo.transactionDetail(transactionId)
            _uiState.value =
                if (detail == null) TransactionDetailUiState.Missing
                else TransactionDetailUiState.Content(detail)
        }
    }
}
