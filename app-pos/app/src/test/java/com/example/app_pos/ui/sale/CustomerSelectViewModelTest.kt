package com.example.app_pos.ui.sale

import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.model.User
import com.example.app_pos.sync.FakeSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the till shows while a credit entry is waiting for a customer.
 *
 * The rule under test was REVERSED deliberately: a blank query used to return nothing, on
 * the reasoning that a full list invites tapping the wrong row. Arriving from a mock-pos
 * handoff, that left the merchant with a blank screen and no choice but to type a name from
 * memory. Seeing the book is what actually prevents picking the wrong person, so these
 * tests pin the new behaviour — otherwise the old rule reads like the obvious one and comes
 * back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerSelectViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun customer(name: String) = Customer(
        customerId = name.lowercase(),
        displayName = name,
        phone = "+90555000000",
        claimStatus = ClaimStatus.UNCLAIMED,
        claimedByUserId = null,
        createdBySellerId = null,
        balanceMinor = 0L
    )

    /** A repository holding one seller and a fixed book. */
    private class FakeBook(private val customers: List<Customer>) : FakeSyncRepository() {
        override fun observeCurrentUser(): Flow<User?> = flowOf(
            User(
                userId = "u_owner",
                phone = "+905554443322",
                displayName = "Ahmet",
                isBuyer = true,
                isSeller = true,
                email = null,
                sellerInfo = null,
                createdAt = "2026-07-01T00:00:00Z"
            )
        )

        override fun observeCustomers(sellerId: String): Flow<List<Customer>> = flowOf(customers)
    }

    /**
     * Reads [CustomerSelectViewModel.matches] after the flow has actually produced a value.
     *
     * `matches` is a stateIn(WhileSubscribed) StateFlow, so it sits on its initial empty
     * value until something collects it AND the dispatcher runs the upstream. Reading
     * `.value` (or `first()`) without both would report "empty" for every case and the
     * tests would pass no matter what the filter does.
     */
    private suspend fun CustomerSelectViewModel.names(): List<String> {
        val collected = matches.stateIn(CoroutineScope(dispatcher + Job()))
        dispatcher.scheduler.advanceUntilIdle()
        return collected.value.map { it.displayName }
    }

    /** The behaviour the merchant asked for: arrive from the handoff and SEE the book. */
    @Test
    fun `a blank query lists the whole book`() = runTest(dispatcher) {
        val vm = CustomerSelectViewModel(FakeBook(listOf(customer("Ahmet"), customer("Fatma"))))

        assertEquals(listOf("Ahmet", "Fatma"), vm.names())
    }

    @Test
    fun `typing narrows the list`() = runTest(dispatcher) {
        val vm = CustomerSelectViewModel(FakeBook(listOf(customer("Ahmet"), customer("Fatma"))))

        vm.onSearchChanged("fat")

        assertEquals(listOf("Fatma"), vm.names())
    }

    /** An empty list now means an empty BOOK, which is what the screen's hint must say. */
    @Test
    fun `an empty book lists nothing`() = runTest(dispatcher) {
        val vm = CustomerSelectViewModel(FakeBook(emptyList()))

        assertEquals(emptyList<String>(), vm.names())
    }
}
