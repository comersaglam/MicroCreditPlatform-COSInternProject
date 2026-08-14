package com.example.app_pos.network.api

import com.example.app_pos.network.dto.CustomerCreateDto
import com.example.app_pos.network.dto.CustomerDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The seller's book. Every read is scoped to the token's seller, so no sellerId is ever
 * passed — including the balances, which are that seller's view of the customer.
 */
interface CustomerApi {

    /** Opens a book entry for an app-less customer (UNCLAIMED). 409 if the phone exists. */
    @POST("customers")
    suspend fun create(@Body body: CustomerCreateDto): CustomerDto

    /** Customers this seller has at least one ledger entry with, plus derived balances. */
    @GET("customers")
    suspend fun list(): List<CustomerDto>

    @GET("customers/{id}")
    suspend fun byId(@Path("id") customerId: String): CustomerDto

    /**
     * Looks a person up by phone before booking an entry. A 404 means nobody holds the
     * number; a hit may still be someone only another shop knows, which is why the
     * decision of what that means belongs to the caller, not here.
     */
    @GET("customers/lookup")
    suspend fun lookupByPhone(@Query("phone") phone: String): CustomerDto
}
