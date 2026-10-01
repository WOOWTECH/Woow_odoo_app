package io.woowtech.odoo.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spght.encryptedprefs.EncryptedSharedPreferences
import dev.spght.encryptedprefs.MasterKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable record of each account's last known Odoo session id (pi 1001b Android P2), so a promotion after
 * an app restart can still reuse a valid session or revoke it instead of orphaning it on the server.
 * Values are session secrets: only an encrypted store may hold them and they are never logged.
 */
interface KnownSessionStore {
    /** Throws when the store cannot be read (the caller retries later). */
    fun load(accountId: String): String?
    /** Throws when the write did not reach the store (the caller retries it). */
    fun save(accountId: String, sessionId: String)
    fun remove(accountId: String)

    /** Process-only store (unit tests, or before DI wires the encrypted one). */
    class InMemory : KnownSessionStore {
        private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun load(accountId: String): String? = values[accountId]
        override fun save(accountId: String, sessionId: String) { values[accountId] = sessionId }
        override fun remove(accountId: String) { values.remove(accountId) }
    }
}

/** Keystore-backed store, same scheme and corruption recovery as [EncryptedPrefs], in its own file. */
@Singleton
class EncryptedKnownSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : KnownSessionStore {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        try {
            create(masterKey)
        } catch (e: Exception) {
            // Keystore corruption — losing these only means a later promotion signs in again.
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
            java.io.File(context.filesDir.parent, "shared_prefs/$FILE.xml").takeIf { it.exists() }?.delete()
            create(masterKey)
        }
    }

    private fun create(masterKey: MasterKey) = EncryptedSharedPreferences.create(
        context, FILE, masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(accountId: String): String? = prefs.getString(key(accountId), null)
    // pi 1001c P2: synchronous commit — orphan prevention relies on the record being on disk; a failure throws
    // so [io.woowtech.odoo.data.repository.KnownSessions] keeps and retries it.
    override fun save(accountId: String, sessionId: String) {
        check(prefs.edit().putString(key(accountId), sessionId).commit()) { "known-session write not committed" }
    }
    override fun remove(accountId: String) {
        check(prefs.edit().remove(key(accountId)).commit()) { "known-session removal not committed" }
    }

    private fun key(accountId: String) = "sid_$accountId"

    private companion object {
        const val FILE = "known_sessions"
    }
}
