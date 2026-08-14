package com.example.app_pos.sync

import androidx.work.ListenableWorker.Result
import com.example.app_pos.model.PullOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PullWorker's decision table: given a session state and a pull result, what does it tell
 * WorkManager to do?
 *
 * The stakes differ from SyncWorker's. Nothing can be LOST here — a pull only reads — so the
 * load-bearing case is the opposite one: a refusal must not answer `failure`, because that
 * cancels PERIODIC work permanently and one bad response would end every future pull.
 *
 * decide() is called directly rather than through TestListenableWorkerBuilder: the Worker
 * touches nothing on its Context, so a real Android runtime would add Robolectric's cost
 * without testing anything more.
 */
class PullWorkerTest {

    private class PullFake(
        sessionValid: Boolean = true,
        private val outcome: PullOutcome = PullOutcome.Refreshed(0),
        private val failWith: Throwable? = null
    ) : FakeSyncRepository(sessionValid = sessionValid) {

        var pullCallCount = 0
            private set

        override suspend fun refreshApprovals(): PullOutcome {
            pullCallCount++
            failWith?.let { throw it }
            return outcome
        }
    }

    /** No token, no question worth asking — the endpoint would only answer 401. */
    @Test
    fun `signed out does not ask the server`() = runTest {
        val repo = PullFake(sessionValid = false)

        assertEquals(Result.retry(), PullWorker.decide(repo))
        assertEquals("the pull must not run without a token", 0, repo.pullCallCount)
    }

    @Test
    fun `a refreshed inbox succeeds`() = runTest {
        assertEquals(Result.success(), PullWorker.decide(PullFake(outcome = PullOutcome.Refreshed(3))))
    }

    /** Zero rows is a real answer, not a failure: everything was decided elsewhere. */
    @Test
    fun `an empty inbox is still a success`() = runTest {
        assertEquals(Result.success(), PullWorker.decide(PullFake(outcome = PullOutcome.Refreshed(0))))
    }

    @Test
    fun `an unreachable server retries`() = runTest {
        assertEquals(Result.retry(), PullWorker.decide(PullFake(outcome = PullOutcome.Unreachable)))
    }

    /**
     * THE case that matters here. `failure` would cancel the periodic schedule for good, so
     * a single refusal would silently stop the terminal ever pulling again — the next
     * scheduled run is the right recovery, not abandonment.
     */
    @Test
    fun `a refusal never abandons the schedule`() = runTest {
        val result = PullWorker.decide(PullFake(outcome = PullOutcome.Failed("nope")))

        assertEquals(Result.success(), result)
        assertEquals("failure() would cancel all future pulls", false, result == Result.failure())
    }

    @Test
    fun `an unexpected fault retries rather than failing`() = runTest {
        val repo = PullFake(failWith = IllegalStateException("boom"))

        assertEquals(Result.retry(), PullWorker.decide(repo))
    }
}
