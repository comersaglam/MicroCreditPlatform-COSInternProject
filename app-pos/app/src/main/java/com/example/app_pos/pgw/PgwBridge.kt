package com.example.app_pos.pgw

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
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
     * already agreed, the other charges a card. The gateway builds its own basket for a
     * payment, so nothing is handed over but the amount.
     */
    fun collectPayment(context: Context, amountMinor: Long): Boolean =
        launch(context, paymentOrderBody(amountMinor))

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

    /** A credit slip, in the gateway's shape. Mirrors backend/app/pgw.py::receipt_order_body. */
    private fun receiptOrderBody(amountMinor: Long): String =
        orderBody(amountMinor, ITEM_TYPE_CREDIT)

    /**
     * A card payment. Item type 1 is the gateway's ordinary payment, as in the integration
     * example — the same envelope as a receipt, with a different item type.
     */
    private fun paymentOrderBody(amountMinor: Long): String = orderBody(amountMinor, itemType = 1)

    private fun orderBody(amountMinor: Long, itemType: Int): String =
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
                        put("type", itemType)
                    }
                )
            )
        }.toString()
}
