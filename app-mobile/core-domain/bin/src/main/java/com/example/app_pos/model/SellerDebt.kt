package com.example.app_pos.model

/**
 * One row on the buyer's debt list: a shop they owe and how much.
 *
 * A read projection rather than a stored entity — the balance is summed from the
 * ledger and the shop name is joined in from the seller's account. It lives in the
 * domain module because [Repository] returns it: the buyer-side mirror of the
 * seller's customer list.
 */
data class SellerDebt(
    val sellerId: String,
    val shopName: String,
    val balanceMinor: Long
)
