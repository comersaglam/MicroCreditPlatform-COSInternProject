package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Wire shapes for the auth endpoints, mirroring shared-contracts/openapi.yaml.
 *
 * Every DTO in this package ends in `Dto` so mapper files can import both the wire type
 * and the same-named domain model without a collision. Enum-valued fields are plain
 * Strings here on purpose — see EnumMapping in the mapper package for why.
 */

/** POST /auth/otp/request */
@JsonClass(generateAdapter = true)
data class OtpRequestDto(
    @param:Json(name = "phone") val phone: String
)

/** 202 from POST /auth/otp/request. `channel` is APP_PUSH or SMS_OTP. */
@JsonClass(generateAdapter = true)
data class OtpRequestResultDto(
    @param:Json(name = "sent") val sent: Boolean = false,
    @param:Json(name = "channel") val channel: String? = null
)

/** POST /auth/otp/verify */
@JsonClass(generateAdapter = true)
data class OtpVerifyDto(
    @param:Json(name = "phone") val phone: String,
    @param:Json(name = "code") val code: String
)

/** POST /auth/refresh — trades a refresh token for a fresh session. */
@JsonClass(generateAdapter = true)
data class RefreshDto(
    @param:Json(name = "refresh_token") val refreshToken: String
)

/**
 * A signed-in session: the access token plus the user it belongs to.
 *
 * `refreshToken` is nullable because a server that does not issue one still satisfies
 * the contract; the authenticator then simply cannot refresh and the app falls back to
 * the login gate.
 */
@JsonClass(generateAdapter = true)
data class SessionDto(
    @param:Json(name = "token") val token: String,
    @param:Json(name = "refresh_token") val refreshToken: String? = null,
    @param:Json(name = "expires_at") val expiresAt: String,
    @param:Json(name = "user") val user: UserDto
)
