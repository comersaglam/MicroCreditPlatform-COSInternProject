package com.example.app_pos.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app_pos.model.Repository
import com.example.app_pos.model.SignInResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.example.app_pos.model.OtpRequestResult
import com.example.app_pos.model.PhoneFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where the sign-in is in its lifecycle.
 *
 * CODE_SENT is the second half of the flow: the number was accepted and a code is on its
 * way, so the screen swaps the phone field for the code field.
 *
 * NEEDS_REGISTER: the number is valid but has no account — the screen asks to confirm
 * registration, then [register] creates it and signs in.
 */
enum class LoginState {
    IDLE, SUBMITTING, CODE_SENT, SUCCESS, ERROR, NEEDS_REGISTER, INVALID_CODE, OFFLINE
}

/**
 * The merchant sign-in: phone, then the code that came back.
 *
 * Two round trips with a person in the middle, which is why it is two methods rather than
 * one: [sendCode] asks the server to dispatch a code, [verify] exchanges what the merchant
 * typed for a real session.
 *
 * Whether the number has an account is decided BY THE SERVER, never by a local lookup.
 * Verify does not auto-register, so an unknown number comes back as NeedsRegister; asking
 * Room first would send every merchant to the sign-up prompt on a fresh install, because a
 * fresh install has an empty database.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState.IDLE)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    // The number the flow is about, in E.164. Single source of truth for both the code
    // step and the register prompt, so neither needs it passed back in.
    private var pendingPhone: String? = null

    /** The number being registered, for the confirmation prompt. Already E.164. */
    val pendingPhoneDisplay: String? get() = pendingPhone

    /**
     * Step one: normalise the number and ask the server to send a code.
     *
     * A number the server does not know still gets a code here — whether an account exists
     * is deliberately not revealed at this point, and comes out at verify time to somebody
     * who has proved they hold the phone.
     */
    fun sendCode(phone: String?) {
        val stored = phone?.let { PhoneFormat.toStored(it) }
        if (stored == null) {
            _state.value = LoginState.ERROR
            return
        }
        pendingPhone = stored
        _state.value = LoginState.SUBMITTING
        viewModelScope.launch {
            _state.value =
                when (repo.requestOtp(stored)) {
                    is OtpRequestResult.Sent -> LoginState.CODE_SENT
                    // Refused vs unreachable: the number is wrong, or the server was never
                    // reached. OFFLINE already says the latter honestly.
                    is OtpRequestResult.Refused -> LoginState.ERROR
                    is OtpRequestResult.Unreachable -> LoginState.OFFLINE
                }
        }
    }

    /**
     * Step two: exchange the typed code for a session.
     *
     * Each outcome leads somewhere different, which is why the repository answers with a
     * sealed result rather than a Boolean: a wrong code is retried right here, an unknown
     * number opens the sign-up prompt, and an unreachable server is nobody's mistake.
     */
    fun verify(code: String?) {
        val phone = pendingPhone ?: return
        if (code.isNullOrBlank()) {
            _state.value = LoginState.INVALID_CODE
            return
        }
        _state.value = LoginState.SUBMITTING
        viewModelScope.launch {
            _state.value = when (repo.signIn(phone, code.trim())) {
                SignInResult.Success -> LoginState.SUCCESS
                SignInResult.NeedsRegister -> LoginState.NEEDS_REGISTER
                SignInResult.InvalidCode -> LoginState.INVALID_CODE
                SignInResult.Unreachable -> LoginState.OFFLINE
                is SignInResult.Failed -> LoginState.ERROR
            }
        }
    }

    /** Back to the phone field, e.g. the merchant mistyped the number. */
    fun editPhone() {
        _state.value = LoginState.IDLE
    }

    /**
     * Confirms registration of the pending number: create the account, then sign in.
     *
     * The code is requested again because the first one was spent on the verify attempt
     * that returned NeedsRegister — reusing it would fail, and asking the merchant to type
     * the same digits again is better than a sign-in that mysteriously does not work.
     */
    fun register() {
        val phone = pendingPhone ?: return
        _state.value = LoginState.SUBMITTING
        viewModelScope.launch {
            // app-pos registers a seller; the name is blank and filled in from profile.
            repo.registerUser(phone, displayName = "", isSeller = true)
            _state.value =
                when (repo.requestOtp(phone)) {
                    is OtpRequestResult.Sent -> LoginState.CODE_SENT
                    is OtpRequestResult.Refused -> LoginState.ERROR
                    is OtpRequestResult.Unreachable -> LoginState.OFFLINE
                }
        }
    }

    /** The merchant declined to register; return to the idle input state. */
    fun cancelRegister() {
        pendingPhone = null
        _state.value = LoginState.IDLE
    }
}
