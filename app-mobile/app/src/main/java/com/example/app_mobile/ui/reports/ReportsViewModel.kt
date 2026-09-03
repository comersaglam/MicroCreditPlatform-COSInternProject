package com.example.app_mobile.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The one live fact on an otherwise mock screen: does this account also keep a book?
 *
 * That decides whether the buyer/seller chooser is shown at all. Everything the screen
 * draws comes from [InsightsMockData] (Turn 46, decision 46.1 — deferred.md §L.17); this is
 * the only thing it asks the repository, because it is the only thing that differs between
 * one signed-in user and the next.
 *
 * Starts false, which is the honest default while the first emission is in flight: a
 * chooser that flashes in and out is worse than one that appears a beat late.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    repo: Repository
) : ViewModel() {

    val isSeller: StateFlow<Boolean> =
        repo.observeCurrentUser()
            .map { it?.isSeller == true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
