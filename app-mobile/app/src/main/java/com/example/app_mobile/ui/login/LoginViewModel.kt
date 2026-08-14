package com.example.app_mobile.ui.login

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
 * CODE_SENT: the server accepted the number and sent a code — the screen swaps the phone
 * field for the code field. NEEDS_REGISTER: the SERVER reported the number has no account,
 * so the screen offers to create one.
 */
enum class LoginState {
    IDLE, SUBMITTING, CODE_SENT, SUCCESS, NEEDS_REGISTER,
    /** The number itself was refused — the user has to change what they typed. */
    ERROR,
    /**
     * The server was never reached. Kept apart from ERROR because the two ask for opposite
     * things: ERROR means "fix the number", this means "the number is fine, try again".
     * Collapsing them told a user with a dropped connection that their valid number was
     * invalid.
     */
    UNREACHABLE
}

/**
 * The customer sign-in — phone, then the code the server sent.
 *
 * One screen, two steps, mirroring app-pos's Turn 34 login. The account it creates is a
 * BUYER (isSeller = false) — the whole difference from app-pos, which registers a seller.
 * On success the account CLAIMS any merchant records holding its number, which is how a
 * customer inherits debt a shop wrote before they had the app.
 *
 * Every decision here is the SERVER's: whether the code is right, and whether the number
 * has an account at all. There is deliberately no local findUserByPhone check first — on a
 * fresh install Room is empty, so asking it would send even an existing customer to the
 * sign-up prompt (the exact bug app-pos hit in Turn 34).
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repo: Repository
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState.IDLE)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    /** Set once the code is on its way, and needed by every step after. */
    private var pendingPhone: String? = null
    val pendingPhoneDisplay: String? get() = pendingPhone

    /** A message for the ERROR state, when the server explained itself. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** True once a code has come back wrong, so the code step can say so. */
    private val _codeRejected = MutableStateFlow(false)
    val codeRejected: StateFlow<Boolean> = _codeRejected.asStateFlow()

    /** Step 1: ask the server to text a code to this number. */
    fun requestCode(phone: String?) {
        val stored = phone?.let { PhoneFormat.toStored(it) }
        if (stored == null) {
            _errorMessage.value = null   // a malformed number needs no server explanation
            _state.value = LoginState.ERROR
            return
        }
        _state.value = LoginState.SUBMITTING
        viewModelScope.launch {
            when (repo.requestOtp(stored)) {
                is OtpRequestResult.Sent -> {
                    pendingPhone = stored
                    _codeRejected.value = false
                    _state.value = LoginState.CODE_SENT
                }
                // The server answered and declined the number itself.
                is OtpRequestResult.Refused -> {
                    _errorMessage.value = null
                    _state.value = LoginState.ERROR
                }
                // Never reached. Says so, instead of blaming a number that is fine —
                // a pulled cable used to read as "Geçersiz numara".
                is OtpRequestResult.Unreachable -> {
                    _errorMessage.value = null
                    _state.value = LoginState.UNREACHABLE
                }
            }
        }
    }

    /**
     * Step 2: verify the code.
     *
     * The four outcomes are kept apart because they need different things from the user: a
     * wrong code is retyped on this screen, an unknown number becomes an offer to register,
     * and an unreachable server is nobody's mistake and should say so rather than reading
     * as "wrong code".
     */
    fun submitCode(code: String) {
        val phone = pendingPhone ?: return
        _state.value = LoginState.SUBMITTING
        viewModelScope.launch {
            _state.value = when (val result = repo.signIn(phone, code)) {
                is SignInResult.Success -> {
                    claimRecords(phone)
                    LoginState.SUCCESS
                }
                is SignInResult.NeedsRegister -> LoginState.NEEDS_REGISTER
                is SignInResult.InvalidCode -> {
                    // Stay on the code step so the user can simply retype it, but say WHY —
                    // otherwise the screen looks identical to the code having just been sent.
                    _codeRejected.value = true
                    LoginState.CODE_SENT
                }
                is SignInResult.Unreachable -> {
                    _errorMessage.value = null
                    LoginState.UNREACHABLE
                }
                is SignInResult.Failed -> {
                    _errorMessage.value = result.message
                    LoginState.ERROR
                }
            }
        }
    }

    /**
     * Confirms registration of the pending number: create a BUYER, then sign in.
     *
     * Signs in with the same code the user already entered — the server keeps it valid for
     * the whole exchange, so making them wait for a second SMS would be friction with no
     * security gain.
     */
    fun register(code: String) {
        val phone = pendingPhone ?: return
        _state.value = LoginState.SUBMITTING
        viewModelScope.launch {
            // app-mobile registers a buyer (isSeller = false); name fills in from profile.
            repo.registerUser(phone, displayName = "", isSeller = false)
            _state.value = if (repo.signIn(phone, code) is SignInResult.Success) {
                claimRecords(phone)
                LoginState.SUCCESS
            } else {
                LoginState.ERROR
            }
        }
    }

    fun cancelRegister() {
        pendingPhone = null
        _errorMessage.value = null
        _codeRejected.value = false
        _state.value = LoginState.IDLE
    }

    /** Back to the phone step, e.g. the number was mistyped. */
    fun editPhone() {
        pendingPhone = null
        _errorMessage.value = null
        _codeRejected.value = false
        _state.value = LoginState.IDLE
    }

    /**
     * Links every UNCLAIMED customer record holding this number to the new session, which
     * is how a customer inherits debt written before they had the app.
     */
    private suspend fun claimRecords(phone: String) {
        repo.currentUserId()?.let { userId -> repo.claimCustomerForUser(userId, phone) }
    }
}
