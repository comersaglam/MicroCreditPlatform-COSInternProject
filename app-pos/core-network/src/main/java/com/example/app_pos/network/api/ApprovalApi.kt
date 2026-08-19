package com.example.app_pos.network.api

import com.example.app_pos.network.dto.ApprovalCreateDto
import com.example.app_pos.network.dto.ApprovalDto
import com.example.app_pos.network.dto.ApprovalSendResultDto
import com.example.app_pos.network.dto.TransactionDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The approval gate every write passes through, in whichever of the three directions it
 * started. app-pos only sends today; app-mobile is the side that renders an inbox.
 */
interface ApprovalApi {

    /**
     * Sends a write for the counterparty's approval.
     *
     * When the target holds the app this creates a PENDING approval and nothing is
     * written yet; when they do not, the server writes the entry immediately over the
     * SMS-OTP branch. The response carries whichever happened — see
     * [ApprovalSendResultDto], which holds both shapes and tells them apart.
     *
     * Was typed as `ApprovalDto`, which could not represent the immediate-write answer at
     * all: the client could not see that an entry had already been booked.
     */
    @POST("approvals")
    suspend fun send(@Body body: ApprovalCreateDto): ApprovalSendResultDto

    /**
     * Approvals waiting on the signed-in user to answer.
     *
     * [role] narrows the inbox to one side of the account. A terminal asks for SELLER: the
     * till is a shop tool, and its owner's personal debts at ANOTHER shop are not its
     * business — nor could it show the result of approving one, since a personal debt
     * appears on no POS screen. Null asks for both, which is what a phone wants.
     */
    @GET("approvals")
    suspend fun pending(@Query("role") role: String? = null): List<ApprovalDto>

    /**
     * One approval by id — how the side that RAISED a request learns the answer.
     *
     * [pending] cannot serve this: it lists what is addressed to you, and the initiator is
     * never the one who decides. A till holding a sale open reads its own request here.
     */
    @GET("approvals/{id}")
    suspend fun byId(@Path("id") approvalId: String): ApprovalDto

    /** Approving is what writes the ledger entry — the single write point on this path. */
    @POST("approvals/{id}/approve")
    suspend fun approve(@Path("id") approvalId: String): TransactionDto

    /** Rejecting writes nothing; the row survives with its status changed, as an audit trail. */
    @POST("approvals/{id}/reject")
    suspend fun reject(@Path("id") approvalId: String)
}
