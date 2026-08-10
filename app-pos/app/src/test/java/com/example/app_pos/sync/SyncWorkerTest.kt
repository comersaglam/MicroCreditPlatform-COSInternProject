package com.example.app_pos.sync

import androidx.work.ListenableWorker.Result
import com.example.app_pos.model.SyncOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * SyncWorker's decision table: given a session state and a drain result, what does it tell
 * WorkManager to do?
 *
 * These four answers are load-bearing. `success` ends the attempt; `retry` schedules another
 * with backoff; `failure` would abandon the work permanently — and getting the first case
 * wrong (running while signed out) silently DELETES unsent veresiye records, because the
 * server's 401 is a 4xx and the drain treats 4xx as "never going to succeed".
 *
 * doWork() is called directly rather than through TestListenableWorkerBuilder: the Worker
 * touches nothing on its Context, so a real Android runtime would add Robolectric's cost
 * without testing anything more.
 */
class SyncWorkerTest {

    /**
     * The guard that protects unsent writes. Not merely "returns retry" — the drain must not
     * be reached AT ALL, which is what syncCallCount proves.
     */
    @Test
    fun `signed out does not touch the queue`() = runTest {
        val repo = FakeSyncRepository(sessionValid = false)

        val result = SyncWorker.decide(repo)

        assertEquals(Result.retry(), result)
        assertEquals("the drain must not run without a token", 0, repo.syncCallCount)
    }

    @Test
    fun `an empty queue succeeds`() = runTest {
        val repo = FakeSyncRepository(outcome = SyncOutcome())

        assertEquals(Result.success(), SyncWorker.decide(repo))
    }

    @Test
    fun `everything delivered succeeds`() = runTest {
        val repo = FakeSyncRepository(outcome = SyncOutcome(sent = 3))

        assertEquals(Result.success(), SyncWorker.decide(repo))
        assertEquals(1, repo.syncCallCount)
    }

    /** Offline or a 5xx: entries are still queued, so ask WorkManager to come back. */
    @Test
    fun `anything left queued asks for a retry`() = runTest {
        val repo = FakeSyncRepository(outcome = SyncOutcome(sent = 1, retryable = 2))

        assertEquals(Result.retry(), SyncWorker.decide(repo))
    }

    /**
     * Dropped entries are resolved, not pending — the server refused them and the drain
     * removed them. There is nothing left to come back for.
     */
    @Test
    fun `dropped entries alone do not force a retry`() = runTest {
        val repo = FakeSyncRepository(outcome = SyncOutcome(sent = 1, dropped = 1))

        assertEquals(Result.success(), SyncWorker.decide(repo))
    }

    /**
     * Never `failure()`: that abandons the work for good and the queue would stay stuck
     * forever. An unexpected fault means "try again later", like any other interruption.
     */
    @Test
    fun `an unexpected error retries rather than failing`() = runTest {
        val repo = FakeSyncRepository(failWith = IllegalStateException("boom"))

        assertEquals(Result.retry(), SyncWorker.decide(repo))
    }

    @Test
    fun `an IO error retries`() = runTest {
        val repo = FakeSyncRepository(failWith = IOException("socket closed"))

        assertEquals(Result.retry(), SyncWorker.decide(repo))
    }
}
