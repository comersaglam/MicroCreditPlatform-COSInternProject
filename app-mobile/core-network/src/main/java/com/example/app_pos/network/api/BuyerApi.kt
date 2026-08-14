package com.example.app_pos.network.api

import com.example.app_pos.network.dto.BalanceDto
import com.example.app_pos.network.dto.SellerDebtDto
import com.example.app_pos.network.dto.TransactionDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The buyer's side of the same ledger: the mirror image of the seller-scoped reads, with
 * the token's user as the customer instead of the seller.
 *
 * Written even though app-pos has no buyer screens, for two reasons: a shopkeeper is also
 * a buyer at other shops (the seed's u_owner holds both roles), and app-mobile — which
 * lives on these endpoints — will copy this module rather than reinvent it.
 */
interface BuyerApi {

    /** What the buyer owes across every shop, grouped by seller. */
    @GET("me/debts")
    suspend fun debts(): List<SellerDebtDto>

    @GET("me/transactions")
    suspend fun history(@Query("seller_id") sellerId: String): List<TransactionDto>

    @GET("me/balances")
    suspend fun balance(@Query("seller_id") sellerId: String): BalanceDto
}
