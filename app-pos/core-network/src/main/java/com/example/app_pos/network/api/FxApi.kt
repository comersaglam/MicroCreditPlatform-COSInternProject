package com.example.app_pos.network.api

import com.example.app_pos.network.dto.FxSnapshotDto
import retrofit2.http.GET
import retrofit2.http.Query

/** What a lira was worth on a given day. One read-only endpoint, scoped to nobody. */
interface FxApi {

    /**
     * The rates in force on [asOf] (yyyy-MM-dd), or today's when it is omitted.
     *
     * 404 `rate_not_found` when the series does not reach the date at all. That is a real
     * answer, not a failure: an unknown rate has to be distinguishable from a rate of zero,
     * and a client that got zeros back would divide by them.
     */
    @GET("fx-rates")
    suspend fun rate(@Query("as_of") asOf: String? = null): FxSnapshotDto
}
