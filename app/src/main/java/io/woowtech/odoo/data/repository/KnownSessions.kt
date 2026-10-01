package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.local.KnownSessionStore
import timber.log.Timber

/**
 * Each account's last known Odoo session, cached in memory and written through to [store] (pi 1001b
 * Android P2: the record must survive an app restart). Same operations the repository used on its former
 * in-memory map. A storage failure never blocks sign-in or logout (pi 1001c P2): a failed read is retried on
 * the next access instead of being cached as a miss, and a failed write is kept and retried on every later
 * operation until it lands. Session ids are never logged.
 *
 * Durability limits, stated plainly (pi 1001d P2): a failed REMOVAL is made durable by [sweep] at the next
 * start (a record whose account no longer exists is deleted). A failed SAVE stays in memory only: if the
 * process dies before a retry lands, that record is lost and a later promotion signs in again instead of
 * reusing it (the unrecorded session then just expires on the server).
 */
internal class KnownSessions(private val store: () -> KnownSessionStore) {
    private val cache = HashMap<String, String>()
    private val loaded = HashSet<String>()
    /** Writes that have not reached [store] yet: account id → session id to save, or null to remove. */
    private val pending = LinkedHashMap<String, String?>()

    @Synchronized
    operator fun get(accountId: String): String? {
        flushPending()
        if (accountId !in loaded) {
            runCatching { store().load(accountId) }
                .onSuccess { stored ->
                    loaded += accountId
                    stored?.takeIf { it.isNotBlank() }?.let { cache[accountId] = it }
                }
                .onFailure { Timber.w("Known-session store read failed; will retry on next access") }
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
        loaded += accountId
        write(accountId, sessionId)
        return previous
    }

    /** Forgets the account's session and returns it. */
    @Synchronized
    fun remove(accountId: String): String? {
        val previous = get(accountId)
        cache.remove(accountId)
        loaded += accountId
        write(accountId, null)
        return previous
    }

    /** Forgets the account's session only while it is still [sessionId]. */
    @Synchronized
    fun remove(accountId: String, sessionId: String): Boolean {
        if (get(accountId) != sessionId) return false
        remove(accountId)
        return true
    }

    /** Deletes every stored record whose account is not in [liveAccounts] (startup cleanup, pi 1001d P2). */
    @Synchronized
    fun sweep(liveAccounts: Set<String>) {
        flushPending()
        val stored = runCatching { store().accountIds() }
            .onFailure { Timber.w("Known-session store listing failed; cleanup retried at next start") }
            .getOrNull() ?: return
        (stored - liveAccounts).forEach { orphan ->
            cache.remove(orphan)
            loaded += orphan
            write(orphan, null)
        }
    }

    private fun write(accountId: String, sessionId: String?) {
        pending.remove(accountId)
        if (!tryWrite(accountId, sessionId)) pending[accountId] = sessionId
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (accountId, sessionId) = iterator.next()
            if (tryWrite(accountId, sessionId)) iterator.remove()
        }
    }

    private fun tryWrite(accountId: String, sessionId: String?): Boolean =
        runCatching {
            if (sessionId == null) store().remove(accountId) else store().save(accountId, sessionId)
        }.onFailure { Timber.w("Known-session store write failed; will retry") }.isSuccess
}
