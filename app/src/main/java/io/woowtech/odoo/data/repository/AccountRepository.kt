package io.woowtech.odoo.data.repository

import io.woowtech.odoo.brand.AppBrand
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.api.SessionOwnership
import io.woowtech.odoo.data.api.SessionReauthenticator
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository(
    private val accountDao: AccountDao,
    private val encryptedPrefs: EncryptedPrefs,
    private val odooClient: OdooJsonRpcClient,
    private val brand: AppBrand,
    /**
     * Shared auto re-auth engine. Every successful manual sign-in / switch below re-closes its
     * per-account circuit breaker ([SessionReauthenticator.onManualReloginSucceeded]); otherwise a
     * breaker opened earlier in this process stays open until restart. Null only in unit tests.
     */
    private val sessionReauthenticator: SessionReauthenticator? = null,
    /** Runs best-effort server-side session revokes without blocking logout / switch (iOS D1/D5 parity). */
    private val revokeScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
) : SessionReauthenticator.HealCommitter {
    @Inject
    constructor(
        accountDao: AccountDao,
        encryptedPrefs: EncryptedPrefs,
        odooClient: OdooJsonRpcClient,
        sessionReauthenticator: SessionReauthenticator,
    ) : this(accountDao, encryptedPrefs, odooClient, AppBrand.current, sessionReauthenticator)

    // Apporo-only: an old response cannot commit over a newer explicit login/switch intent.
    private val selectionMutex = Mutex()
    private var selectionAttempt = 0L
    private suspend fun beginSelection(): Long = selectionMutex.withLock { ++selectionAttempt }

    /**
     * pi 1001g P1 (iOS a8f374f parity): per sign-in identity (server, db, login) a removal generation, bumped
     * when its logout / removal starts and again when it ends, plus the identities being removed right now. A
     * sign-in commits only if its identity's generation did not move since it started, so a sign-in started
     * before or during a removal can never recreate the removed account. Guarded by [selectionMutex].
     */
    private val removalGenerations = HashMap<String, Long>()
    private val identitiesBeingRemoved = HashSet<String>()
    private var removalCounter = 0L


    /** WebView side of logout / removal (iOS D1 parity); wired by DI, null in unit tests that don't need it. */
    var webDataCleaner: AccountWebDataCleaner? = null

    /**
     * The Odoo session each account last had (minted by sign-in / switch / self-heal, or read back from the
     * WebView when switching away). Written through to the encrypted [knownSessionStore] so it survives an app
     * restart (pi 1001b P2); never logged. An account switch or promotion reuses it after proving it still
     * belongs to that uid and db (iOS D5 parity), and logout revokes it.
     */
    private val knownSessions = KnownSessions { knownSessionStore }

    /** Durable, encrypted backing for [knownSessions]; wired by DI (pi 1001b P2), in-memory otherwise. */
    var knownSessionStore: io.woowtech.odoo.data.local.KnownSessionStore = io.woowtech.odoo.data.local.KnownSessionStore.InMemory()
        set(value) {
            field = value
            // pi 1001d P2: wired once at startup — records left behind by a removal that never reached the disk
            // (the in-memory retry died with the process) are deleted, so the cleanup survives a restart.
            launchDetached(revokeScope) { sweepOrphanSessionRecords() }
        }

    private suspend fun sweepOrphanSessionRecords() {
        recordFence.withLock { knownSessions.sweep(accountDao.getAllAccountsList().map { it.id }.toSet()) }
    }

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

    /**
     * Deep-link routing predicate. The ACTIVE account is usable through its live session even when
     * "Remember me" was off (no stored password); any other account must be switched to, which needs
     * a stored password.
     */
    fun canRouteDeepLink(accountId: String, activeAccountId: String?): Boolean =
        accountId == activeAccountId || isLoggedIn(accountId)

    /**
     * @param rememberPassword the login screen's "Remember me". When false the password is NOT
     * persisted and any password previously remembered for this same account is removed, so no
     * reusable secret outlives the session (silent re-auth / switching back will ask to sign in).
     */
    suspend fun authenticate(
        serverUrl: String,
        database: String,
        username: String,
        password: String,
        rememberPassword: Boolean = true,
    ): AuthResult {
        if (brand.isApporo) return authenticateApporo(serverUrl, database, username, password, rememberPassword)
        // pi 1001f P1: the Apporo selection fence — a sign-in commits only while it is still the newest selection.
        val attempt = beginSelection()
        invalidateHeals()
        val fullUrl = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"
        val removalTicket = removalTicketOf(fullUrl, database, username)
        // pi 0930b P2: keep the displayed account's live WebView session so a later promotion can reuse it.
        accountDao.getActiveAccountOnce()?.id?.let { rememberWebViewSession(it) }

        val result = odooClient.authenticate(fullUrl, database, username, password)

        if (result is AuthResult.Success) {
            var published = false
            val committed = try {
                selectionMutex.withLock {
                    if (attempt != selectionAttempt) return@withLock false
                    // pi 1001g P1: nor may it recreate an account whose logout / removal started after it did.
                    if (!identityUnremovedLocked(fullUrl, database, username, removalTicket)) return@withLock false
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

                    // pi 1001f P1: rows, password (only when remembered, W1-10), jar and record are written at one
                    // non-cancellable boundary; the session reaches the jar only once the rows are written.
                    commitApporoSelection(account, existingAccount, password.takeIf { rememberPassword }, result.sessionId,
                        forgetPassword = !rememberPassword,
                        onPublished = {
                            published = true
                            knownSessions[account.id] = result.sessionId
                        },
                    ) {
                        accountDao.insertAccount(account)
                    }
                    fcmTokenRepository?.onManualLogin(account.id, result.sessionId)
                    sessionReauthenticator?.onManualReloginSucceeded(account.id)
                    true
                }
            } finally {
                // Minted but never published (superseded, failed or cancelled): nothing on this device will use it.
                if (!published) revokeLater(fullUrl, result.sessionId)
            }
            if (!committed) {
                return AuthResult.Error("Sign-in superseded by a newer selection", AuthResult.ErrorType.UNKNOWN)
            }

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
        if (brand.isApporo) return switchApporoAccount(accountId)
        // pi 1001f P1: the Apporo selection fence — a switch commits only while it is still the newest selection.
        val attempt = beginSelection()
        invalidateHeals()
        val account = accountDao.getAccountById(accountId) ?: return false
        val password = encryptedPrefs.getPassword(accountId) ?: return false

        // Capture the previously-active account so we can unregister its FCM
        // token on the server side. Without this, both accounts remain active
        // in `woow.fcm.device` and the previous user's notifications keep
        // arriving on the device after switching. Symmetric with the
        // `logout → unregisterToken` pattern (CLAUDE.md
        // § "Repository-Event Symmetry").
        val previousActiveAccountId = accountDao.getActiveAccountOnce()?.id
        if (previousActiveAccountId != null && previousActiveAccountId != accountId) rememberWebViewSession(previousActiveAccountId)

        // CRITICAL ordering: unregister A BEFORE re-authenticating as B.
        //
        // OdooJsonRpcClient's cookie jar is keyed by HOST, not accountId
        // (see `OdooJsonRpcClient.kt`: `cookieStore.getOrPut(url.host)`).
        // For multi-account on the same Odoo host, re-authenticating as B
        // OVERWRITES A's session cookie. If we unregistered A AFTER re-auth,
        // the unregister POST would carry B's cookie and either silently
        // no-op (server can't find A's record) or worse, delete B's
        // brand-new record. So: unregister first, while A's cookie is live.
        //
        // pi 1001f P2: if the switch then fails or is cancelled, A is registered again (below) while A is still
        // the displayed account and no newer selection has started, so A keeps receiving its notifications.
        val unregistered = previousActiveAccountId != null && previousActiveAccountId != accountId && fcmTokenRepository != null
        if (previousActiveAccountId != null && previousActiveAccountId != accountId) {
            fcmTokenRepository?.let { repo ->
                repo.unregisterToken(previousActiveAccountId)
                    .onSuccess {
                        Timber.d(
                            "FCM token unregistered for previous account %s before switching to %s",
                            previousActiveAccountId, accountId,
                        )
                    }
                    .onFailure { error ->
                        Timber.w(
                            error,
                            "FCM unregister of previous account %s failed during switch — server may keep delivering its notifications until token rotates",
                            previousActiveAccountId,
                        )
                    }
            }
        }

        // Live 1001e W3 (Apporo parity): reuse the target's known session when the server proves it is this uid
        // AND db, instead of signing in again on every switch and orphaning the session it replaces.
        val stored = knownSessions[accountId]
        val userId = account.userId
        val reused = stored?.takeIf {
            userId != null && validApporoSession(it) &&
                odooClient.sessionOwnership(account.fullServerUrl, it, userId, account.database) == SessionOwnership.Belongs
        }

        // Sign in again only when no stored session is proven; the result is published at the commit below.
        val result = if (reused != null) {
            AuthResult.Success(userId!!, reused, account.username, account.displayName)
        } else odooClient.authenticate(
            account.fullServerUrl,
            account.database,
            account.username,
            password
        )

        // pi 1001f P1: one commit boundary — attempt still current, target unchanged, rows written — then the jar
        // and the record. A loser keeps the existing jar and revokes only its own unpublished session.
        var published = false
        val committed = try {
            result is AuthResult.Success && selectionMutex.withLock {
                val current = accountDao.getAccountById(accountId)
                if (attempt != selectionAttempt || current == null || current.serverUrl != account.serverUrl ||
                    current.database != account.database || current.username != account.username ||
                    current.userId != account.userId
                ) return@withLock false
                commitApporoSelection(current, current, null, result.sessionId, onPublished = {
                    published = true
                    knownSessions[accountId] = result.sessionId
                    // pi 1001f P2: the target's previous session is revoked only after the new one is committed,
                    // and only when it is positively still the target's and no other account holds it (pi 1001d P1).
                    if (reused == null && stored != null && stored != result.sessionId) revokeReplacedIfOwn(current, stored)
                }) {
                    accountDao.activateAccount(accountId)
                    accountDao.updateLastLogin(accountId)
                }
                fcmTokenRepository?.onManualLogin(accountId, result.sessionId)
                sessionReauthenticator?.onManualReloginSucceeded(accountId)
                true
            }
        } finally {
            if (!published && reused == null && result is AuthResult.Success) revokeLater(account.fullServerUrl, result.sessionId)
            if (!published && unregistered) reRegisterIfStillDisplayed(previousActiveAccountId!!, attempt)
        }

        // Same reason as authenticate(): the FCM token may have been
        // saved before this account became active. Replay it.
        if (committed) registerSavedFcmToken(accountId)
        return committed
    }

    /**
     * pi 1001f P2: a failed / superseded / cancelled WOOW switch already unregistered [accountId]'s push device.
     * Registers it again (detached, so it also runs after a cancellation) only while no newer selection has
     * started and [accountId] is still the displayed account; a newer selection owns the device state otherwise.
     */
    private fun reRegisterIfStillDisplayed(accountId: String, attempt: Long) {
        launchDetached(revokeScope) {
            selectionMutex.withLock {
                if (attempt == selectionAttempt && accountDao.getActiveAccountOnce()?.id == accountId) {
                    registerSavedFcmToken(accountId)
                }
            }
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
        // pi 1001g P1: before any network wait, supersede every sign-in / switch in flight and tombstone this
        // identity, so none of them can recreate or reactivate the account being logged out.
        beginIdentityRemoval(account)
        try {
            return logoutAccount(account)
        } finally {
            endIdentityRemoval(account)
        }
    }

    private suspend fun logoutAccount(account: OdooAccount): Boolean {
        val id = account.id
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

        // D1 (iOS parity): wipe only this account's WebView data and native session, then revoke it server-side.
        // pi 1001c P2: one non-cancellable boundary, and the session record is forgotten only together with the
        // account row, so a cancellation can never leave the account without the record needed to clean it up.
        withContext(NonCancellable) {
            beginRemoval(id)
            try {
                removeAccountSessions(account)
                encryptedPrefs.removePassword(id)
                deleteAccountAndRecord(id)
            } finally {
                removing -= id
            }
        }
        fcmTokenRepository?.forgetAccount(id)

        // Multi-account fallback: if other accounts remain and we logged out the ACTIVE one (or none
        // is active), promote the most-recently-used remaining account so the app stays authenticated
        // instead of stranding the user on the login screen. getAllAccountsList() is ORDER BY
        // lastLogin DESC, so the first entry is the most recent.
        val remaining = accountDao.getAllAccountsList()
        if (remaining.isEmpty()) {
            return false
        }
        if (wasActive || remaining.none { it.isActive }) {
            // D5 (iOS parity): hand the promoted account its still-valid session before it is shown.
            publishKnownSession(remaining.first())
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
        // pi 1001g P1: same removal fence as logout, before any network wait.
        val target = accountDao.getAccountById(accountId)
        if (target != null) beginIdentityRemoval(target) else beginSelection()
        try {
            removeAccountRow(accountId)
        } finally {
            if (target != null) endIdentityRemoval(target)
        }
    }

    private suspend fun removeAccountRow(accountId: String) {
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
        // D1 (iOS parity): the removed account's sessions are wiped and revoked like on logout (same boundary).
        withContext(NonCancellable) {
            beginRemoval(accountId)
            try {
                accountDao.getAccountById(accountId)?.let { removeAccountSessions(it) }
                encryptedPrefs.removePassword(accountId)
                deleteAccountAndRecord(accountId)
            } finally {
                removing -= accountId
            }
        }
        fcmTokenRepository?.forgetAccount(accountId)
    }

    // Apporo selection/session commit boundary. WOOW paths above retain legacy semantics.
    private fun validApporoSession(sessionId: String): Boolean =
        sessionId.isNotBlank() && sessionId.none { it <= ' ' || it == ';' || it >= '\u007f' }

    private suspend fun authenticateApporo(
        serverUrl: String, database: String, username: String, password: String, rememberPassword: Boolean,
    ): AuthResult {
        val attempt = beginSelection()
        invalidateHeals()
        val fullUrl = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"
        val removalTicket = removalTicketOf(fullUrl, database, username)
        val result = odooClient.authenticateApporoIsolated(fullUrl, database, username, password)
        if (result !is AuthResult.Success) return result
        if (!validApporoSession(result.sessionId)) return AuthResult.Error("Sign-in session was not established", AuthResult.ErrorType.SESSION_EXPIRED)
        var published = false
        val committed = try {
            // D5: keep the displayed account's live WebView session (the new login replaces it) so switching back can reuse it.
            accountDao.getActiveAccountOnce()?.id?.let { rememberWebViewSession(it) }
            selectionMutex.withLock {
                if (attempt != selectionAttempt) return@withLock false
                // pi 1001g P1: a sign-in never recreates an account whose logout / removal started after it did.
                if (!identityUnremovedLocked(fullUrl, database, username, removalTicket)) return@withLock false
                val existing = accountDao.findAccount(fullUrl, database, username)
                val account = existing?.copy(displayName = result.displayName, userId = result.userId,
                    lastLogin = System.currentTimeMillis(), isActive = true)
                    ?: OdooAccount(serverUrl = fullUrl, database = database, username = username,
                        displayName = result.displayName, userId = result.userId, isActive = true)
                commitApporoSelection(account, existing, password.takeIf { rememberPassword }, result.sessionId,
                    forgetPassword = !rememberPassword,
                    onPublished = {
                        published = true
                        // Replaced only now that the new session is committed (iOS D5 P1 lesson).
                        knownSessions.put(account.id, result.sessionId)
                            ?.takeIf { it != result.sessionId }?.let { revokeReplacedIfOwn(account, it) }
                    },
                ) {
                    accountDao.insertAccount(account)
                }
                fcmTokenRepository?.onManualLogin(account.id, result.sessionId)
                sessionReauthenticator?.onManualReloginSucceeded(account.id)
                true
            }
        } finally {
            // Minted but never published (superseded, failed or cancelled): nothing on this device will ever use it.
            if (!published) revokeLater(fullUrl, result.sessionId)
        }
        if (!committed) {
            return AuthResult.Error("Sign-in superseded by a newer selection", AuthResult.ErrorType.UNKNOWN)
        }
        fcmTokenRepository?.reconcileOnAccountAvailable()
            ?.onFailure { Timber.w("Push reconcile after Apporo login failed; see account registration status") }
        return result
    }

    private suspend fun switchApporoAccount(accountId: String): Boolean {
        val attempt = beginSelection()
        invalidateHeals()
        val account = accountDao.getAccountById(accountId) ?: return false
        val password = encryptedPrefs.getPassword(accountId) ?: return false
        val previous = accountDao.getActiveAccountOnce()?.id
        // D5: keep the outgoing account's live WebView session so switching back can reuse it.
        if (previous != null && previous != accountId) rememberWebViewSession(previous)
        // Preserve previous-account unregister BEFORE authentication, including same-host switches.
        if (previous != null && previous != accountId) {
            fcmTokenRepository?.unregisterToken(previous)
                ?.onFailure { Timber.w("Push cleanup before Apporo switch failed") }
        }
        // D5 (iOS parity): reuse the target's session when the server still proves it is this uid AND db;
        // only otherwise sign in again. A reused session is not a new server session.
        val stored = knownSessions[accountId]
        val reused = stored?.takeIf { sessionStillBelongs(account, it) }
        val sessionId = reused ?: run {
            val result = odooClient.authenticateApporoIsolated(account.fullServerUrl, account.database, account.username, password)
            if (result !is AuthResult.Success || !validApporoSession(result.sessionId)) return false
            if (result.userId != account.userId) {
                revokeLater(account.fullServerUrl, result.sessionId)
                return false
            }
            result.sessionId
        }
        var published = false
        val committed = try {
            selectionMutex.withLock {
                val current = accountDao.getAccountById(accountId)
                if (attempt != selectionAttempt || current == null || current.serverUrl != account.serverUrl ||
                    current.database != account.database || current.username != account.username ||
                    current.userId != account.userId || encryptedPrefs.getPassword(accountId) != password
                ) return@withLock false
                commitApporoSelection(current, current, null, sessionId, onPublished = {
                    published = true
                    knownSessions[accountId] = sessionId
                    // The stale session is revoked only after the replacement committed (iOS D5 P1 lesson): a
                    // superseded switch must never revoke the target's only stored session.
                    if (reused == null && stored != null && stored != sessionId) revokeReplacedIfOwn(current, stored)
                }) {
                    accountDao.activateAccount(accountId)
                    accountDao.updateLastLogin(accountId)
                }
                fcmTokenRepository?.onManualLogin(accountId, sessionId)
                sessionReauthenticator?.onManualReloginSucceeded(accountId)
                true
            }
        } finally {
            // A freshly minted session that was never published (superseded, failed or cancelled) is orphaned.
            if (!published && reused == null) revokeLater(account.fullServerUrl, sessionId)
        }
        if (committed) registerSavedFcmToken(accountId)
        return committed
    }

    /** Whether [sessionId] is still [account]'s live session on its server (uid AND db; fails closed). */
    private suspend fun sessionStillBelongs(account: OdooAccount, sessionId: String): Boolean {
        val userId = account.userId ?: return false
        return validApporoSession(sessionId) &&
            odooClient.sessionBelongsTo(account.fullServerUrl, sessionId, userId, account.database)
    }

    /** Records the WebView's session of [accountId] (when the WebView cookies are its) before a switch replaces it. */
    private suspend fun rememberWebViewSession(accountId: String) {
        val account = accountDao.getAccountById(accountId) ?: return
        webDataCleaner?.webViewSessionIdOf(accountId, account.fullServerUrl)
            ?.takeIf { validApporoSession(it) }
            ?.let { knownSessions[accountId] = it }
    }

    /**
     * Before [account] is promoted after a logout (both brands), publishes its known session when the
     * server still proves it is that uid AND db, so its WebView shows it instead of signing in again and
     * orphaning it (live finding 2026-09-30; WOOW: pi 0930b Android P2). Otherwise nothing is published
     * and the existing self-heal sign-in takes over; the stale entry is forgotten.
     */
    private suspend fun publishKnownSession(account: OdooAccount) {
        val sessionId = knownSessions[account.id] ?: return
        val userId = account.userId
        val ownership = if (userId == null || !validApporoSession(sessionId)) SessionOwnership.Unknown
            else odooClient.sessionOwnership(account.fullServerUrl, sessionId, userId, account.database)
        if (ownership != SessionOwnership.Belongs) {
            // pi 1001c P1: never revoked here. Unknown (offline, timeout, unparsable) proves nothing, so the record
            // is kept to be proven later; a proven mismatch may be someone else's session, so it is only forgotten.
            if (ownership == SessionOwnership.ProvenMismatch) knownSessions.remove(account.id, sessionId)
            return
        }
        if (brand.isApporo) odooClient.publishApporoSession(account.fullServerUrl, sessionId)
        else odooClient.publishSession(account.fullServerUrl, sessionId)
    }

    /**
     * pi 1001d P1 self-heal fence. Every manual sign-in / switch (and every logout / removal) advances the
     * generation; a heal publishes its session to the jar and records it only if no such selection started
     * since its ticket and its account still exists. A losing heal revokes only its own, never-published session.
     */
    private val healGeneration = java.util.concurrent.atomic.AtomicLong()

    override fun beginHeal(accountId: String): Long = healGeneration.get()

    override suspend fun commitHeal(accountId: String, serverUrl: String, sessionId: String, ticket: Long): Boolean {
        if (!validApporoSession(sessionId)) return false
        val committed = recordFence.withLock {
            val current = ticket == healGeneration.get() && accountId !in removing &&
                accountDao.getAccountById(accountId) != null
            if (current) {
                if (brand.isApporo) odooClient.publishApporoSession(serverUrl, sessionId)
                else odooClient.publishSession(serverUrl, sessionId)
                knownSessions[accountId] = sessionId
            }
            current
        }
        if (!committed) revokeLater(serverUrl, sessionId)
        return committed
    }

    /**
     * Accounts whose logout / removal is in progress (pi 1001d P1 deletion window): from its start until the
     * row is gone no heal may commit for them — a session created in that window is revoked, not recorded.
     */
    private val removing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun beginRemoval(accountId: String) {
        recordFence.withLock {
            removing += accountId
            healGeneration.incrementAndGet()
        }
    }

    /** A manual selection supersedes every heal in flight (pi 1001d P1). */
    private suspend fun invalidateHeals() {
        recordFence.withLock { healGeneration.incrementAndGet() }
    }

    /** Serializes "record a self-heal session" against "delete the account and its record" (pi 1001c P2). */
    private val recordFence = Mutex()

    private suspend fun deleteAccountAndRecord(accountId: String) = recordFence.withLock {
        accountDao.deleteAccountById(accountId)
        knownSessions.remove(accountId)
    }

    /**
     * pi 1001d P1: a session REPLACED by a newer one of [account] is revoked only when no other account holds it
     * (its record, its WebView, or the jar while it is displayed) and the server positively proves it is still
     * [account]'s (uid AND db). A stale record may point at another account's live session; destroy acts on the
     * session id alone, so "not proven" never authorizes it.
     */
    private fun revokeReplacedIfOwn(account: OdooAccount, sessionId: String) {
        launchDetached(revokeScope) {
            val userId = account.userId ?: return@launchDetached
            if (!validApporoSession(sessionId) || heldByAnotherAccount(account.id, account.fullServerUrl, sessionId)) {
                return@launchDetached
            }
            if (odooClient.sessionOwnership(account.fullServerUrl, sessionId, userId, account.database) == SessionOwnership.Belongs) {
                odooClient.revokeSession(account.fullServerUrl, sessionId)
            }
        }
    }

    private suspend fun heldByAnotherAccount(accountId: String, serverUrl: String, sessionId: String): Boolean {
        val others = accountDao.getAllAccountsList().filter { it.id != accountId }
        if (others.any { knownSessions[it.id] == sessionId }) return true
        if (others.any { webDataCleaner?.webViewSessionIdOf(it.id, it.fullServerUrl) == sessionId }) return true
        val displayed = accountDao.getActiveAccountOnce()
        val host = sessionHostOf(serverUrl)
        return displayed != null && displayed.id != accountId && host != null && odooClient.getSessionId(host) == sessionId
    }

    private fun revokeLater(serverUrl: String, sessionId: String) {
        launchDetached(revokeScope) { odooClient.revokeSession(serverUrl, sessionId) }
    }

    private fun identityKey(serverUrl: String, database: String, username: String): String {
        val url = if (serverUrl.startsWith("https://") || serverUrl.startsWith("http://")) serverUrl else "https://$serverUrl"
        return listOf(url.trimEnd('/'), database, username).joinToString("\u0000")
    }

    /** The removal generation of a sign-in identity when a sign-in starts (pi 1001g P1). */
    private suspend fun removalTicketOf(serverUrl: String, database: String, username: String): Long =
        selectionMutex.withLock { removalGenerations[identityKey(serverUrl, database, username)] ?: 0L }

    /** Caller holds [selectionMutex]: no logout / removal of this identity started since [ticket]. */
    private fun identityUnremovedLocked(serverUrl: String, database: String, username: String, ticket: Long): Boolean {
        val key = identityKey(serverUrl, database, username)
        return key !in identitiesBeingRemoved && (removalGenerations[key] ?: 0L) == ticket
    }

    /** pi 1001g P1: a logout / removal supersedes every selection in flight and tombstones [account]'s identity. */
    private suspend fun beginIdentityRemoval(account: OdooAccount): Long = selectionMutex.withLock {
        val key = identityKey(account.serverUrl, account.database, account.username)
        identitiesBeingRemoved += key
        removalGenerations[key] = ++removalCounter
        ++selectionAttempt
    }

    private suspend fun endIdentityRemoval(account: OdooAccount) {
        withContext(NonCancellable) {
            selectionMutex.withLock {
                val key = identityKey(account.serverUrl, account.database, account.username)
                identitiesBeingRemoved -= key
                // Bumped again: a sign-in that started DURING the removal cannot commit either.
                removalGenerations[key] = ++removalCounter
            }
        }
    }

    /**
     * D1 (iOS parity, both brands): removes [account]'s sessions from this device and revokes them on the
     * server. Runs BEFORE the account row is deleted so the WebView cleanup is ordered before the UI
     * shows the next account.
     *
     * - WebView: cookies only when [account] is the displayed (active) one or none remains; site storage
     *   of its origin only when no remaining account uses that origin (Android keeps it per origin).
     * - Native jar (keyed by host): cleared only when it is not a same-host sibling's (known, or the
     *   displayed sibling's WebView session) and either no sibling shares the host, [account] is the
     *   displayed one (the jar carries the displayed account's session), or the jar session is proven
     *   [account]'s (its own record, or the server says it is its uid AND db). An unproven session is
     *   left in place while a sibling may own it (pi 0930 F5 P1: a record can be missing — e.g. accounts
     *   signed in before records were kept — or stale after a self-heal); server revoke covers it.
     * - Server: every session known to be this account's is revoked in the background; a session that
     *   may be a sibling's is never revoked.
     */
    private suspend fun removeAccountSessions(account: OdooAccount) {
        val url = account.fullServerUrl
        val remaining = accountDao.getAllAccountsList().filter { it.id != account.id }
        val host = sessionHostOf(url)
        val sameHost = remaining.filter { sessionHostOf(it.fullServerUrl) == host }
        val displayedSibling = sameHost.firstOrNull { it.isActive }
        val siblingSessions = sameHost.mapNotNull { knownSessions[it.id] }.toSet() +
            listOfNotNull(displayedSibling?.let { webDataCleaner?.webViewSessionIdOf(it.id, it.fullServerUrl) })
        val webSession = webDataCleaner?.webViewSessionIdOf(account.id, url)
        // Forgotten only with the account row ([deleteAccountAndRecord], pi 1001c P2).
        val known = knownSessions[account.id]
        val jarSession = host?.let { odooClient.getSessionId(it) }
        val jarProvenOwn = jarSession != null && jarSession !in siblingSessions &&
            (jarSession == known || jarSession == webSession ||
                (sameHost.isNotEmpty() && !account.isActive && sessionStillBelongs(account, jarSession)))
        val clearJar = host != null && jarSession !in siblingSessions &&
            (sameHost.isEmpty() || account.isActive || jarProvenOwn)
        if (clearJar) odooClient.clearCookies(host!!)
        webDataCleaner?.removeAccountData(
            account.id, url,
            WebDataRemoval(
                cookies = remaining.isEmpty() || account.isActive,
                originStorage = remaining.none { webOriginOf(it.fullServerUrl) == webOriginOf(url) },
                everything = remaining.isEmpty(),
            ),
        )
        val revoke = buildSet {
            known?.let(::add)
            webSession?.let(::add)
            if (jarSession != null && (jarProvenOwn || sameHost.isEmpty())) add(jarSession)
        } - siblingSessions
        revoke.filter { validApporoSession(it) }.forEach { revokeLater(url, it) }
    }

    /**
     * Only local writes are cancellation-protected. Push locks/network remain cancellable outside.
     * [onPublished] (session bookkeeping: record + revoke of the replaced session) runs in the same
     * non-cancellable boundary right after publication, so a cancellation can no longer land between
     * "published" and "recorded" (pi 0930 F5 P2).
     */
    private suspend fun commitApporoSelection(
        target: OdooAccount,
        original: OdooAccount?,
        password: String?,
        sessionId: String,
        forgetPassword: Boolean = false,
        onPublished: () -> Unit,
        writeAccount: suspend () -> Unit,
    ) {
        val touchesPassword = password != null || forgetPassword
        val previous = accountDao.getActiveAccountOnce()
        val previousPassword = encryptedPrefs.getPassword(target.id)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                accountDao.deactivateAllAccounts()
                writeAccount()
                if (password != null) encryptedPrefs.savePassword(target.id, password)
                else if (forgetPassword) encryptedPrefs.removePassword(target.id)
                // Last local operation: publication validates before its single in-memory assignment.
                if (brand.isApporo) odooClient.publishApporoSession(target.fullServerUrl, sessionId)
                else odooClient.publishSession(target.fullServerUrl, sessionId)
            } catch (failure: Exception) {
                // No cookie has been published on a failed local commit. Restore rows/credentials
                // before releasing the selection fence, including a newly inserted login account.
                if (original == null) accountDao.deleteAccountById(target.id)
                else accountDao.insertAccount(original)
                accountDao.deactivateAllAccounts()
                if (previous != null) accountDao.activateAccount(previous.id)
                if (touchesPassword) {
                    if (previousPassword == null) encryptedPrefs.removePassword(target.id)
                    else encryptedPrefs.savePassword(target.id, previousPassword)
                }
                throw failure
            }
            onPublished()
        }
        currentCoroutineContext().ensureActive()
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
