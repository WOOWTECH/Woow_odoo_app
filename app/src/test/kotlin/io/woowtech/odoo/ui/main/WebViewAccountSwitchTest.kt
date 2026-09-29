package io.woowtech.odoo.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * pi 0929 recheck P1: [WebViewSwitchLoadGate] (a late page event of the previous account cannot open
 * the new account's load gate) and [isolateCookiesForAccount] (the load runs only after the cookie
 * removal completed and the target session was installed).
 */
class WebViewAccountSwitchTest {

    @Test
    fun `Given no switch happened when a page on the target host finishes then it counts, as before`() {
        val gate = WebViewSwitchLoadGate()
        assertTrue(gate.acceptFinished(onTargetHost = true))
        assertFalse(gate.acceptFinished(onTargetHost = false))
    }

    @Test
    fun `Given a switch whose load was not issued yet when a page finishes then it is ignored`() {
        val gate = WebViewSwitchLoadGate()
        gate.beginSwitch()
        gate.onPageStarted()
        assertFalse(gate.acceptFinished(onTargetHost = true))
    }

    @Test
    fun `Given the switch load was issued but no navigation started after it when a page finishes then it is the previous page and ignored`() {
        val gate = WebViewSwitchLoadGate()
        val switch = gate.beginSwitch()
        gate.onLoadIssued(switch)
        assertFalse(gate.acceptFinished(onTargetHost = true))
    }

    @Test
    fun `Given the switch load started when its page finishes on the target host then it counts once and later pages use the host rule`() {
        val gate = WebViewSwitchLoadGate()
        val switch = gate.beginSwitch()
        gate.onLoadIssued(switch)
        gate.onPageStarted()
        assertFalse(gate.acceptFinished(onTargetHost = false), "a foreign host never counts")
        assertTrue(gate.acceptFinished(onTargetHost = true))
        assertTrue(gate.acceptFinished(onTargetHost = true))
    }

    @Test
    fun `Given a start seen before the load was issued when the switch load is issued then that earlier start does not count`() {
        val gate = WebViewSwitchLoadGate()
        val switch = gate.beginSwitch()
        gate.onPageStarted() // previous account's navigation
        gate.onLoadIssued(switch)
        assertFalse(gate.acceptFinished(onTargetHost = true))
    }

    @Test
    fun `Given a second switch before the first load was issued then the first switch is no longer current`() {
        val gate = WebViewSwitchLoadGate()
        val first = gate.beginSwitch()
        val second = gate.beginSwitch()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        gate.onLoadIssued(first)
        gate.onPageStarted()
        assertFalse(gate.acceptFinished(onTargetHost = true), "a superseded switch's load cannot open the gate")
    }

    @Test
    fun `Given a session to install when isolating then clear completes before the cookie is set, flushed and the page loaded`() {
        val store = FakeStore()
        isolateCookiesForAccount(store, "https://erp.example.invalid", "sid-b") { store.events += "load" }

        assertEquals(listOf("removeAll"), store.events)
        store.completeRemoval()
        assertEquals(listOf("removeAll", "set https://erp.example.invalid session_id=sid-b", "flush", "load"), store.events)
    }

    @Test
    fun `Given nothing to install when isolating then the page loads only after the clear completed`() {
        val store = FakeStore()
        isolateCookiesForAccount(store, "https://erp.example.invalid", null) { store.events += "load" }

        assertEquals(listOf("removeAll"), store.events)
        store.completeRemoval()
        assertEquals(listOf("removeAll", "flush", "load"), store.events)
    }

    private class FakeStore : WebViewCookieStore {
        val events = mutableListOf<String>()
        private var pending: (() -> Unit)? = null
        override fun getCookie(url: String): String? = null
        override fun removeAllCookies(onDone: () -> Unit) { events += "removeAll"; pending = onDone }
        override fun setCookie(url: String, value: String) { events += "set $url ${value.substringBefore(';')}" }
        override fun flush() { events += "flush" }
        fun completeRemoval() { pending?.invoke(); pending = null }
    }
}
