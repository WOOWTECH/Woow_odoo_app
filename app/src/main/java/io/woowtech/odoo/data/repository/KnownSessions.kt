package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.local.KnownSessionStore

/**
 * Each account's last known Odoo session, cached in memory and written through to [store] (pi 1001b
 * Android P2: the record must survive an app restart). Same operations the repository used on its former
 * in-memory map. A storage failure degrades to the in-memory behaviour and never blocks sign-in or logout.
 */
internal class KnownSessions(private val store: () -> KnownSessionStore) {
    private val cache = HashMap<String, String>()
    private val loaded = HashSet<String>()

    @Synchronized
    operator fun get(accountId: String): String? {
        if (loaded.add(accountId)) {
            runCatching { store().load(accountId) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { cache[accountId] = it }
        }
        return cache[accountId]
    }

    @Synchronized
    operator fun set(accountId: String, sessionId: String) {
        put(accountId, sessionId)
    }

    /** Records [sessionId] and returns the session it replaced. */
    @Synchronized
    fun put(accountId: String, sessionId: String): String? {
        val previous = get(accountId)
        cache[accountId] = sessionId
        runCatching { store().save(accountId, sessionId) }
        return previous
    }

    /** Forgets the account's session and returns it. */
    @Synchronized
    fun remove(accountId: String): String? {
        val previous = get(accountId)
        cache.remove(accountId)
        runCatching { store().remove(accountId) }
        return previous
    }

    /** Forgets the account's session only while it is still [sessionId]. */
    @Synchronized
    fun remove(accountId: String, sessionId: String): Boolean {
        if (get(accountId) != sessionId) return false
        remove(accountId)
        return true
    }
}
