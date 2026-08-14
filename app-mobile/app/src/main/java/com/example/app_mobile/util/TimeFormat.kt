package com.example.app_mobile.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Formats a stored ISO-8601 UTC timestamp for display: "2026-07-20T06:15:00Z" ->
 * "20.07.2026 09:15" in the device's own time zone.
 *
 * Timestamps are stored and sent as ISO-8601 UTC (the wire contract's format, and the
 * one the DAOs sort on); turning that into something a person reads is a presentation
 * concern, so it lives here next to [toTlString].
 *
 * SimpleDateFormat rather than java.time because minSdk is 24 and core library
 * desugaring is deliberately not enabled.
 */
private val TR = Locale.forLanguageTag("tr-TR")

// Built per call: SimpleDateFormat is not thread-safe, and these are cheap next to the
// view binding work around them.
private fun isoParser() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun displayFormat() = SimpleDateFormat("dd.MM.yyyy HH:mm", TR)

/**
 * Returns the timestamp in local display form, or the input unchanged when it cannot be
 * parsed. Falling back to the raw text keeps a malformed value visible in the UI instead
 * of crashing a list row or silently blanking the date.
 */
fun String.toDisplayDateTime(): String =
    runCatching { isoParser().parse(this)?.let { displayFormat().format(it) } }
        .getOrNull() ?: this
