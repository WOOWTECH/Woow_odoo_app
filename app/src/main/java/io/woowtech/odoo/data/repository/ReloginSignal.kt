package io.woowtech.odoo.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifies why the app is asking the user to sign in again.
 *
 * Used to keep the wording of the re-login prompt honest without leaking any secret: it only
 * carries the account id and a stable [reason] code, never a password or session cookie.
 */
enum class ReloginReason {
    /**
     * The stored password was rejected by the server during a silent re-authentication (the password
     * was very likely changed server-side). The known-bad password must NOT be re-sent — the user has
     * to enter the new one.
     */
    INVALID_CREDENTIALS,

    /**
     * Too many consecutive silent re-authentication attempts failed for the account, so automatic
     * re-auth has been disabled (circuit breaker) until the user signs in manually.
     */
    REAUTH_CIRCUIT_OPEN,
}

/**
 * A request for the user to re-authenticate a specific account.
 *
 * @property accountId the local account that needs a manual re-login.
 * @property reason why the re-login is required (see [ReloginReason]). Never contains credentials.
 */
data class ReloginRequest(
    val accountId: String,
    val reason: ReloginReason,
)

/**
 * Application-wide, single-source-of-truth signal that a background re-authentication has failed
 * unrecoverably and the user must sign in again.
 *
 * The FCM re-auth [SessionReauthenticator][io.woowtech.odoo.data.api.SessionReauthenticator] emits
 * here when a stored password is rejected or the per-account circuit breaker trips. UI (e.g. the main
 * screen) or a notification can observe [pending] and prompt the user; once handled it calls [clear].
 *
 * Kept deliberately minimal: no credential ever passes through this class, and it exposes only an
 * immutable [StateFlow] plus [request]/[clear] mutators so callers cannot mutate the state directly.
 */
@Singleton
class ReloginSignal @Inject constructor() {

    private val _pending = MutableStateFlow<ReloginRequest?>(null)

    /** The current outstanding re-login request, or null when none is pending. */
    val pending: StateFlow<ReloginRequest?> = _pending.asStateFlow()

    /**
     * Records that [accountId] needs a manual re-login for the given [reason].
     *
     * Idempotent: emitting the same request repeatedly keeps a single outstanding prompt rather than
     * queuing duplicates, so a storm of background 401s cannot produce a storm of prompts.
     */
    fun request(accountId: String, reason: ReloginReason) {
        _pending.value = ReloginRequest(accountId = accountId, reason = reason)
    }

    /** Clears the outstanding re-login request once the UI has handled it (or the user re-logged in). */
    fun clear() {
        _pending.value = null
    }
}
