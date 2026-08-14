package com.example.app_pos.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins down the retry classification, because the outbox drains on it: a wrong answer
 * here means either a queue that never empties or one that replays a rejected write
 * forever, and neither is visible until real data is at stake.
 */
class ApiResultTest {

    @Test
    fun `no answer from the server is retryable`() {
        assertTrue(ApiResult.NetworkError(IOException("offline")).isRetryable())
    }

    @Test
    fun `a client refusal is not retryable`() {
        // 409 = same idempotency key, different body. Replaying cannot fix that.
        val conflict = ApiResult.ApiError(409, "conflict", "Duplicate key")
        assertFalse(conflict.isRetryable())
        assertTrue(conflict.isClientError)
    }

    @Test
    fun `a server failure is retryable`() {
        val boom = ApiResult.ApiError(503, "unavailable", "Try later")
        assertTrue(boom.isRetryable())
        assertTrue(boom.isServerError)
    }

    @Test
    fun `an unusable answer is not retryable`() {
        // Malformed JSON will parse the same way next time.
        assertFalse(ApiResult.UnexpectedError(IllegalStateException("bad json")).isRetryable())
    }

    @Test
    fun `success carries the value and is never retried`() {
        val result = ApiResult.Success("ok")
        assertEquals("ok", result.getOrNull())
        assertFalse(result.isRetryable())
    }

    @Test
    fun `getOrNull is null on failure`() {
        assertNull(ApiResult.NetworkError(IOException()).getOrNull())
    }
}
