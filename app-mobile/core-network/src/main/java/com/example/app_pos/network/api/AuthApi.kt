package com.example.app_pos.network.api

import com.example.app_pos.network.dto.OtpRequestDto
import com.example.app_pos.network.dto.OtpRequestResultDto
import com.example.app_pos.network.dto.OtpVerifyDto
import com.example.app_pos.network.dto.RefreshDto
import com.example.app_pos.network.dto.SessionDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Sign-in and token lifecycle.
 *
 * This interface is built on the TOKEN-FREE OkHttp client, and that separation is
 * structural rather than stylistic: if refreshing went through the authenticated client,
 * a 401 on the refresh call would trigger another refresh, and so on. Keeping the auth
 * endpoints on their own client makes that loop impossible to write by accident.
 *
 * Methods return the DTO directly rather than Response<T>, so a non-2xx throws and is
 * classified once in apiCall.
 */
interface AuthApi {

    @POST("auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequestDto): OtpRequestResultDto

    /**
     * Verifying does NOT auto-register: an unknown phone answers 404, and the caller
     * registers through POST /users as a separate step. Keeping verify pure is what
     * allows a different sign-up flow later without touching sign-in.
     */
    @POST("auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyDto): SessionDto

    /** Trades a refresh token for a fresh session, so the shopkeeper never re-enters one. */
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshDto): SessionDto

    @POST("auth/logout")
    suspend fun logout()
}
