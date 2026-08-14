package com.example.app_pos.data

import android.content.ContentValues
import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Seeds the demo data the first time the database is created, so the app opens with the
 * same accounts/ledger the old FakeRepository had. Runs once (onCreate), on a Room IO
 * thread. Inserts go straight through SupportSQLiteDatabase (the DAOs are not available
 * inside the callback), matching the entity column names exactly.
 *
 * The seed spans TWO sellers on purpose, so the buyer's debt list is not trivially one
 * row: u1 holds two customer records (c1 at Ahmet Bakkal, m1 at Ayşe Market) — the
 * multi-shop case that the per-seller balance rule exists for.
 *
 * Resulting balances: u1 owes 40,00 to Ahmet Bakkal + 100,00 to Ayşe Market (140,00
 * total); u_owner owes 60,00 to Ayşe Market; u_owner's own book totals 235,50
 * (Mehmet 0 / Fatma 25,50 / Hasan 210,00) — identical to the fake.
 *
 * Insert order matters: customers.claimedByUserId is a FK to users.
 *
 * TIMESTAMPS are ISO-8601 UTC, byte-identical to `backend/app/seed.py` — that seed was
 * derived from THIS one, converting the old Istanbul (UTC+3) literals it used to carry
 * ("20.07.2026 09:15" -> "2026-07-20T06:15:00Z"). Keeping both sides on the same instants
 * means a device seeded offline shows the same history the server would send.
 */
internal class SeedCallback(private val context: Context) : RoomDatabase.Callback() {

    /**
     * Seeds on the first open of an empty database — whether it was just created or was
     * emptied by a destructive migration (every version bump of the mock phase drops and
     * recreates the tables, and the demo data is still wanted afterwards).
     *
     * onOpen rather than onCreate/onDestructiveMigration because BOTH of those fire while
     * the schema is still being built, so inserting from them fails with "no such table".
     * By onOpen the tables exist; the emptiness check keeps the seed a one-off instead of
     * something that runs on every launch.
     */
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        if (isEmpty(db)) seed(db)
    }

    /** True when no user row exists — the marker that this database was never seeded. */
    private fun isEmpty(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT COUNT(*) FROM users").use { cursor ->
            !cursor.moveToFirst() || cursor.getInt(0) == 0
        }

    private fun seed(db: SupportSQLiteDatabase) {

        // Two shopkeepers (both also buyers — one account, two roles). A person's name
        // and their shop's name are deliberately DIFFERENT: buyer-facing screens show
        // the shop, seller-facing screens show the person, and identical names would
        // hide a screen pulling the wrong one.
        user(db, "u_owner", "+905554443322", "Ahmet Demirtaş", isSeller = true,
            shopName = "Ahmet Bakkal", shopPhone = "+902121112233", createdAt = "2026-07-01T06:00:00Z")
        user(db, "u_market", "+905553334455", "Ayşe Korkmaz", isSeller = true,
            shopName = "Ayşe Market", shopPhone = "+902123334455", createdAt = "2026-07-02T06:00:00Z")
        // … and two plain buyers.
        user(db, "u1", "+905551112233", "Ahmet Yılmaz", isSeller = false,
            shopName = null, shopPhone = null, createdAt = "2026-07-05T09:30:00Z")
        user(db, "u3", "+905554445566", "Mehmet Kaya", isSeller = false,
            shopName = null, shopPhone = null, createdAt = "2026-07-08T12:45:00Z")

        // u1 is one person with a record in each shop's book (c1 and m1).
        customer(db, "c1", "Ahmet Yılmaz", "+905551112233", "CLAIMED", "u1")
        customer(db, "m1", "Ahmet Y.", "+905551112233", "CLAIMED", "u1")
        // The shopkeeper's own record as a buyer at the other shop — a customer row is
        // a PERSON, so it carries their name, not their shop's.
        customer(db, "o1", "Ahmet Demirtaş", "+905554443322", "CLAIMED", "u_owner")
        customer(db, "c3", "Mehmet Kaya", "+905554445566", "CLAIMED", "u3")
        customer(db, "c4", "Fatma Şahin", "+905556667788", "UNCLAIMED", null)
        customer(db, "c5", "Hasan Öztürk", "+905558889900", "UNCLAIMED", null)

        // u1 @ Ahmet Bakkal (c1): 50 + 30 - 40 = 40,00
        tx(db, "t1", "u_owner", "c1", 5000, "DEBT", "Ekmek, süt", "2026-07-20T06:15:00Z")
        tx(db, "t2", "u_owner", "c1", 3000, "DEBT", "Peynir", "2026-07-21T07:40:00Z")
        tx(db, "t3", "u_owner", "c1", 4000, "PAYMENT", "Nakit ödeme", "2026-07-22T15:00:00Z")
        // u1 @ Ayşe Market (m1): 120 + 45 - 65 = 100,00
        tx(db, "t4", "u_market", "m1", 12000, "DEBT", "Market alışverişi", "2026-07-18T08:20:00Z")
        tx(db, "t5", "u_market", "m1", 4500, "DEBT", "Deterjan", "2026-07-22T13:05:00Z")
        tx(db, "t6", "u_market", "m1", 6500, "PAYMENT", "Kısmi ödeme", "2026-07-24T10:00:00Z")
        // u_owner @ Ayşe Market (o1): 90 - 30 = 60,00
        tx(db, "t7", "u_market", "o1", 9000, "DEBT", "Kırtasiye", "2026-07-16T07:00:00Z")
        tx(db, "t8", "u_market", "o1", 3000, "PAYMENT", "Nakit ödeme", "2026-07-23T12:30:00Z")
        // u_owner's OWN book:1 c3 = 0, c4 = 25,50, c5 = 210,00
        tx(db, "t9", "u_owner", "c3", 8000, "DEBT", "Kahvaltılık", "2026-07-15T05:30:00Z")
        tx(db, "t10", "u_owner", "c3", 8000, "PAYMENT", "Kart ile ödeme", "2026-07-19T09:00:00Z")
        tx(db, "t11", "u_owner", "c4", 2550, "DEBT", "Çay, şeker", "2026-07-23T05:45:00Z")
        tx(db, "t12", "u_owner", "c5", 31000, "DEBT", "Toplu alışveriş", "2026-07-10T14:30:00Z")
        tx(db, "t13", "u_owner", "c5", 10000, "PAYMENT", "Kısmi ödeme", "2026-07-20T11:10:00Z")

        // One pending approval per demo account, so the Onaylar tab is never empty.
        approval(db, "p1", "u_owner", "Ahmet Bakkal", "u1", "c1", 5000, "DEBT",
            "Ekmek, süt", "2026-07-25T07:05:00Z")
        approval(db, "p2", "u_market", "Ayşe Market", "u_owner", "o1", 7500, "DEBT",
            "Temizlik malzemesi", "2026-07-25T08:20:00Z")
    }

    private fun user(
        db: SupportSQLiteDatabase, id: String, phone: String, name: String,
        isSeller: Boolean, shopName: String?, shopPhone: String?, createdAt: String
    ) = db.insert("users", 0, ContentValues().apply {
        put("userId", id); put("phone", phone); put("displayName", name)
        put("isBuyer", 1); put("isSeller", if (isSeller) 1 else 0)
        putNull("email")
        if (shopName == null) putNull("shopName") else put("shopName", shopName)
        if (shopPhone == null) putNull("shopPhone") else put("shopPhone", shopPhone)
        put("createdAt", createdAt)
    })

    private fun customer(
        db: SupportSQLiteDatabase, id: String, name: String, phone: String,
        claim: String, claimedBy: String?
    ) = db.insert("customers", 0, ContentValues().apply {
        put("customerId", id); put("displayName", name); put("phone", phone)
        put("claimStatus", claim)
        if (claimedBy == null) putNull("claimedByUserId") else put("claimedByUserId", claimedBy)
        put("createdAt", "2026-07-01T06:00:00Z")
    })

    private fun tx(
        db: SupportSQLiteDatabase, id: String, seller: String, customer: String,
        amount: Long, type: String, desc: String, createdAt: String
    ) = db.insert("transactions", 0, ContentValues().apply {
        put("transactionId", id); put("sellerId", seller); put("customerId", customer)
        put("amountMinor", amount); put("type", type); put("description", desc)
        putNull("basketId"); put("settledViaPgw", 0); putNull("receiptNo")
        put("createdAt", createdAt)
    })

    private fun approval(
        db: SupportSQLiteDatabase, id: String, sellerId: String, shopName: String,
        targetUserId: String, customerId: String, amount: Long, type: String,
        desc: String, requestedAt: String
    ) = db.insert("approvals", 0, ContentValues().apply {
        put("approvalId", id)
        // Today's flow is seller-initiated; the direction fields record that explicitly.
        put("initiatorUserId", sellerId); put("initiatorRole", "SELLER")
        put("targetUserId", targetUserId); put("sellerId", sellerId)
        put("shopName", shopName); put("customerId", customerId)
        put("amountMinor", amount); put("type", type); put("description", desc)
        put("channel", "APP_PUSH"); put("status", "PENDING")
        put("requestedAt", requestedAt)
    })
}
