package com.example.app_pos.network.api

import com.example.app_pos.network.dto.PgwJobCreateDto
import com.example.app_pos.network.dto.PgwJobDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Sending work to a POS terminal.
 *
 * Only the CREATE half of app-pos's version of this interface, and that asymmetry is the
 * point: a phone can ASK a till to collect a payment, but it can never fetch or ack the
 * queue — that work is the terminal's, and this app has no gateway to hand it to.
 */
interface PgwJobApi {

    /** Queues a payment for the signed-in seller's own till (path 4). */
    @POST("pgw-jobs")
    suspend fun create(@Body body: PgwJobCreateDto): PgwJobDto
}
