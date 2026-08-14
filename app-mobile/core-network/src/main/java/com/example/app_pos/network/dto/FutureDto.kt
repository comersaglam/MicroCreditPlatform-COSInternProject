package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Wire shapes for the contract's `future`-tagged endpoints: they compile and parse, but
 * nothing calls them yet. Same "signature ready, body later" pattern as OtpService and
 * the forward-phase Room entities — the shape is settled now so adopting the feature
 * later is a wiring job, not a redesign.
 */

/** POST /sync/transactions — the server end of the offline outbox (phase 4). */
@JsonClass(generateAdapter = true)
data class SyncBatchDto(
    @param:Json(name = "entries") val entries: List<TransactionCreateDto>
)

/** Which entries the server accepted; each is idempotent by its own transaction id. */
@JsonClass(generateAdapter = true)
data class SyncResultDto(
    @param:Json(name = "accepted") val accepted: List<String> = emptyList()
)

/** POST /transactions/{id}/settle — an approved PAYMENT went through the gateway. */
@JsonClass(generateAdapter = true)
data class SettleRequestDto(
    @param:Json(name = "receipt_no") val receiptNo: String,
    @param:Json(name = "settled_via_pgw") val settledViaPgw: Boolean
)

/** POST /devices — FCM registration, so the backend can nudge "there is news, sync". */
@JsonClass(generateAdapter = true)
data class DeviceCreateDto(
    @param:Json(name = "fcm_token") val fcmToken: String,
    @param:Json(name = "platform") val platform: String
)

/** A micro-credit offer. `apr` is ×100 (1900 = 19%), keeping it an integer like money. */
@JsonClass(generateAdapter = true)
data class CreditOfferDto(
    @param:Json(name = "offer_id") val offerId: String,
    @param:Json(name = "user_id") val userId: String,
    @param:Json(name = "limit_minor") val limitMinor: Long,
    @param:Json(name = "apr") val apr: Int,
    @param:Json(name = "term_days") val termDays: Int,
    @param:Json(name = "status") val status: String
)

/** GET /insights — analytics, gated on KVKK review (phase 2); shape still provisional. */
@JsonClass(generateAdapter = true)
data class InsightsDto(
    @param:Json(name = "risk_scores") val riskScores: List<RiskScoreDto> = emptyList(),
    @param:Json(name = "collection_forecast_minor") val collectionForecastMinor: Long? = null,
    @param:Json(name = "as_of") val asOf: String? = null
)

@JsonClass(generateAdapter = true)
data class RiskScoreDto(
    @param:Json(name = "customer_id") val customerId: String,
    @param:Json(name = "score") val score: Double
)
