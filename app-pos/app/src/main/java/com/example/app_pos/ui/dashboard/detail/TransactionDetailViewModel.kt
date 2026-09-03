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
import com.example.app_pos.util.toTlString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.roundToLong

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

    /**
     * What the entry was worth in dollars then, and what the same lira are worth now.
     *
     * A separate flow from [uiState] on purpose: this one may never arrive. The basket is
     * on disk and renders immediately; the rates come from the server, so binding them
     * together would hold a local read hostage to a network call.
     *
     * Null means no line. That covers a date the series does not reach, no signal, and a
     * server that is down — the screen does the same thing with all three, and explaining
     * which one to the reader would be explaining the absence of something they were never
     * promised.
     */
    private val _fxNote = MutableStateFlow<FxNote?>(null)
    val fxNote: StateFlow<FxNote?> = _fxNote.asStateFlow()

    init {
        viewModelScope.launch {
            val detail = repo.transactionDetail(transactionId)
            if (detail == null) {
                _uiState.value = TransactionDetailUiState.Missing
                return@launch
            }

            _uiState.value = TransactionDetailUiState.Content(detail)
            loadFxNote(detail.transaction.amountMinor, detail.transaction.createdAt)
        }
    }

    private suspend fun loadFxNote(amountMinor: Long, createdAt: String) {
        // createdAt is ISO-8601 UTC and the server indexes fx_rates by its own dates, so
        // the first ten characters ARE the key to ask for. Converting to local time first
        // would shift an entry booked just after midnight onto the previous day and quote
        // the wrong reading — this substring is correct, not lazy.
        val then = repo.fxRateAt(createdAt.take(10)) ?: return
        val now = repo.fxRateAt(todayIsoDate()) ?: return

        // Both or neither. One half of a comparison is not a comparison, and "worth 12,17
        // dollars then" with nothing to weigh it against says less than silence.
        if (then.usdMinor <= 0L || now.usdMinor <= 0L) return

        // What the entry bought in dollars on the day it was written, and what those same
        // dollars cost today.
        //
        // The SECOND figure is the one that matters, and framing it this way round is the
        // whole point. "It was 12,17 dollars, now it is 10,37" is true and reads as the
        // debt shrinking — which is exactly backwards. Nobody paid anything: the lira moved.
        // Restating the same purchase in today's money (586,78 TL against the 500,00 on the
        // books) says what actually happened, in the currency the reader thinks in.
        val dollarsThen = amountMinor.toDouble() / then.usdMinor
        val equivalentNowMinor = (dollarsThen * now.usdMinor).roundToLong()

        _fxNote.value = FxNote(
            thenUsd = String.format(Locale("tr", "TR"), "%.2f", dollarsThen),
            equivalentNow = equivalentNowMinor.toTlString()
        )
    }

    private fun todayIsoDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}

/**
 * The two lines of the exchange-rate note, already formatted.
 *
 * [equivalentNow] is lira, not dollars: what the same purchase costs at today's rate. The
 * comparison is with the amount on the entry above it, which is the point of showing it.
 */
data class FxNote(val thenUsd: String, val equivalentNow: String)
