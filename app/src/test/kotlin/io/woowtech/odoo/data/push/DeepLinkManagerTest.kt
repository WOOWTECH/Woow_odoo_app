package io.woowtech.odoo.data.push

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [DeepLinkManager] — the account-bound, single-consume, time-boxed pending-link queue
 * that is the core of the cross-tenant deep-link isolation.
 */
class DeepLinkManagerTest {

    private lateinit var manager: DeepLinkManager

    private val accountA = "acc-A"
    private val accountB = "acc-B"
    private val t0 = 1_000_000L

    @BeforeEach
    fun setup() {
        manager = DeepLinkManager()
    }

    @Test
    fun `Given no pending link when consumeFor then returns null`() {
        assertNull(manager.consumeFor(accountA, nowMillis = t0))
    }

    @Test
    fun `Given link for A when consumeFor A then returns url and clears`() {
        manager.setPending(url = "/web#id=42", accountId = accountA, nowMillis = t0)

        assertEquals("/web#id=42", manager.consumeFor(accountA, nowMillis = t0))
        assertNull(manager.consumeFor(accountA, nowMillis = t0))
    }

    @Test
    fun `Given link for B when consumeFor A then returns null and keeps link for B`() {
        // Cross-account isolation: a link bound to B is NEVER handed to A.
        manager.setPending(url = "/web#id=99", accountId = accountB, nowMillis = t0)

        assertNull(manager.consumeFor(accountA, nowMillis = t0))
        // Link is preserved for its real target.
        assertEquals("/web#id=99", manager.consumeFor(accountB, nowMillis = t0))
    }

    @Test
    fun `Given link older than TTL when consumeFor then returns null and clears`() {
        manager.setPending(url = "/web#id=1", accountId = accountA, nowMillis = t0)

        val afterTtl = t0 + DeepLinkManager.TTL_MILLIS + 1
        assertNull(manager.consumeFor(accountA, nowMillis = afterTtl))
        // Cleared even for a later in-window read.
        assertNull(manager.consumeFor(accountA, nowMillis = afterTtl + 1))
    }

    @Test
    fun `Given link within TTL when consumeFor then returns url`() {
        manager.setPending(url = "/web#id=1", accountId = accountA, nowMillis = t0)

        val withinTtl = t0 + DeepLinkManager.TTL_MILLIS - 1
        assertEquals("/web#id=1", manager.consumeFor(accountA, nowMillis = withinTtl))
    }

    @Test
    fun `Given link for B when dropIfNotTarget A then link is dropped`() {
        manager.setPending(url = "/web#id=7", accountId = accountB, nowMillis = t0)

        manager.dropIfNotTarget(accountA)

        assertNull(manager.consumeFor(accountB, nowMillis = t0))
    }

    @Test
    fun `Given link for A when dropIfNotTarget A then link is kept`() {
        manager.setPending(url = "/web#id=7", accountId = accountA, nowMillis = t0)

        manager.dropIfNotTarget(accountA)

        assertEquals("/web#id=7", manager.consumeFor(accountA, nowMillis = t0))
    }

    @Test
    fun `Given null url when setPending then clears`() {
        manager.setPending(url = "/web#id=1", accountId = accountA, nowMillis = t0)
        manager.setPending(url = null, accountId = accountA, nowMillis = t0)

        assertNull(manager.consumeFor(accountA, nowMillis = t0))
    }

    @Test
    fun `Given link overwritten when consumeFor then returns latest`() {
        manager.setPending(url = "/web#id=1", accountId = accountA, nowMillis = t0)
        manager.setPending(url = "/web#id=2", accountId = accountB, nowMillis = t0)

        // Latest tap wins, bound to its own account.
        assertNull(manager.consumeFor(accountA, nowMillis = t0))
        assertEquals("/web#id=2", manager.consumeFor(accountB, nowMillis = t0))
    }

    @Test
    fun `Given link set when observed via flow then emits pending then null on consume`() = runTest {
        manager.pending.test {
            assertNull(awaitItem())

            manager.setPending(url = "/web#id=1", accountId = accountA, nowMillis = t0)
            assertEquals(accountA, awaitItem()?.accountId)

            manager.consumeFor(accountA, nowMillis = t0)
            assertNull(awaitItem())
        }
    }
}
