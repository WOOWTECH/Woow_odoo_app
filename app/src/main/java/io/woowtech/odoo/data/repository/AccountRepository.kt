package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val encryptedPrefs: EncryptedPrefs,
    private val odooClient: OdooJsonRpcClient
) {
    val allAccounts: Flow<List<OdooAccount>> = accountDao.getAllAccounts()
    val activeAccount: Flow<OdooAccount?> = accountDao.getActiveAccount()

    /**
     * FCM token repository is injected lazily (after construction) to avoid a circular
     * dependency: AccountRepository → FcmTokenRepository → AccountDao (already held here).
     * Set by the DI framework after both objects are constructed.
     */
    var fcmTokenRepository: FcmTokenRepository? = null

    suspend fun getActiveAccountOnce(): OdooAccount? = accountDao.getActiveAccountOnce()

    /** Returns a one-shot snapshot of all locally known accounts (most-recent login first). */
    suspend fun getAllAccountsOnce(): List<OdooAccount> = accountDao.getAllAccountsList()

    /**
     * Returns true when the account has stored credentials and can therefore be treated as
     * "logged in" for deep-link routing. A resolved-but-not-logged-in account causes the deep
     * link to be dropped rather than applied.
     */
    fun isLoggedIn(accountId: String): Boolean = encryptedPrefs.getPassword(accountId) != null

    suspend fun authenticate(
        serverUrl: String,
        database: String,
        username: String,
        password: String
    ): AuthResult {
        val fullUrl = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"

        val result = odooClient.authenticate(fullUrl, database, username, password)

        if (result is AuthResult.Success) {
            // Check if account already exists
            val existingAccount = accountDao.findAccount(fullUrl, database, username)

            val account = existingAccount?.copy(
                displayName = result.displayName,
                userId = result.userId,
                lastLogin = System.currentTimeMillis(),
                isActive = true
            ) ?: OdooAccount(
                serverUrl = fullUrl,
                database = database,
                username = username,
                displayName = result.displayName,
                userId = result.userId,
                isActive = true
            )

            // Deactivate other accounts and save this one
            accountDao.deactivateAllAccounts()
            accountDao.insertAccount(account)

            // Save password securely
            encryptedPrefs.savePassword(account.id, password)

            // S2 / AC8.b — account-added event: fire the event-driven reconcile so the current
            // token is upserted for EACH logged-in account (not only this one). This starts push
            // delivery immediately and, by re-reading the stored token, also self-heals the
            // token-arrived-before-account race — a token saved by onNewToken before any account
            // existed is registered as soon as this account appears. Idempotent server-side, so
            // re-firing is cheap; best-effort, so a failure never blocks login.
            //
            // Symmetric counterpart of `logout → unregisterToken` below.
            // See `CLAUDE.md` § "Repository-Event Symmetry" for why.
            fcmTokenRepository?.reconcileOnAccountAvailable()
                ?.onSuccess { Timber.d("FCM account-available reconcile completed after login") }
                ?.onFailure { error ->
                    Timber.w(
                        error,
                        "FCM account-available reconcile after login partially failed — user may miss notifications until the next trigger",
                    )
                }
        }

        return result
    }

    suspend fun switchAccount(accountId: String): Boolean {
        val account = accountDao.getAccountById(accountId) ?: return false
        val password = encryptedPrefs.getPassword(accountId) ?: return false

        // Story 8-1 (P2-9): switching accounts deliberately does NOT unregister the
        // previously-active account's FCM token any more.
        //
        // The removed code unregistered A before re-authenticating as B, on the rationale
        // that otherwise "both accounts remain active in woow.fcm.device" and A's
        // notifications keep arriving — cross-account bleed. That was correct under the OLD
        // server schema, where one token mapped to exactly one row. It is wrong now, for
        // two independent reasons:
        //
        //  1. The server was redesigned for precisely this case. The plugin has
        //     UNIQUE(fcm_token, user_id), so one physical token legitimately maps to MANY
        //     users, and the send path DISTINCT-dedupes by token so the device still gets
        //     exactly ONE push. Both accounts holding a row is the DESIGNED state.
        //  2. This app already contradicted itself: registerTokenForAllAccounts registers
        //     EVERY account unconditionally, so the switch was deleting a row that the very
        //     next token refresh or cold-start replay put straight back.
        //
        // And it was not merely redundant. A push deep link can drive switchAccount, so a
        // notification mis-routed by a colliding tenant id would KILL PUSH for an unrelated
        // account — a denial-of-notifications primitive handed to any peer server. Removing
        // the side effect is what takes that primitive away; story 8-1's ambiguity check in
        // DeepLinkRouter closes the other half.
        //
        // Explicit user-initiated logout still unregisters (see `logout`), which is the
        // honest meaning of "stop sending me this account's pushes". Do not restore this
        // call here without first re-checking the server's uniqueness constraint.

        // Try to re-authenticate (this overwrites the cookie jar for the host)
        val result = odooClient.authenticate(
            account.fullServerUrl,
            account.database,
            account.username,
            password
        )

        return if (result is AuthResult.Success) {
            accountDao.deactivateAllAccounts()
            accountDao.activateAccount(accountId)
            accountDao.updateLastLogin(accountId)
            // Same reason as authenticate(): the FCM token may have been
            // saved before this account became active. Replay it.
            registerSavedFcmToken(accountId)
            true
        } else {
            false
        }
    }

    /**
     * Register the locally-saved FCM token with the given Odoo account.
     *
     * Used by [switchAccount] to register ONLY the switched-to account under the currently-active
     * session cookie. Switch must not register the other accounts here: it has just unregistered the
     * previously-active account on purpose (see [switchAccount]), and re-registering all accounts
     * would undo that. (Login instead uses [FcmTokenRepository.reconcileOnAccountAvailable], which
     * upserts the token for every logged-in account — there is no prior unregister to preserve.)
     *
     * This is also the "replay" path for the case where `WoowFcmService.onNewToken`
     * fired with zero accounts (e.g., fresh install before login) — the token
     * was saved to `EncryptedPrefs` but never POSTed to any server.
     *
     * Failure is non-fatal — the user can still use the app, they just won't
     * receive push notifications until the next token rotation (which will
     * fire `onNewToken` again, this time with at least one account present).
     * A warning is logged so it can be correlated with reports of "missed
     * notifications".
     */
    private suspend fun registerSavedFcmToken(accountId: String) {
        val repo = fcmTokenRepository ?: return
        val token = repo.getStoredToken() ?: return
        repo.registerToken(accountId = accountId, token = token)
            .onSuccess { Timber.d("FCM token registered for account %s on login", accountId) }
            .onFailure { error ->
                Timber.w(
                    error,
                    "FCM token register-on-login failed for account %s — user may miss notifications until next token rotation",
                    accountId,
                )
            }
    }

    /**
     * Logs out of the given account (or the active account if [accountId] is null).
     *
     * C3: Before clearing the local session, attempts to unregister the FCM token from
     * the Odoo server so the device stops receiving notifications after logout. If the
     * unregister call fails (e.g. network unavailable or session already expired), the
     * failure is logged as a warning and logout proceeds — the user must not be blocked
     * by a network failure when attempting to sign out.
     */
    /**
     * Logs out the given (or active) account. Returns whether the app should STAY authenticated:
     * `true` when another account was promoted to active (multi-account fallback), `false` when no
     * accounts remain and the caller should navigate to the login screen.
     *
     * Fix (multi-account parity with iOS): previously logout deleted the active account without
     * promoting a remaining one, so the app dropped to the login screen even though another valid
     * account was still signed in.
     */
    suspend fun logout(accountId: String? = null): Boolean {
        val id = accountId ?: accountDao.getActiveAccountOnce()?.id ?: return false
        val account = accountDao.getAccountById(id) ?: return false
        val wasActive = account.isActive

        // C3: Attempt to unregister FCM token before session is cleared. Non-fatal if it
        // fails — the token will eventually be cleaned up server-side when it bounces.
        fcmTokenRepository?.let { repo ->
            repo.unregisterToken(id)
                .onSuccess { Timber.d("FCM token unregistered for account %s before logout", id) }
                .onFailure { error ->
                    Timber.w(error, "FCM unregister failed for account %s — proceeding with logout anyway", id)
                }
        }

        // Clear cookies
        val host = account.fullServerUrl.removePrefix("https://").split("/").first()
        odooClient.clearCookies(host)

        // Remove password
        encryptedPrefs.removePassword(id)

        // Delete account from database
        accountDao.deleteAccountById(id)

        // Multi-account fallback: if other accounts remain and we logged out the ACTIVE one (or none
        // is active), promote the most-recently-used remaining account so the app stays authenticated
        // instead of stranding the user on the login screen. getAllAccountsList() is ORDER BY
        // lastLogin DESC, so the first entry is the most recent.
        val remaining = accountDao.getAllAccountsList()
        if (remaining.isEmpty()) {
            return false
        }
        if (wasActive || remaining.none { it.isActive }) {
            accountDao.deactivateAllAccounts()
            accountDao.activateAccount(remaining.first().id)
        }
        return true
    }

    /**
     * Removes the given account from local storage WITHOUT performing a full
     * logout flow.
     *
     * Unlike [logout], `removeAccount` does NOT clear cookies — it is used in
     * scenarios where the local record is being purged (e.g., user removed
     * the account from a multi-account drawer) but the session may still
     * exist for other purposes.
     *
     * **FCM cleanup**: same hazard as [logout] — without an unregister POST
     * to the Odoo server, the server-side `woow.fcm.device` record for this
     * account stays active. Future pushes for that account would arrive on
     * this device even though the local record is gone. Best-effort
     * unregister; failure is logged and the deletion proceeds.
     *
     * Symmetric counterpart of [authenticate]'s register-on-login path
     * (CLAUDE.md § "Repository-Event Symmetry").
     */
    suspend fun removeAccount(accountId: String) {
        // Best-effort FCM unregister before local deletion. If the device
        // is offline we still proceed — the local record removal is the
        // user-facing intent and must not be blocked by network state.
        fcmTokenRepository?.let { repo ->
            repo.unregisterToken(accountId)
                .onSuccess { Timber.d("FCM token unregistered for account %s before removal", accountId) }
                .onFailure { error ->
                    Timber.w(
                        error,
                        "FCM unregister failed during removeAccount(%s) — server may keep this device active until token rotates",
                        accountId,
                    )
                }
        }
        encryptedPrefs.removePassword(accountId)
        accountDao.deleteAccountById(accountId)
    }

    fun getSessionId(serverUrl: String): String? {
        val host = serverUrl.removePrefix("https://").removePrefix("http://").split("/").first()
        return odooClient.getSessionId(host)
    }

    fun getSessionCookies(serverUrl: String): List<okhttp3.Cookie> {
        val host = serverUrl.removePrefix("https://").removePrefix("http://").split("/").first()
        return odooClient.getSessionCookies(host)
    }

    suspend fun getAccountCount(): Int = accountDao.getAccountCount()
}
