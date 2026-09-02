package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * What a lira was worth on one day.
 *
 * Reference data, not anybody's records: the same series answers for every user and no
 * client can write to it. Nothing on a device needs this to compute a balance — indexation
 * arrives as ledger rows already denominated in lira, precisely so the phones never have to
 * carry a rate table. It is here for the screens that SHOW value over time.
 *
 * [asOf] is the date of the row the server actually used, which is not always the date
 * asked for: it falls back to the most recent earlier reading, so a Sunday answers with
 * Friday's rates. Reporting back the requested day would claim a precision the series does
 * not have.
 *
 * snake_case on the wire, like every other DTO in this module.
 */
@JsonClass(generateAdapter = true)
data class FxSnapshotDto(
    @param:Json(name = "as_of") val asOf: String,
    @param:Json(name = "usd_minor") val usdMinor: Long,
    @param:Json(name = "eur_minor") val eurMinor: Long,
    @param:Json(name = "gold_minor") val goldMinor: Long
)
