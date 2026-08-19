package com.example.app_pos.network.api

import com.example.app_pos.network.dto.PgwJobCreateDto
import com.example.app_pos.network.dto.PgwJobDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The terminal's inbox for payment-gateway work.
 *
 * Exists because the server cannot reach a POS — it is behind NAT with no push channel —
 * yet paths 3, 4 and 5 all start on a phone and END at the gateway. The server leaves the
 * work here and the terminal comes and asks.
 */
interface PgwJobApi {

    /** What is still waiting for this terminal, oldest first. */
    @GET("pgw-jobs")
    suspend fun pending(): List<PgwJobDto>

    /**
     * Says the job was handed to the gateway.
     *
     * Idempotent server-side, and that matters: the terminal fires the intent FIRST and
     * acks second, so a lost response leaves it holding work it really did deliver. The
     * only safe move then is to retry the ack — if that were an error the job would stay
     * pending and the receipt would print on every poll.
     */
    @POST("pgw-jobs/{id}/ack")
    suspend fun ack(@Path("id") jobId: String): PgwJobDto

    /** A seller queues work for their own till (path 4: collecting payment from a phone). */
    @POST("pgw-jobs")
    suspend fun create(@Body body: PgwJobCreateDto): PgwJobDto
}
