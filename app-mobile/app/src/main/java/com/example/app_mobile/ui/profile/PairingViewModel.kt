package com.example.app_mobile.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the pairing is in its confirm → done lifecycle. */
enum class PairingStatus { IDLE, PAIRING, DONE, ERROR }

/**
 * Pairs this app with the seller's POS terminal (phone-number axis; QR/NFC is a
 * later phase). Mock: a short delay stands in for the handshake, then the flag flips.
 * Mirrors app-pos's PairingViewModel; the flag write (pairWithApp) and screen stay
 * when the real handshake lands.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    private val _status = MutableStateFlow(PairingStatus.IDLE)
    val status: StateFlow<PairingStatus> = _status.asStateFlow()

    fun confirmPairing() {
        _status.value = PairingStatus.PAIRING
        viewModelScope.launch {
            delay(300) // stand-in for the pairing round trip
            repo.pairWithApp()
            _status.value = PairingStatus.DONE
        }
    }
}
