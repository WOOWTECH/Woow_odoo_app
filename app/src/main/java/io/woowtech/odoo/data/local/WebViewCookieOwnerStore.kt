package io.woowtech.odoo.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which account the process-global WebView cookies belong to, so a cold start can keep a
 * still-valid Odoo session instead of wiping it (see `WebViewCookiePlanner`).
 *
 * Only the local account id (a random UUID, not a secret) is persisted, in `noBackupFilesDir` so it
 * never travels with Auto Backup. The session itself stays where Chromium keeps it. The last session
 * id installed from the native jar is kept in memory only: the native jar is in-memory too, so it
 * can only be compared within the same process.
 */
@Singleton
class WebViewCookieOwnerStore internal constructor(private val file: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, FILE_NAME))

    @Volatile
    var lastInjectedSessionId: String? = null
        private set

    /** The account the WebView cookies were last installed for, or null when unknown / cleared. */
    fun ownerAccountId(): String? = try {
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        Timber.w(e, "WebView cookie owner unreadable — treating cookies as foreign")
        null
    }

    /** Records that [accountId]'s native session [sessionId] was just installed into the WebView. */
    fun recordInstalled(accountId: String, sessionId: String) {
        lastInjectedSessionId = sessionId
        write(accountId)
    }

    /** Records that the WebView cookies were cleared and belong to nobody. */
    fun recordCleared() {
        write(null)
    }

    private fun write(accountId: String?) {
        try {
            if (accountId == null) file.delete() else file.writeText(accountId)
        } catch (e: IOException) {
            // Fail closed: an unknown owner makes the next start clear the cookies again.
            Timber.w(e, "WebView cookie owner not persisted")
            file.delete()
        }
    }

    companion object {
        internal const val FILE_NAME = "webview_cookie_owner"
    }
}
