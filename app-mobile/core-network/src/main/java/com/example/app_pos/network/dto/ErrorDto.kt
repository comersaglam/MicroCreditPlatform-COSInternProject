package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The shared error envelope: `{ "error": { "code": ..., "message": ... } }`.
 *
 * Parsing this is best-effort. A failure between the app and the backend (a proxy 502, a
 * captive portal) answers with HTML, not this shape, so callers must tolerate the parse
 * coming back empty rather than assuming every non-2xx carries an envelope.
 */
@JsonClass(generateAdapter = true)
data class ErrorEnvelopeDto(
    @param:Json(name = "error") val error: ErrorBodyDto
)

@JsonClass(generateAdapter = true)
data class ErrorBodyDto(
    @param:Json(name = "code") val code: String,
    @param:Json(name = "message") val message: String
)
