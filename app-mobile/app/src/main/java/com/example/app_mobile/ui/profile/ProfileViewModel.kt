package com.example.app_mobile.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.User
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the profile screen shows: the signed-in user plus, for a seller, whether the
 * POS is paired yet. The login gate guarantees a session, so user is null only during
 * the brief logout→login transition.
 */
data class ProfileUiState(
    val user: User,
    val isPaired: Boolean
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState?> =
        combine(
            repo.observeCurrentUser(),
            repo.isPairedWithApp
        ) { user, paired ->
            if (user == null) null else ProfileUiState(user, paired)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ends the session; the caller then navigates back to the login gate. The repo
     *  call is suspend now (persisted state), so it runs in viewModelScope. */
    fun logout() {
        viewModelScope.launch { repo.logout() }
    }

    fun updateDisplayName(name: String) {
        currentUserId()?.let { id -> viewModelScope.launch { repo.updateDisplayName(id, name) } }
    }

    fun updateEmail(email: String) {
        currentUserId()?.let { id -> viewModelScope.launch { repo.updateEmail(id, email) } }
    }

    /** "Satıcı ol": flips the account into a seller with the given shop name. */
    fun becomeSeller(shopName: String) {
        if (shopName.isBlank()) return
        currentUserId()?.let { id -> viewModelScope.launch { repo.setSeller(id, shopName) } }
    }

    /** Updates just the shop name (already a seller). */
    fun updateShopName(shopName: String) {
        if (shopName.isBlank()) return
        currentUserId()?.let { id -> viewModelScope.launch { repo.updateShopName(id, shopName) } }
    }

    // Read from the session, not from uiState: stateIn(WhileSubscribed) holds null
    // whenever nothing is collecting, which would silently drop an edit.
    private fun currentUserId(): String? = repo.currentUserId()
}
