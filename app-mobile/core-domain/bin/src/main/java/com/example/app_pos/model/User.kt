package com.example.app_pos.model

/**
 * An app-mobile ACCOUNT — a real person who signed in with their phone + OTP.
 *
 * This is NOT the same thing as [Customer]:
 *  - [Customer] is a MERCHANT'S LEDGER ENTRY (what app-pos knows). It can be
 *    UNCLAIMED — just a name + phone the merchant typed, with no account behind it.
 *  - [User] is an actual account. One account, two possible roles held as separate
 *    boolean flags: everyone starts a buyer; the "Become a seller" button flips
 *    isSeller on. Both roles can be active at once (a shopkeeper who also shops).
 *
 * The bridge between them is CLAIM: when a User signs in with a phone number that
 * matches an UNCLAIMED Customer, that customer record becomes CLAIMED and points
 * back to this User via Customer.claimedByUserId (the old debt is inherited).
 */
data class User(
    val userId: String,          // stable internal id (UUID); survives a phone change
    val phone: String,           // identity, unique; sign-in is by this (NOT nullable)
    val displayName: String,
    val isBuyer: Boolean,        // everyone starts as a buyer (true)
    val isSeller: Boolean,       // "Become a seller" sets this; both roles can be active
    val email: String?,          // optional profile field (filled in on app-mobile)
    val sellerInfo: SellerInfo?, // null while isSeller == false; non-null once a seller
    val createdAt: String        // same string format as Transaction: ISO-8601 UTC
)

/**
 * Seller-only details, kept in a separate class so the type enforces the rule:
 * a non-null [User.sellerInfo] guarantees every seller field is present. Putting
 * these straight on [User] as nullable fields would allow "isSeller = true but
 * shopName = null", which the compiler could not catch.
 */
data class SellerInfo(
    val shopName: String,        // shown in app-pos and in customer-facing logs
    val shopPhone: String?       // the shop's landline (may differ from the personal phone)
)
