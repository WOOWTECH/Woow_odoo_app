package io.woowtech.odoo.data.api

import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.ReloginReason
import io.woowtech.odoo.data.repository.ReloginSignal
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guardrail'd re-authentication engine that transparently self-heals an expired Odoo **session
 * cookie** when an authenticated request (currently the FCM register / unregister calls) is rejected
 * for session expiry.
 *
 * ## Why this exists
 * `/woow_fcm_push/register` is protected by an Odoo session cookie (`auth='user'`). Sessions expire
 * on inactivity or server restart, after which registration fails and the device silently stops
 * receiving push notifications. This engine re-authenticates **once** with the account's already-stored
 * credentials (the same secret used for biometric auto-login — no new credential-at-rest exposure),
 * refreshes the session cookie on the shared [OdooJsonRpcClient] cookie jar, and lets the caller replay
 * the original request.
 *
 * ## Detection lives in the interceptor, not here
 * Odoo `type='json'` routes return an expired session as **HTTP 200 with a JSON-RPC error envelope**
 * (`data.name == "odoo.http.SessionExpiredException"`), NOT HTTP 401, so an OkHttp [okhttp3.Authenticator]
 * (which only fires on 401) never triggers. Session-expiry detection therefore lives in
 * [SessionReauthInterceptor]; this class exposes [reauthenticateForHost] which the interceptor calls
 * once it has detected an expired session. All six security guardrails below live here so they are
 * enforced regardless of how expiry was detected.
 *
 * ## Guarantees (security review must-haves)
 * 1. **HTTPS-only + exact host.** Re-auth is attempted only against the account's own stored,
 *    previously-validated `https` host that exactly matches the failing request's host. The password
 *    is never POSTed to any other host, and the re-auth reuses the same [OdooJsonRpcClient] instance
 *    (so any TLS/pinning config applies) — never a fresh permissive client.
 * 2. **One re-auth + one retry per attempt.** Enforced by [SessionReauthInterceptor] via a retry
 *    marker header and a retry counter. No recursion, no loop.
 * 3. **Cause distinction.** Re-auth runs only for an expired session. If re-auth itself reports
 *    invalid credentials (password changed server-side) it STOPS, clears the stale session cookie for
 *    that host, and raises a re-login signal via [ReloginSignal]; the known-bad password is never
 *    re-sent.
 * 4. **Circuit breaker.** After [MAX_CONSECUTIVE_FAILURES] consecutive re-auth failures for an
 *    account, auto re-auth is disabled for it (a re-login signal is raised) until a manual re-login.
 * 5. **No credential/cookie logging.** Nothing here logs a password, cookie, or request body.
 * 6. **Single-flight.** A per-host [Mutex] guarantees concurrent session-expiry responses trigger
 *    exactly one re-auth network call per host.
 *
 * `runBlocking` is used to bridge the suspend [OdooJsonRpcClient.authenticate] into the blocking
 * OkHttp interceptor chain. This is the sanctioned exception to the no-`runBlocking` rule: the
 * interceptor already runs on OkHttp's own dispatcher thread pool, never on the main thread.
 */
@Singleton
class SessionReauthenticator @Inject constructor(
    private val accountDao: AccountDao,
    private val encryptedPrefs: EncryptedPrefs,
    private val odooClient: OdooJsonRpcClient,
    private val reloginSignal: ReloginSignal,
) {

    /** Per-host single-flight locks so concurrent expiries on the same host cause exactly one re-auth. */
    private val hostLocks = ConcurrentHashMap<String, Mutex>()

    /** Per-account consecutive re-auth failure counters backing the circuit breaker. */
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()

    /** Accounts whose circuit breaker is open — auto re-auth disabled until a manual re-login. */
    private val openCircuits = ConcurrentHashMap.newKeySet<String>()

    /**
     * Attempts to refresh the expired Odoo session for [requestHost], applying every security
     * guardrail. Returns true when a fresh session cookie was established on the shared cookie jar and
     * the caller may retry the original request once, or false when the caller must give up (no stored
     * account for the host, a non-https host, an open circuit, or a failed re-auth).
     *
     * Safe to call concurrently: a per-host single-flight [Mutex] collapses simultaneous callers into a
     * single re-auth network call for that host. Blocking — call it off the main thread (the OkHttp
     * interceptor chain already runs on a background dispatcher thread).
     */
    fun reauthenticateForHost(requestHost: String): Boolean {
        // Guardrail 1: refuse to touch anything that is not an exact stored https host.
        val account = resolveAccountForHost(requestHost) ?: run {
            Timber.w("Re-auth: no stored account matches request host — declining")
            return false
        }

        if (openCircuits.contains(account.id)) {
            Timber.w("Re-auth: circuit open for account %s — declining until manual re-login", account.id)
            return false
        }

        return runReauthSingleFlight(host = requestHost, account = account)
    }

    /**
     * Runs the re-authentication for [host] under a per-host single-flight lock so that parallel
     * session-expiry responses result in exactly one authenticate network call. Returns true when the
     * session was refreshed and the caller should retry, false when it must give up.
     */
    private fun runReauthSingleFlight(host: String, account: OdooAccount): Boolean {
        val lock = hostLocks.getOrPut(host) { Mutex() }
        return runBlocking {
            lock.withLock {
                // Re-check the circuit inside the lock: a concurrent expiry that ran first may have
                // opened it (invalid credentials), in which case we must not resend the password.
                if (openCircuits.contains(account.id)) {
                    return@withLock false
                }
                performReauth(account)
            }
        }
    }

    /**
     * Performs a single re-authentication against the account's own https host using its stored
     * credentials and applies the guardrails around the result. Returns true only when a fresh session
     * was established and the request should be retried.
     */
    private suspend fun performReauth(account: OdooAccount): Boolean {
        val password = encryptedPrefs.getPassword(account.id)
        if (password.isNullOrEmpty()) {
            // No stored secret to re-auth with — surface a re-login rather than silently failing.
            Timber.w("Re-auth: no stored credentials for account %s — signalling re-login", account.id)
            openCircuit(account.id, ReloginReason.INVALID_CREDENTIALS)
            return false
        }

        val serverUrl = account.fullServerUrl
        // Guardrail 1 (belt and suspenders — resolveAccountForHost already enforced https + exact host).
        if (!serverUrl.startsWith("https://")) {
            Timber.w("Re-auth: account %s server URL is not https — declining", account.id)
            return false
        }

        val result = odooClient.authenticate(
            serverUrl = serverUrl,
            database = account.database,
            username = account.username,
            password = password,
        )

        return when (result) {
            is AuthResult.Success -> {
                consecutiveFailures.remove(account.id)
                Timber.d("Re-auth: session refreshed for account %s", account.id)
                true
            }

            is AuthResult.Error -> handleReauthError(account, result)
        }
    }

    /**
     * Applies guardrails 3 (cause distinction / bad-credential stop) and 4 (circuit breaker) to a
     * failed re-authentication. Returns false in all cases (the request must not be retried).
     */
    private fun handleReauthError(account: OdooAccount, error: AuthResult.Error): Boolean {
        if (error.type == AuthResult.ErrorType.INVALID_CREDENTIALS) {
            // Guardrail 3: the stored password is wrong (changed server-side). Stop immediately, clear
            // the stale session, and surface a re-login. Never resend the known-bad password.
            Timber.w("Re-auth: stored credentials rejected for account %s — clearing session, signalling re-login", account.id)
            clearSessionFor(account)
            openCircuit(account.id, ReloginReason.INVALID_CREDENTIALS)
            return false
        }

        // Transient failure (network / server / timeout): count towards the circuit breaker.
        val failures = consecutiveFailures.merge(account.id, 1, Int::plus) ?: 1
        Timber.w("Re-auth: attempt %d/%d failed for account %s (%s)", failures, MAX_CONSECUTIVE_FAILURES, account.id, error.type)
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            Timber.w("Re-auth: circuit breaker opened for account %s after %d failures", account.id, failures)
            openCircuit(account.id, ReloginReason.REAUTH_CIRCUIT_OPEN)
        }
        return false
    }

    /** Opens the circuit for [accountId] and emits a re-login signal with the given [reason]. */
    private fun openCircuit(accountId: String, reason: ReloginReason) {
        openCircuits.add(accountId)
        reloginSignal.request(accountId = accountId, reason = reason)
    }

    /** Clears the stale session cookie for the account's host so no expired cookie lingers. */
    private fun clearSessionFor(account: OdooAccount) {
        // Use the bare host (no port) so it matches how OdooJsonRpcClient keys its cookie store.
        val host = hostOf(account.serverUrl) ?: return
        odooClient.clearCookies(host)
    }

    /**
     * Resolves the single stored account whose validated https host exactly matches [requestHost].
     *
     * Enforces guardrail 1: only accounts with an `https` server URL are considered, and the host must
     * match exactly (case-insensitive). Returns null when there is no such account (declines re-auth).
     * When multiple accounts share a host, the most-recently-used one (first in the DAO's
     * `lastLogin DESC` order) is chosen — its stored session is the one the request was using.
     */
    private fun resolveAccountForHost(requestHost: String): OdooAccount? = runBlocking {
        accountDao.getAllAccountsList()
            .firstOrNull { account ->
                // Guardrail 1: the account's STORED url must itself be https (not merely https after
                // fullServerUrl's fallback prefix). An http-stored account is never a re-auth target.
                val serverUrl = account.serverUrl
                if (!serverUrl.startsWith("https://")) return@firstOrNull false
                // Compare bare hosts (no port, case-insensitive) so resolution is consistent with
                // both response.request.url.host and OdooJsonRpcClient's cookie-store keying.
                val host = hostOf(serverUrl) ?: return@firstOrNull false
                host.equals(requestHost, ignoreCase = true)
            }
    }

    /**
     * Clears the circuit-breaker state for [accountId] after a successful manual re-login, so
     * automatic re-auth is re-enabled. Safe to call even when no circuit was open.
     */
    fun onManualReloginSucceeded(accountId: String) {
        openCircuits.remove(accountId)
        consecutiveFailures.remove(accountId)
    }

    companion object {
        /** Header stamped on a retried request so it can never be retried a second time. */
        internal const val RETRY_MARKER_HEADER = "X-Woow-Reauth-Retry"

        /** Consecutive transient re-auth failures per account before the circuit breaker opens. */
        internal const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * Extracts the bare host (no scheme, no port) from a stored server URL, or null when the URL
         * is unparseable. Keeping it bare keeps host resolution consistent with OkHttp's
         * `request.url.host` and with [OdooJsonRpcClient]'s cookie-store keying.
         */
        private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host
    }
}
