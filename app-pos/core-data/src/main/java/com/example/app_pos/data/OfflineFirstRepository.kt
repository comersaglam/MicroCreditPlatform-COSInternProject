package com.example.app_pos.data

import com.example.app_pos.data.local.LocalSource
import com.example.app_pos.data.remote.RemoteDataSource
import com.example.app_pos.data.sync.SyncEngine
import com.example.app_pos.model.SyncOutcome
import com.example.app_pos.network.dto.TransactionCreateDto
import com.example.app_pos.network.mapper.toCreateDto
import com.squareup.moshi.Moshi
import com.example.app_pos.model.Customer
import com.example.app_pos.model.CustomerLookup
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.Repository
import com.example.app_pos.model.SignInResult
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.User
import com.example.app_pos.network.ApiResult
import com.example.app_pos.network.auth.TokenStore
import com.example.app_pos.network.dto.UserDto
import com.example.app_pos.network.mapper.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [Repository] the app actually talks to: local storage, the backend and the session
 * store composed into one surface.
 *
 * The division of labour is the offline-first rule itself. Reads and writes go to Room, so
 * a POS with no signal keeps working; the network is not on the critical path of any screen
 * — phase 8 adds an outbox that drains [remote] in the background, which is why [remote] is
 * held but not yet called.
 *
 * What DOES change here versus the local source: the session. It now lives in [TokenStore]
 * (DataStore-backed), so it survives the process dying. That is the whole user-visible
 * payoff of this step — sign in once, and reopening the app does not ask again.
 *
 * The session reads stay SYNCHRONOUS because MainActivity chooses the navigation start
 * destination in onCreate, before the graph exists, and cannot await anything. TokenStore
 * serves them from a RAM cache primed once at startup, so this costs no disk I/O on the
 * main thread. See TokenStore's own note.
 */
@Singleton
class OfflineFirstRepository @Inject constructor(
    private val local: LocalSource,
    private val remote: RemoteDataSource,
    private val syncEngine: SyncEngine,
    private val tokens: TokenStore,
    moshi: Moshi
) : Repository {

    // Serialises the queued request body. Held here rather than built per write: adapter
    // creation is reflection-backed work that would otherwise repeat on every sale.
    private val payloadAdapter = moshi.adapter(TransactionCreateDto::class.java)

    // --- session -------------------------------------------------------------

    override fun isSessionValid(): Boolean = tokens.isValid()

    override fun currentSellerId(): String? = tokens.currentUserIdOrNull()

    /** Asks the server to send a code. False when it refused or could not be reached. */
    override suspend fun requestOtp(phone: String): Boolean =
        when (val result = remote.requestOtp(phone)) {
            is ApiResult.Success -> result.data
            else -> false
        }

    /**
     * Verifies the code, PERSISTS the returned session, and mirrors the user into Room.
     *
     * The session is now the SERVER's: a real token with a real expiry and a refresh
     * token, which is what makes TokenAuthenticator's 401 -> refresh -> retry path work at
     * all. Only the token's provenance changed — it still lands in the same TokenStore the
     * mock wrote to, so every screen reading the session is untouched.
     *
     * Whether the number has an account is the server's answer, never a local lookup. On a
     * fresh install Room is empty, so asking it first would send every number to the
     * sign-up prompt — including merchants who already have an account.
     *
     * The user row is mirrored into Room because [observeCurrentUser] resolves the
     * session's user id against the local user table; without the row the shopkeeper would
     * sign in successfully and land on a blank profile.
     */
    override suspend fun signIn(phone: String, code: String): SignInResult =
        when (val result = remote.verifyOtp(phone, code)) {
            is ApiResult.Success -> {
                mirrorUser(result.data.user)
                tokens.save(result.data)
                SignInResult.Success
            }

            is ApiResult.ApiError -> when (result.code) {
                // The server does not auto-register; this is a branch, not a failure.
                CODE_USER_NOT_FOUND -> SignInResult.NeedsRegister
                CODE_INVALID_CODE -> SignInResult.InvalidCode
                else -> SignInResult.Failed(result.message)
            }

            is ApiResult.NetworkError -> SignInResult.Unreachable
            is ApiResult.UnexpectedError -> SignInResult.Failed()
        }

    /**
     * Keeps the local user row in step with the account the server just authenticated.
     *
     * [LocalSource.upsertUser] rather than registerUser because the SERVER's user id has to
     * survive: the session stores that id, and observeCurrentUser matches it against the
     * local table. registerUser mints a fresh UUID, so the two would never line up and the
     * merchant would sign in successfully to an empty profile.
     */
    private suspend fun mirrorUser(user: UserDto) = local.upsertUser(user.toDomain())

    override suspend fun logout() {
        // Revoke server-side first, but clear locally NO MATTER WHAT it answers: a failed
        // revoke (no signal, an expired token) must not strand the merchant in a shell
        // that still looks signed in. The local clear is the one that decides the UI.
        runCatching { remote.logout() }
        tokens.clear()
        local.logout()
    }

    override fun observeCurrentUser(): Flow<User?> =
        // Re-reads on either input: a profile edit changes the user row, a sign-in or
        // sign-out changes the session. Emitting null on sign-out is what clears the
        // profile screen without any screen having to listen for logout separately.
        combine(local.observeAllUsers(), tokens.observeSession()) { users, session ->
            val userId = session?.takeIf { it.isValid(System.currentTimeMillis()) }?.userId
            userId?.let { id -> users.firstOrNull { it.userId == id } }
        }

    // --- everything else is local; the outbox (phase 8) is what will involve remote ---

    override val isPairedWithApp: Flow<Boolean> get() = local.isPairedWithApp

    override suspend fun pairWithApp() = local.pairWithApp()

    override suspend fun findUserByPhone(phone: String): User? = local.findUserByPhone(phone)

    /**
     * Creates the account ON THE SERVER, then mirrors it locally.
     *
     * The server has to own the user id: it is what the session carries and what every
     * seller-scoped row is keyed by. Registering only locally would mint a UUID the server
     * has never heard of, and the very next sign-in would replace it — stranding anything
     * written under the old id.
     *
     * Registration is idempotent by phone server-side, so a retry returns the existing
     * account rather than failing. If the server cannot be reached the local row is still
     * written, which keeps the merchant moving; the next successful sign-in re-mirrors the
     * authoritative copy over it.
     */
    override suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean): User =
        when (val result = remote.register(phone, displayName, isSeller)) {
            is ApiResult.Success -> result.data.also { local.upsertUser(it) }
            else -> local.registerUser(phone, displayName, isSeller)
        }

    override suspend fun setSeller(userId: String, shopName: String, shopPhone: String?) =
        local.setSeller(userId, shopName, shopPhone)

    override suspend fun updateDisplayName(userId: String, displayName: String) =
        local.updateDisplayName(userId, displayName)

    override suspend fun updateShopName(userId: String, shopName: String) =
        local.updateShopName(userId, shopName)

    override fun observeCustomers(sellerId: String): Flow<List<Customer>> =
        local.observeCustomers(sellerId)

    override suspend fun addCustomer(displayName: String, phone: String): String =
        local.addCustomer(displayName, phone)

    override suspend fun lookupCustomerForSeller(sellerId: String, phone: String): CustomerLookup =
        local.lookupCustomerForSeller(sellerId, phone)

    override suspend fun findCustomerById(sellerId: String, customerId: String): Customer? =
        local.findCustomerById(sellerId, customerId)

    override suspend fun findCustomerByPhone(sellerId: String, phone: String): Customer? =
        local.findCustomerByPhone(sellerId, phone)

    override fun observeTransactions(sellerId: String, customerId: String): Flow<List<Transaction>> =
        local.observeTransactions(sellerId, customerId)

    override fun observeTotalReceivableMinor(sellerId: String): Flow<Long> =
        local.observeTotalReceivableMinor(sellerId)

    override fun observeBalance(sellerId: String, customerId: String): Flow<Long> =
        local.observeBalance(sellerId, customerId)

    /**
     * Books the entry locally and queues it for the server — atomically, in one database
     * transaction (see LocalSource.addTransactionQueued).
     *
     * Deliberately does NOT wait for the network. The merchant's screen updates from Room
     * the moment this returns, whether or not there is signal; SyncEngine delivers the
     * entry afterwards. Sending inline would make a veresiye fail on a dead connection,
     * which on a shop floor is the common case, not the edge case.
     *
     * The wire body is serialised HERE, at approval time, and stored verbatim. So what
     * eventually reaches the server is exactly what was confirmed.
     */
    override suspend fun addTransaction(transaction: Transaction, orderBody: OrderBody?) {
        val payload = payloadAdapter.toJson(transaction.toCreateDto(orderBody))
        local.addTransactionQueued(transaction, orderBody, payload)
    }

    /**
     * Pushes whatever is queued. Safe to call at any time: it is a no-op on an empty queue,
     * only one drain runs at a time, and every send is idempotent.
     *
     * Called on sign-in and on app start (see App.onCreate). WorkManager-driven background
     * syncing is a separate step; this covers the case that matters most — a terminal that
     * was offline is opened again once there is signal.
     */
    override suspend fun syncNow(): SyncOutcome = syncEngine.drainOutbox()

    /** How many writes have not reached the server yet. */
    override fun observeUnsentCount(): Flow<Int> = local.observeUnsentCount()

    // --- helpers -------------------------------------------------------------

    /**
     * A locally-minted session in the SERVER's shape, so TokenStore stores and expires it
     * with exactly the code path a real one takes. No refresh token: nothing can renew a
     * token no server issued, and pretending otherwise would exercise a branch that cannot
     * work.
     */

    private companion object {
        // Error codes the sign-in flow branches on, exactly as the contract spells them.
        const val CODE_USER_NOT_FOUND = "user_not_found"
        const val CODE_INVALID_CODE = "invalid_code"

        /**
         * A new formatter per call: SimpleDateFormat is not thread-safe. ISO-8601 UTC is
         * the contract's timestamp format, and the one DataStoreTokenStore parses back.
         */
        fun isoFormat(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
}
