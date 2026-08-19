package com.example.app_pos.data.remote

import com.example.app_pos.model.Customer
import com.example.app_pos.model.OrderBody
import com.example.app_pos.model.SellerInfo
import com.example.app_pos.model.Transaction
import com.example.app_pos.model.TransactionType
import com.example.app_pos.model.User
import com.example.app_pos.network.ApiResult
import com.example.app_pos.network.api.ApprovalApi
import com.example.app_pos.network.api.PgwJobApi
import com.example.app_pos.network.api.AuthApi
import com.example.app_pos.network.api.BuyerApi
import com.example.app_pos.network.api.CustomerApi
import com.example.app_pos.network.api.LedgerApi
import com.example.app_pos.network.api.SyncApi
import com.example.app_pos.network.api.UserApi
import com.example.app_pos.network.apiCall
import com.example.app_pos.network.dto.ApprovalDto
import com.example.app_pos.network.dto.PGW_KIND_COLLECT
import com.example.app_pos.network.dto.PgwJobCreateDto
import com.example.app_pos.network.dto.ApprovalSendResultDto
import com.example.app_pos.network.dto.OtpRequestDto
import com.example.app_pos.network.dto.OtpVerifyDto
import com.example.app_pos.network.dto.RefreshDto
import com.example.app_pos.network.dto.SellerDebtDto
import com.example.app_pos.network.dto.SessionDto
import com.example.app_pos.network.dto.SettleRequestDto
import com.example.app_pos.network.dto.SyncBatchDto
import com.example.app_pos.network.dto.SyncResultDto
import com.example.app_pos.network.dto.TransactionCreateDto
import com.example.app_pos.network.dto.UserPatchDto
import com.example.app_pos.network.mapper.approvalCreateDto
import com.example.app_pos.network.mapper.customerCreateDto
import com.example.app_pos.network.mapper.toBecomeSellerDto
import com.example.app_pos.network.mapper.toCreateDto
import com.example.app_pos.network.mapper.toDomain
import com.example.app_pos.network.mapper.toDomainList
import com.example.app_pos.network.mapper.toDomainOrNull
import com.example.app_pos.network.mapper.userCreateDto
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The REMOTE half of the data layer: the backend, expressed in domain types.
 *
 * Two rules hold everywhere in this class, and they are what make it safe to call from
 * an offline-first repository:
 *
 *  1. **Nothing throws.** Every call goes through [apiCall] and comes back as an
 *     [ApiResult], so a caller cannot accidentally swallow a failure in a `catch` — the
 *     failure is a value it has to handle. `isRetryable()` on that value is the outbox's
 *     retry rule.
 *  2. **DTOs stop here.** Callers get `User`/`Customer`/`Transaction`, never a `*Dto`,
 *     so wire-shape churn cannot leak into the repository or the ViewModels. The few
 *     exceptions are forward-phase calls with no domain type yet (approvals, sync), which
 *     return their DTO on purpose rather than inventing a model nothing consumes.
 *
 * NOTHING CALLS THIS YET. It is written now so phase 8 (the outbox drain) is a wiring job
 * rather than a design one, and so app-mobile can copy the module wholesale — which is
 * also why all seven APIs are wrapped even though app-pos itself has no buyer or approval
 * screens today.
 *
 * sellerId never appears in a request: the server derives it from the bearer token, and
 * trusting a body field for ownership is exactly the hole the contract closes.
 */
@Singleton
class RemoteDataSource @Inject constructor(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val customerApi: CustomerApi,
    private val ledgerApi: LedgerApi,
    private val buyerApi: BuyerApi,
    private val approvalApi: ApprovalApi,
    private val pgwJobApi: PgwJobApi,
    private val syncApi: SyncApi,
    // apiCall parses the error envelope with it, so the same Moshi that decodes responses
    // also decodes failures — one configuration, not two.
    private val moshi: Moshi
) {

    // --- auth ----------------------------------------------------------------

    /** Asks the server to send a code. The channel it chose comes back in the result. */
    suspend fun requestOtp(phone: String): ApiResult<Boolean> =
        apiCall(moshi) { authApi.requestOtp(OtpRequestDto(phone)).sent }

    /**
     * Verifies a code and returns the session. Deliberately returns the DTO: it is what
     * [com.example.app_pos.network.auth.TokenStore.save] consumes, and re-modelling it
     * into a domain type only to convert back would lose the expiry and refresh token.
     */
    suspend fun verifyOtp(phone: String, code: String): ApiResult<SessionDto> =
        apiCall(moshi) { authApi.verifyOtp(OtpVerifyDto(phone, code)) }

    /** Renews a session without user interaction. Also driven by TokenAuthenticator on a 401. */
    suspend fun refresh(refreshToken: String): ApiResult<SessionDto> =
        apiCall(moshi) { authApi.refresh(RefreshDto(refreshToken)) }

    suspend fun logout(): ApiResult<Unit> = apiCall(moshi) { authApi.logout() }

    // --- users ---------------------------------------------------------------

    suspend fun register(phone: String, displayName: String, isSeller: Boolean): ApiResult<User> =
        apiCall(moshi) { userApi.register(userCreateDto(phone, displayName, isSeller)).toDomain() }

    suspend fun me(): ApiResult<User> = apiCall(moshi) { userApi.me().toDomain() }

    /** Only the non-null fields are sent, so a null leaves that field untouched server-side. */
    suspend fun updateProfile(displayName: String? = null, email: String? = null): ApiResult<User> =
        apiCall(moshi) { userApi.updateProfile(UserPatchDto(displayName, email)).toDomain() }

    /** Adds the seller role; never clears the buyer one, since the roles are additive. */
    suspend fun becomeSeller(info: SellerInfo): ApiResult<User> =
        apiCall(moshi) { userApi.becomeSeller(info.toBecomeSellerDto()).toDomain() }

    /**
     * Claims every UNCLAIMED record written against this account's phone, and answers with
     * the records now held. The phone is the token's, so there is nothing to pass.
     */
    suspend fun claimMyRecords(): ApiResult<List<Customer>> =
        apiCall(moshi) { userApi.claimMyRecords().map { it.toDomain() } }

    // --- customers (implicitly scoped to the token's seller) -------------------

    suspend fun createCustomer(displayName: String, phone: String): ApiResult<Customer> =
        apiCall(moshi) { customerApi.create(customerCreateDto(displayName, phone)).toDomain() }

    suspend fun customers(): ApiResult<List<Customer>> =
        apiCall(moshi) { customerApi.list().map { it.toDomain() } }

    suspend fun customerById(customerId: String): ApiResult<Customer> =
        apiCall(moshi) { customerApi.byId(customerId).toDomain() }

    /**
     * A hit may be someone only ANOTHER shop knows; what that means for this seller's book
     * is the repository's decision, not this layer's. A 404 arrives as ApiError(404), not
     * as null, so "nobody holds this number" stays distinguishable from "the call failed".
     */
    suspend fun lookupCustomerByPhone(phone: String): ApiResult<Customer> =
        apiCall(moshi) { customerApi.lookupByPhone(phone).toDomain() }

    // --- ledger --------------------------------------------------------------

    /**
     * The single write point. The idempotency key IS the entry's transaction id, minted
     * on-device before the local write, so a retry after a lost response replays the
     * original entry instead of creating a second one.
     */
    suspend fun createTransaction(
        transaction: Transaction,
        orderBody: OrderBody? = null
    ): ApiResult<Transaction?> =
        createTransaction(transaction.toCreateDto(orderBody), transaction.transactionId)

    /**
     * The same write, from a body that was serialised earlier — how the outbox sends: the
     * queued row holds the exact payload agreed at approval time, so what reaches the
     * server cannot drift from what the merchant confirmed.
     *
     * [idempotencyKey] is passed rather than read from the body so the caller stays the one
     * responsible for replay identity; it is always the entry's transaction id.
     */
    suspend fun createTransaction(
        body: TransactionCreateDto,
        idempotencyKey: String
    ): ApiResult<Transaction?> =
        apiCall(moshi) {
            ledgerApi.create(idempotencyKey = idempotencyKey, body = body).toDomainOrNull()
        }

    /** Entries whose type this client cannot interpret are dropped — see toDomainList. */
    suspend fun transactionHistory(customerId: String): ApiResult<List<Transaction>> =
        apiCall(moshi) { ledgerApi.history(customerId).toDomainList() }

    suspend fun balance(customerId: String): ApiResult<Long> =
        apiCall(moshi) { ledgerApi.balance(customerId).balanceMinor }

    // --- buyer side (the token's user as the customer) ------------------------
    // Unused by app-pos's screens, but a shopkeeper is also a buyer elsewhere, and
    // app-mobile lives on these three.

    suspend fun myDebts(): ApiResult<List<SellerDebtDto>> = apiCall(moshi) { buyerApi.debts() }

    suspend fun myHistoryWithSeller(sellerId: String): ApiResult<List<Transaction>> =
        apiCall(moshi) { buyerApi.history(sellerId).toDomainList() }

    suspend fun myBalanceWithSeller(sellerId: String): ApiResult<Long> =
        apiCall(moshi) { buyerApi.balance(sellerId).balanceMinor }

    // --- approvals -----------------------------------------------------------

    suspend fun sendForApproval(
        sellerId: String,
        customerId: String,
        amountMinor: Long,
        type: TransactionType,
        description: String?,
        initiatorRole: String,
        targetUserId: String,
        origin: String
    ): ApiResult<ApprovalSendResultDto> =
        apiCall(moshi) {
            approvalApi.send(
                approvalCreateDto(
                    sellerId = sellerId,
                    customerId = customerId,
                    amountMinor = amountMinor,
                    type = type,
                    description = description,
                    initiatorRole = initiatorRole,
                    targetUserId = targetUserId,
                    origin = origin
                )
            )
        }

    // --- payment-gateway jobs -------------------------------------------------

    /**
     * Queues a payment for the signed-in seller's own till (path 4).
     *
     * Create only. Fetching and acking jobs is the TERMINAL's half — this device cannot
     * reach the gateway, so it has nothing to deliver.
     */
    suspend fun queueCollectAtTerminal(customerId: String, amountMinor: Long): ApiResult<Unit> =
        apiCall(moshi) {
            pgwJobApi.create(
                PgwJobCreateDto(
                    kind = PGW_KIND_COLLECT,
                    customerId = customerId,
                    amountMinor = amountMinor
                )
            )
        }

    suspend fun pendingApprovals(): ApiResult<List<ApprovalDto>> =
        apiCall(moshi) { approvalApi.pending() }

    /** Approving is what writes the ledger entry on this path. */
    suspend fun approve(approvalId: String): ApiResult<Transaction?> =
        apiCall(moshi) { approvalApi.approve(approvalId).toDomainOrNull() }

    suspend fun reject(approvalId: String): ApiResult<Unit> =
        apiCall(moshi) { approvalApi.reject(approvalId) }

    // --- forward phase (contract's `future` tag) ------------------------------

    /**
     * Drains the outbox in one round trip (phase 8). Each entry is idempotent on its own
     * transaction id, so resending a partially-applied batch is safe.
     */
    suspend fun syncTransactions(entries: List<Transaction>): ApiResult<SyncResultDto> =
        apiCall(moshi) { syncApi.syncTransactions(SyncBatchDto(entries.map { it.toCreateDto() })) }

    /** Only PAYMENTs settle: a DEBT ends at the ledger, since no money moved. */
    suspend fun settle(
        transactionId: String,
        receiptNo: String
    ): ApiResult<Transaction?> =
        apiCall(moshi) {
            syncApi.settle(transactionId, SettleRequestDto(receiptNo, settledViaPgw = true))
                .toDomainOrNull()
        }
}
