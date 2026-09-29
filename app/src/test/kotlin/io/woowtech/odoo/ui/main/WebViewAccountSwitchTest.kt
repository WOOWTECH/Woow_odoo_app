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

    // --- pi 0929 recheck-2: gated continuations and serialized cookie jobs ---------------------------

    @Test
    fun `Given the switch was superseded before the removal completed then nothing is written, flushed or loaded`() {
        val store = FakeStore()
        var current = true
        var settled = 0
        isolateCookiesForAccount(store, "https://erp.example.invalid", "sid-b", isCurrent = { current }, onSettled = { settled++ }) {
            store.events += "load"
        }
        current = false
        store.completeRemoval()

        assertEquals(listOf("removeAll"), store.events)
        assertEquals(1, settled, "a superseded job still settles so the next one can start")
    }

    @Test
    fun `Given the switch is superseded after the cookie was written then it neither flushes nor loads`() {
        val store = FakeStore()
        var current = true
        store.onSet = { current = false }
        isolateCookiesForAccount(store, "https://erp.example.invalid", "sid-b", isCurrent = { current }) {
            store.events += "load"
        }
        store.completeRemoval()

        assertEquals(listOf("removeAll", "set https://erp.example.invalid session_id=sid-b"), store.events)
    }

    @Test
    fun `Given a job is running when another is queued then it starts only after the first settled`() {
        val sequencer = WebViewCookieSequencer()
        val events = mutableListOf<String>()
        var finishFirst: (() -> Unit)? = null
        sequencer.enqueue({ true }) { done -> events += "first"; finishFirst = done }
        sequencer.enqueue({ true }) { done -> events += "second"; done() }

        assertEquals(listOf("first"), events)
        finishFirst!!()
        assertEquals(listOf("first", "second"), events)
    }

    @Test
    fun `Given a queued job was superseded before it started then it never runs`() {
        val sequencer = WebViewCookieSequencer()
        val events = mutableListOf<String>()
        var finishFirst: (() -> Unit)? = null
        var bCurrent = true
        sequencer.enqueue({ true }) { done -> events += "a"; finishFirst = done }
        sequencer.enqueue({ bCurrent }) { done -> events += "b"; done() }
        sequencer.enqueue({ true }) { done -> events += "c"; done() }
        bCurrent = false

        finishFirst!!()

        assertEquals(listOf("a", "c"), events)
    }

    @Test
    fun `Given jobs that settle synchronously then every queued job runs in order`() {
        val sequencer = WebViewCookieSequencer()
        val events = mutableListOf<String>()
        repeat(3) { i -> sequencer.enqueue({ true }) { done -> events += "job$i"; done() } }
        assertEquals(listOf("job0", "job1", "job2"), events)
    }

    @Test
    fun `Given a job calls done twice then the next job still runs only once`() {
        val sequencer = WebViewCookieSequencer()
        var runs = 0
        var finishFirst: (() -> Unit)? = null
        sequencer.enqueue({ true }) { done -> finishFirst = done }
        sequencer.enqueue({ true }) { _ -> runs++ }
        finishFirst!!()
        finishFirst!!()
        assertEquals(1, runs)
    }

    private class FakeStore : WebViewCookieStore {
        val events = mutableListOf<String>()
        private var pending: (() -> Unit)? = null
        override fun getCookie(url: String): String? = null
        override fun removeAllCookies(onDone: () -> Unit) { events += "removeAll"; pending = onDone }
        var onSet: () -> Unit = {}
        override fun setCookie(url: String, value: String) { events += "set $url ${value.substringBefore(';')}"; onSet() }
        override fun flush() { events += "flush" }
        fun completeRemoval() { pending?.invoke(); pending = null }
    }
}
