package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.local.KnownSessionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * pi 1001c P2: a failed read must not be cached as a permanent miss, and a failed write must be retried —
 * the orphan prevention relies on the record actually reaching the encrypted store.
 */
class KnownSessionsTest {
    private class FlakyStore : KnownSessionStore {
        val values = HashMap<String, String>()
        var failLoads = 0
        var failWrites = 0
        override fun load(accountId: String): String? {
            if (failLoads > 0) { failLoads--; throw IllegalStateException("keystore unavailable") }
            return values[accountId]
        }
        override fun save(accountId: String, sessionId: String) {
            if (failWrites > 0) { failWrites--; throw IllegalStateException("write not committed") }
            values[accountId] = sessionId
        }
        override fun remove(accountId: String) {
            if (failWrites > 0) { failWrites--; throw IllegalStateException("write not committed") }
            values.remove(accountId)
        }
    }

    @Test
    fun `Given the first read fails then a later read still loads the stored session`() {
        val store = FlakyStore().apply { values["a"] = "a-sid"; failLoads = 1 }
        val sessions = KnownSessions { store }

        assertNull(sessions["a"])
        assertEquals("a-sid", sessions["a"], "a failed read is not a permanent miss")
    }

    @Test
    fun `Given a save fails then the next operation retries it until it is stored`() {
        val store = FlakyStore().apply { failWrites = 1 }
        val sessions = KnownSessions { store }

        sessions["a"] = "a-sid"
        assertNull(store.values["a"])
        sessions["b"]

        assertEquals("a-sid", store.values["a"], "the failed save is retried")
    }

    @Test
    fun `Given a remove fails then the next operation retries it and the cache keeps the removal`() {
        val store = FlakyStore().apply { values["a"] = "a-sid" }
        val sessions = KnownSessions { store }
        assertEquals("a-sid", sessions["a"])
        store.failWrites = 1

        sessions.remove("a")
        assertNull(sessions["a"])
        sessions["b"]

        assertNull(store.values["a"], "the failed remove is retried")
    }
}
