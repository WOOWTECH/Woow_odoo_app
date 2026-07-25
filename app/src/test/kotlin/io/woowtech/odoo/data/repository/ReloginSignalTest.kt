package io.woowtech.odoo.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for [ReloginSignal] — the minimal re-login signal surfaced when a background session re-auth
 * fails unrecoverably (WI-3, guardrail 3).
 */
class ReloginSignalTest {

    @Test
    fun `Given fresh signal when read then no request is pending`() {
        val signal = ReloginSignal()

        assertNull(signal.pending.value)
    }

    @Test
    fun `Given request when emitted then pending carries account and reason`() {
        val signal = ReloginSignal()

        signal.request(accountId = "acc1", reason = ReloginReason.INVALID_CREDENTIALS)

        val pending = signal.pending.value
        assertEquals("acc1", pending?.accountId)
        assertEquals(ReloginReason.INVALID_CREDENTIALS, pending?.reason)
    }

    @Test
    fun `Given pending request when cleared then pending is null again`() {
        val signal = ReloginSignal()
        signal.request(accountId = "acc1", reason = ReloginReason.REAUTH_CIRCUIT_OPEN)

        signal.clear()

        assertNull(signal.pending.value)
    }

    @Test
    fun `Given repeated identical requests when emitted then a single request stays pending`() {
        val signal = ReloginSignal()

        signal.request(accountId = "acc1", reason = ReloginReason.INVALID_CREDENTIALS)
        signal.request(accountId = "acc1", reason = ReloginReason.INVALID_CREDENTIALS)

        assertEquals(
            ReloginRequest(accountId = "acc1", reason = ReloginReason.INVALID_CREDENTIALS),
            signal.pending.value,
        )
    }
}
