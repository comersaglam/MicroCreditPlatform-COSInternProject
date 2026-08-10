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
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.User
import com.example.app_pos.network.auth.TokenStore
import com.example.app_pos.network.dto.SessionDto
import com.example.app_pos.network.dto.UserDto
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
    @Suppress("unused") private val remote: RemoteDataSource,
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

    /**
     * Signs in and PERSISTS the session.
     *
     * Still a local mock: the number is checked against the user table, not the server, so
     * the app stays usable with no backend running (the contract mock is a dev-machine
     * process, not a dependency of the shop floor). What is no longer a mock is where the
     * result goes — a real StoredSession on disk, through the same TokenStore a real
     * sign-in will write to. So the persistence path is exercised now, and swapping in
     * [RemoteDataSource.verifyOtp] later replaces the token's provenance and nothing else.
     */
    override suspend fun login(phone: String?): Boolean {
        val user = phone?.let { local.findUserByPhone(it) } ?: return false
        // TODO(phase 4b): remote.requestOtp(phone) → remote.verifyOtp(phone, code) and save
        //  the server's SessionDto instead of minting one here.
        tokens.save(mockSession(user))
        return true
    }

    override suspend fun logout() {
        // TODO(phase 4b): remote.logout() to revoke server-side, then clear regardless of
        //  its result — a failed revoke must not strand the user in a signed-in shell.
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

    override suspend fun registerUser(phone: String, displayName: String, isSeller: Boolean): User =
        local.registerUser(phone, displayName, isSeller)

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
    private fun mockSession(user: User): SessionDto = SessionDto(
        token = UUID.randomUUID().toString(),
        refreshToken = null,
        expiresAt = isoFormat().format(Date(System.currentTimeMillis() + SESSION_TTL_MILLIS)),
        user = UserDto(
            userId = user.userId,
            phone = user.phone,
            displayName = user.displayName,
            isBuyer = user.isBuyer,
            isSeller = user.isSeller,
            email = user.email,
            sellerInfo = null,
            createdAt = user.createdAt
        )
    )

    private companion object {
        const val SESSION_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

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
