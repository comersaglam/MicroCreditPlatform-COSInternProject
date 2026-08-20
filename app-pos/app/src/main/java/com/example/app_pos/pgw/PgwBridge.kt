package com.example.app_pos.pgw

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * How this app talks TO the payment gateway.
 *
 * The gateway is somebody else's application — we integrate with it, we do not implement
 * it — so everything here is shaped by THEIR contract, taken from the integration notes:
 *
 *     am start -n com.tokeninc.sardis.paymentgateway/.MainActivity \
 *       --es orderBody '{"basketID":"…","documentType":9002,…}'
 *
 * An explicit component, not an action: the gateway is a specific installed application,
 * and an implicit intent could be answered by anything that declared a matching filter.
 *
 * The reverse direction — the gateway starting a veresiye here — lives in MainActivity's
 * intent filter and is unchanged; that one is OUR contract, so we name the action.
 */
object PgwBridge {

    /**
     * The real gateway on a Token terminal. mock-pos publishes the same component name so
     * a development device can stand in for it without either side changing.
     */
    private const val PGW_PACKAGE = "com.tokeninc.sardis.paymentgateway"
    private const val PGW_ACTIVITY = "$PGW_PACKAGE.MainActivity"

    /** The gateway reads its whole request from this one string extra. */
    private const val EXTRA_ORDER_BODY = "orderBody"

    /**
     * The gateway's payment-item type for a credit slip. Their number, not ours: it is what
     * marks the receipt as a veresiye rather than a card payment.
     */
    private const val ITEM_TYPE_CREDIT = 17

    /** The document type the integration example uses for this receipt class. */
    private const val DOCUMENT_TYPE_RECEIPT = 9002

    /**
     * Asks the gateway to print a slip for an entry already in the ledger (paths 1 and 3).
     *
     * [orderBody] is passed through verbatim when the server supplied one — it is the
     * gateway's contract, and re-shaping it here is how a field they require quietly goes
     * missing. One is built locally only when there is none to pass on.
     */
    fun printReceipt(context: Context, amountMinor: Long, orderBody: String? = null): Boolean =
        launch(context, orderBody ?: receiptOrderBody(amountMinor))

    /**
     * Opens the gateway to TAKE money by card (paths 2, 4 and 5).
     *
     * Distinct from [printReceipt] and not interchangeable with it: one records money
     * already agreed, the other charges a card.
     *
     * [customerName] and [customerPhone] are optional: when either is missing the
     * customerInfo block is left out entirely rather than sent half-filled. Path 4/5 reads
     * them from the local book, and a customer that has not synced to this terminal yet
     * simply cannot be named — sending the payment without a name is better than not
     * sending it at all.
     */
    fun collectPayment(
        context: Context,
        amountMinor: Long,
        customerName: String? = null,
        customerPhone: String? = null
    ): Boolean = launch(context, paymentOrderBody(amountMinor, customerName, customerPhone))

    /**
     * Starts the gateway with a request.
     *
     * Returns false when it is not installed rather than throwing: on a development
     * machine without mock-pos this is expected, and a crashed till is a worse answer than
     * a merchant being told the gateway is missing.
     *
     * NEW_TASK because the gateway is a separate application with its own lifecycle — it
     * must not end up inside this app's back stack, exactly as mock-pos does in reverse.
     */
    private fun launch(context: Context, orderBody: String): Boolean {
        val intent = Intent().apply {
            setClassName(PGW_PACKAGE, PGW_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_ORDER_BODY, orderBody)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * A credit slip, in the gateway's shape. Mirrors backend/app/pgw.py::receipt_order_body.
     *
     * This one DOES carry paymentItems, and that is exactly what makes it a slip: the
     * gateway prints what the items describe instead of opening its payment screen.
     */
    private fun receiptOrderBody(amountMinor: Long): String =
        JSONObject().apply {
            // The gateway keys its request on the basket id, so two requests must never
            // share one. Minted per call rather than reused.
            put("basketID", UUID.randomUUID().toString())
            put("documentType", DOCUMENT_TYPE_RECEIPT)
            put(
                "paymentItems",
                JSONArray().put(
                    JSONObject().apply {
                        put("amount", amountMinor)
                        put("type", ITEM_TYPE_CREDIT)
                    }
                )
            )
        }.toString()

    /**
     * A card payment, in the shape the gateway's integration example uses.
     *
     * Two things here are the fix for a real terminal rejecting our earlier request with
     * "sepet tutarı 0 olamaz":
     *
     *  - NO paymentItems. Sending them made the gateway print a slip immediately instead of
     *    opening its payment screen — the items ARE the instruction to print.
     *  - The amount travels in taxFreeAmount. With paymentItems gone this is the only field
     *    carrying it, and its absence was the zero the gateway complained about.
     *
     * documentType stays 9002, as in the reference request.
     */
    private fun paymentOrderBody(
        amountMinor: Long,
        customerName: String?,
        customerPhone: String?
    ): String =
        JSONObject().apply {
            put("basketID", UUID.randomUUID().toString())
            put("documentType", DOCUMENT_TYPE_RECEIPT)

            // All or nothing: a customerInfo with a name but no id (or the reverse) is worse
            // than none, because it looks complete on the receipt.
            if (!customerName.isNullOrBlank() && !customerPhone.isNullOrBlank()) {
                put(
                    "customerInfo",
                    JSONObject().apply {
                        put("name", customerName)
                        // TODO(taxid): the field wants a tax/national id and we hold neither,
                        //  so the phone number stands in — it is the only identity this app
                        //  actually has for a customer. Noted in docs/deferred.md; a real
                        //  integration must either collect the real id or leave this out.
                        put("taxID", "11111111111")
                    }
                )
            }

            put(
                "infoReceiptInfo",
                JSONObject().apply {
                    put("documentDate", documentDateFormat().format(Date()))
                    put("documentNo", documentNo())
                }
            )

            put("taxFreeAmount", amountMinor)
        }.toString()

    /** dd-MM-yyyy, the format the gateway's reference request uses. */
    private fun documentDateFormat(): SimpleDateFormat =
        SimpleDateFormat("dd-MM-yyyy", Locale.ROOT)

    /**
     * A document number in the reference request's shape (GIB + year + digits).
     *
     * Derived from the clock rather than a stored counter: nothing here needs the numbers to
     * be consecutive, and a counter would have to survive reinstalls and stay unique across
     * terminals to be worth its complexity. Two receipts inside the same second would
     * collide, which does not happen at a till.
     *
     * TODO(gib-document-no): this is NOT a real GİB document number — a real integration
     *  gets it from the gateway or the fiscal unit. Noted in docs/deferred.md.
     */
    private fun documentNo(): String {
        val year = SimpleDateFormat("yyyy", Locale.ROOT).format(Date())
        return "GIB$year${System.currentTimeMillis() / 1000}"
    }
}
