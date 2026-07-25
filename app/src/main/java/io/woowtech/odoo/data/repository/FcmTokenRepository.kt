package io.woowtech.odoo.data.repository

/**
 * Manages FCM token registration with Odoo servers.
 * Token is stored locally in EncryptedPrefs and registered
 * with all active Odoo accounts via HTTP controller.
 */
interface FcmTokenRepository {

    /**
     * Registers the FCM token with all active Odoo server accounts.
     * Called when Firebase issues a new token via onNewToken.
     */
    suspend fun registerTokenForAllAccounts(token: String): Result<Unit>

    /**
     * Registers the FCM token with a specific Odoo account.
     */
    suspend fun registerToken(accountId: String, token: String): Result<Unit>

    /**
     * Unregisters the FCM token from a specific Odoo account.
     * Called when user logs out of an account.
     */
    suspend fun unregisterToken(accountId: String): Result<Unit>

    /**
     * Returns the locally stored FCM token, or null if not yet obtained.
     */
    fun getStoredToken(): String?

    /**
     * Reconciles the given current device [token] with the Odoo server on app launch.
     *
     * When at least one account is active/logged in, registers the token for all active accounts
     * (see [registerTokenForAllAccounts]); when no account is active it short-circuits without any
     * server call, returning success. This self-heals a stale server-side token without requiring a
     * re-login. Safe to call on every launch — registration is idempotent and serialized by the same
     * mutex used by `onNewToken` and login-time registration, so concurrent callers cannot race.
     */
    suspend fun reconcileToken(token: String): Result<Unit>

    /**
     * Event-driven reconcile fired when an account becomes available — restored on cold start, or
     * newly added on login/switch (S2 / AC8.b).
     *
     * Reads the current locally-stored token and upserts it for **each** logged-in account via
     * [registerTokenForAllAccounts]. When no token has been obtained yet (Firebase has not delivered
     * one), it is a no-op success: the token-arrived event (`onNewToken`) will drive registration
     * once a token exists. This is the trigger that closes the token-arrived-before-account race —
     * a token saved before any account existed is registered as soon as an account appears.
     *
     * The reconcile is deliberately trivial: it relies on the server's register-as-upsert
     * early-return (S1) for idempotency, so it keeps **no** persisted last-registered diff-set, does
     * **not** return a tri-state result, and does **not** reconcile against any server canonical-state
     * endpoint. Re-firing on every account event is therefore cheap and safe. Runs its work off the
     * main thread on [kotlinx.coroutines.Dispatchers.IO] and is serialized by the same mutex as the
     * other registration paths.
     */
    suspend fun reconcileOnAccountAvailable(): Result<Unit>
}
