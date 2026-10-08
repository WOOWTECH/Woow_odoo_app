"""Brand offline contracts (phase-3 approved push-only deltas). No Gradle, dotenv, device, subprocess services or network."""
import ast
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASELINE = '73ad528'
sys.path.insert(0, str(ROOT / "scripts"))
from brand_test_target import authorize_live, target_for
from generate_apporo_assets import read_rgba
from validate_brand_config import BrandConfigError, validate_client, validate_file

K = ROOT / "app/src/main/java/io/woowtech/odoo"
BRAND = (K / "brand/AppBrand.kt").read_text()
GRADLE = (ROOT / "app/build.gradle.kts").read_text()
SCRIPTS = ("verify-on-device.py", "e2e-production-test.py", "e2e_15_clockin_full.py",
           "e2e_hprime_android.py", "e2e_hprime_android_chaos.py", "e2e-verification-report.py")


def strings(brand, locale):
    path = ROOT / f"app/src/{brand}/res/{locale}/strings.xml"
    return {node.attrib["name"]: node.text for node in ET.parse(path).getroot()}


def baseline(path):
    return subprocess.check_output(["git", "show", f"{BASELINE}:{path}"], cwd=ROOT)


def fixture(brand, build_type):
    # In-memory, deliberately invalid-for-service test data. Never written to a config path.
    target = target_for(brand + build_type.title())
    return {"project_info": {"project_id": target.firebase_project, "project_number": "123"},
            "client": [{"client_info": {"android_client_info": {"package_name": target.package},
                                       "mobilesdk_app_id": "1:123:android:test-only"},
                        "api_key": [{"current_key": "unit-test-only-not-a-real-api-key"}]}]}


# W1-10 (owner-approved 2026-09-26): "Remember me" off must not persist a password; the active
# account stays deep-link routable without one. Each pair is (current text, baseline text) and must
# occur exactly once; everything else in these files keeps its byte-level baseline gate.
W1_10_MAIN_ACTIVITY = (
    ('            // "Remember me" off leaves no stored password; the active account still has its session.\n'
     '            val activeAccountId = accounts.firstOrNull { it.isActive }?.id\n', ''),
    ('isLoggedIn = { accountRepository.canRouteDeepLink(it, activeAccountId) },',
     'isLoggedIn = { accountRepository.isLoggedIn(it) },'),
)
# External links (owner-approved 2026-09-29, RELEASE-MASTER-PLAN「擁有者決定（2026-09-29）」: Android supports
# `<brand scheme>://open?url=<encoded>` like iOS `odooApp.handleIncomingURL`, shared by both brands). The only
# approved MainActivity seam: a VIEW intent without a notification payload is handed to ExternalLinkIntake.
# Each pair is (current text, baseline text) and must occur exactly once; the push path and every other
# byte of MainActivity keep the baseline gate below.
EXTERNAL_LINK_MAIN_ACTIVITY = (
    ('import io.woowtech.odoo.data.push.DeepLinkValidator\n'
     'import io.woowtech.odoo.data.push.ExternalLinkIntake\n',
     'import io.woowtech.odoo.data.push.DeepLinkValidator\n'),
    ('    @Inject lateinit var settingsRepository: SettingsRepository\n'
     '    @Inject lateinit var externalLinkIntake: ExternalLinkIntake\n',
     '    @Inject lateinit var settingsRepository: SettingsRepository\n'),
    ('val actionUrl = intent?.getStringExtra(NotificationHelper.EXTRA_ACTION_URL) ?: return handleExternalLink(intent)\n',
     'val actionUrl = intent?.getStringExtra(NotificationHelper.EXTRA_ACTION_URL) ?: return\n'),
    ('    /**\n'
     '     * Owner-approved 2026-09-29 (iOS `handleIncomingURL` parity): a VIEW intent\n'
     '     * `<brand scheme>://open?url=<encoded>` without a notification payload. [ExternalLinkIntake]\n'
     '     * validates it against the active account and queues it for the existing load-gated WebView\n'
     '     * apply flow. Cold start (onCreate) and warm start (onNewIntent, singleTask) both reach here\n'
     '     * through [handleDeepLinkIntent].\n'
     '     */\n'
     '    private fun handleExternalLink(intent: Intent?) {\n'
     '        if (intent?.action != Intent.ACTION_VIEW) return\n'
     '        activityScope.launch(Dispatchers.IO) { externalLinkIntake.accept(intent) }\n'
     '    }\n'
     '\n', ''),
)
# Push-tap link validation (owner-approved 2026-09-29, coordination/.../OWNER-APPROVAL-PUSH-LINK-20260929.md and
# RELEASE-MASTER-PLAN「擁有者決定（2026-09-29）」): the shared DeepLinkValidator is tightened to iOS
# `DeepLinkValidator.isValid` (absolute URLs https + same host; control/format characters and `..` / `%2e%2e`
# in any case or half-encoded rejected). Only for the push-tap/external-link validation path; each pair is
# (current text, baseline text) and must occur exactly once, every other byte keeps the baseline gate below.
PUSH_LINK_VALIDATOR = (
    (' *\n'
     ' * Owner-approved 2026-09-29 (OWNER-APPROVAL-PUSH-LINK-20260929, iOS `DeepLinkValidator.isValid`\n'
     ' * parity): shared by notification taps and external `?url=` links. Absolute URLs must be `https`\n'
     ' * on the active server\'s host; control/format characters and `..` / `%2e%2e` (any case, also\n'
     ' * half-encoded) are rejected. Never looser than iOS; whitespace is rejected rather than trimmed.\n', ''),
    ('    /** `..` raw, percent-encoded or half-encoded (WHATWG treats all of these as a parent segment). */\n'
     '    private val TRAVERSAL = Regex("""(\\.|%2e)(\\.|%2e)""", RegexOption.IGNORE_CASE)\n'
     '\n', ''),
    ('        // iOS parity: encoded traversal, control (Cc) / format (Cf) characters and padding.\n'
     '        if (TRAVERSAL.containsMatchIn(url) || hasControlOrFormat(url) || url != url.trim()) {\n'
     '            return false\n'
     '        }\n'
     '\n', ''),
    ('            if (serverHost.isBlank() || !parsed.scheme.equals("https", ignoreCase = true)) return false\n', ''),
    ('\n'
     '    private fun hasControlOrFormat(url: String): Boolean {\n'
     '        var i = 0\n'
     '        while (i < url.length) {\n'
     '            val cp = url.codePointAt(i)\n'
     '            val type = Character.getType(cp)\n'
     '            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) return true\n'
     '            i += Character.charCount(cp)\n'
     '        }\n'
     '        return false\n'
     '    }\n', ''),
)
# pi review P2 (PI-REVIEW-0929-ANDROID-IOS-INCREMENTAL, under the 2026-09-29 push-link iOS-parity approval):
# MainActivity's private string-split `serverHost()` kept the port (`example.com:8443`) while DeepLinkValidator
# compares `URI.host`, so same-server https push links on a non-default port were rejected. Both push paths now
# use `OdooAccount.serverHost` (iOS `URL(fullServerUrl).host`). Only these pairs may differ from the baseline.
PUSH_LINK_SERVER_HOST_MAIN_ACTIVITY = (
    ('serverHost = account.serverHost,', 'serverHost = account.serverHost(),'),
    ('serverHost = active.serverHost)', 'serverHost = active.serverHost())'),
    ('            Timber.w("Rejected invalid deep link")\n'
     '        }\n'
     '    }\n'
     '}\n',
     '            Timber.w("Rejected invalid deep link")\n'
     '        }\n'
     '    }\n'
     '\n'
     '    /** Bare host of this account\'s server, with scheme and path stripped. */\n'
     '    private fun io.woowtech.odoo.domain.model.OdooAccount.serverHost(): String =\n'
     '        serverUrl.removePrefix("https://").removePrefix("http://").split("/").first()\n'
     '}\n'),
)
W1_10_ACCOUNT_WOOW = (
    ('    /**\n'
     '     * Deep-link routing predicate. The ACTIVE account is usable through its live session even when\n'
     '     * "Remember me" was off (no stored password); any other account must be switched to, which needs\n'
     '     * a stored password.\n'
     '     */\n'
     '    fun canRouteDeepLink(accountId: String, activeAccountId: String?): Boolean =\n'
     '        accountId == activeAccountId || isLoggedIn(accountId)\n'
     '\n'
     '    /**\n'
     '     * @param rememberPassword the login screen\'s "Remember me". When false the password is NOT\n'
     '     * persisted and any password previously remembered for this same account is removed, so no\n'
     '     * reusable secret outlives the session (silent re-auth / switching back will ask to sign in).\n'
     '     */\n', ''),
    ('        password: String,\n        rememberPassword: Boolean = true,\n    ): AuthResult {',
     '        password: String\n    ): AuthResult {'),
    ('            // Save password securely only when the user asked us to remember it (W1-10).\n'
     '            if (rememberPassword) encryptedPrefs.savePassword(account.id, password)\n'
     '            else encryptedPrefs.removePassword(account.id)\n',
     '            // Save password securely\n'
     '            encryptedPrefs.savePassword(account.id, password)\n'),
)

# pi 1001f P1/P2 (2026-10-02): a WOOW sign-in / switch takes the Apporo selection fence and publishes its session
# to the jar, records it and revokes the target's replaced session only at one non-cancellable commit boundary
# (attempt still current, target unchanged, rows written); a loser revokes only its own unpublished session and
# a failed switch registers the still-displayed previous account's push device again. Same (current, baseline);
# applied first, it restores the bf2d0d3 text the older tuples below expect.
F5G_ACCOUNT_WOOW = (
    (
     '        if (brand.isApporo) return authenticateApporo(serverUrl, database, username, password, rememberPassword)\n'
     '        // pi 1001f P1: the Apporo selection fence — a sign-in commits only while it is still the newest selection.\n'
     '        val attempt = beginTrackedSelection()\n'
     '        try {\n'
     '            return authenticateWoow(attempt, serverUrl, database, username, password, rememberPassword)\n'
     '        } finally {\n'
     '            endSelection(attempt)\n'
     '        }\n'
     '    }\n'
     '\n'
     '    private suspend fun authenticateWoow(\n'
     '        attempt: Long,\n'
     '        serverUrl: String,\n'
     '        database: String,\n'
     '        username: String,\n'
     '        password: String,\n'
     '        rememberPassword: Boolean,\n'
     '    ): AuthResult {\n'
     '        invalidateHeals()\n'
     '        val fullUrl = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"\n'
     '        val removalTicket = removalTicketOf(fullUrl, database, username)\n'
     "        // pi 0930b P2: keep the displayed account's live WebView session so a later promotion can reuse it.\n"
     ,
     '        if (brand.isApporo) return authenticateApporo(serverUrl, database, username, password, rememberPassword)\n'
     '        invalidateHeals()\n'
     '        val fullUrl = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"\n'
     "        // pi 0930b P2: keep the displayed account's live WebView session so a later promotion can reuse it.\n"
    ),
    (
     '        if (result is AuthResult.Success) {\n'
     '            var published = false\n'
     '            val committed = try {\n'
     '                selectionMutex.withLock {\n'
     '                    if (attempt != selectionAttempt) return@withLock false\n'
     '                    // pi 1001g P1: nor may it recreate an account whose logout / removal started after it did.\n'
     '                    if (!identityUnremovedLocked(fullUrl, database, username, removalTicket)) return@withLock false\n'
     '                    // Check if account already exists\n'
     '                    val existingAccount = accountDao.findAccount(fullUrl, database, username)\n'
     '\n'
     '                    val account = existingAccount?.copy(\n'
     '                        displayName = result.displayName,\n'
     '                        userId = result.userId,\n'
     '                        lastLogin = System.currentTimeMillis(),\n'
     '                        isActive = true\n'
     '                    ) ?: OdooAccount(\n'
     '                        serverUrl = fullUrl,\n'
     '                        database = database,\n'
     '                        username = username,\n'
     '                        displayName = result.displayName,\n'
     '                        userId = result.userId,\n'
     '                        isActive = true\n'
     '                    )\n'
     '\n'
     '                    // pi 1001f P1: rows, password (only when remembered, W1-10), jar and record are written at one\n'
     '                    // non-cancellable boundary; the session reaches the jar only once the rows are written.\n'
     '                    commitApporoSelection(account, existingAccount, password.takeIf { rememberPassword }, result.sessionId,\n'
     '                        forgetPassword = !rememberPassword,\n'
     '                        onPublished = {\n'
     '                            published = true\n'
     '                            knownSessions[account.id] = result.sessionId\n'
     '                        },\n'
     '                    ) {\n'
     '                        accountDao.insertAccount(account)\n'
     '                    }\n'
     '                    fcmTokenRepository?.onManualLogin(account.id, result.sessionId)\n'
     '                    sessionReauthenticator?.onManualReloginSucceeded(account.id)\n'
     '                    true\n'
     '                }\n'
     '            } finally {\n'
     '                // Minted but never published (superseded, failed or cancelled): nothing on this device will use it.\n'
     '                if (!published) revokeLater(fullUrl, result.sessionId)\n'
     '            }\n'
     '            if (!committed) {\n'
     '                return AuthResult.Error("Sign-in superseded by a newer selection", AuthResult.ErrorType.UNKNOWN)\n'
     '            }\n'
     '\n'
     ,
     '        if (result is AuthResult.Success) {\n'
     '            // Check if account already exists\n'
     '            val existingAccount = accountDao.findAccount(fullUrl, database, username)\n'
     '\n'
     '            val account = existingAccount?.copy(\n'
     '                displayName = result.displayName,\n'
     '                userId = result.userId,\n'
     '                lastLogin = System.currentTimeMillis(),\n'
     '                isActive = true\n'
     '            ) ?: OdooAccount(\n'
     '                serverUrl = fullUrl,\n'
     '                database = database,\n'
     '                username = username,\n'
     '                displayName = result.displayName,\n'
     '                userId = result.userId,\n'
     '                isActive = true\n'
     '            )\n'
     '\n'
     '            // Deactivate other accounts and save this one\n'
     '            accountDao.deactivateAllAccounts()\n'
     '            accountDao.insertAccount(account)\n'
     '            knownSessions[account.id] = result.sessionId\n'
     '\n'
     '            // Save password securely only when the user asked us to remember it (W1-10).\n'
     '            if (rememberPassword) encryptedPrefs.savePassword(account.id, password)\n'
     '            else encryptedPrefs.removePassword(account.id)\n'
     '            fcmTokenRepository?.onManualLogin(account.id, result.sessionId)\n'
     '            sessionReauthenticator?.onManualReloginSucceeded(account.id)\n'
     '\n'
    ),
    (
     '        if (brand.isApporo) return switchApporoAccount(accountId)\n'
     '        // pi 1001f P1: the Apporo selection fence — a switch commits only while it is still the newest selection.\n'
     '        val attempt = beginTrackedSelection()\n'
     '        try {\n'
     '            return switchWoowAccount(attempt, accountId)\n'
     '        } finally {\n'
     '            // pi 1001g P2: every exit — including an early one — settles a push re-registration still owed.\n'
     '            endSelection(attempt)\n'
     '        }\n'
     '    }\n'
     '\n'
     '    private suspend fun switchWoowAccount(attempt: Long, accountId: String): Boolean {\n'
     '        invalidateHeals()\n'
     ,
     '        if (brand.isApporo) return switchApporoAccount(accountId)\n'
     '        invalidateHeals()\n'
    ),
    (
     '        //\n'
     '        // pi 1001f/1001g P2: A is first recorded as owed a re-registration; if this switch does not commit, the\n'
     '        // last tracked selection to end with A still displayed registers A again (see endSelection).\n'
     '        if (previousActiveAccountId != null && previousActiveAccountId != accountId) {\n'
     '            fcmTokenRepository?.let { repo ->\n'
     '                pendingPushReRegister += previousActiveAccountId\n'
     '                unregisterPush(repo, previousActiveAccountId)\n'
     '                    .onSuccess {\n'
     ,
     '        //\n'
     "        // Trade-off: if re-auth then fails, A's FCM record is already\n"
     '        // deactivated server-side and the user keeps account A locally but\n'
     "        // won't receive A's notifications until the next successful login or\n"
     '        // FCM token rotation re-registers. This is the lesser of two evils\n'
     "        // versus risking a server-side corruption of B's record.\n"
     '        if (previousActiveAccountId != null && previousActiveAccountId != accountId) {\n'
     '            fcmTokenRepository?.let { repo ->\n'
     '                repo.unregisterToken(previousActiveAccountId)\n'
     '                    .onSuccess {\n'
    ),
    (
     '                odooClient.sessionOwnership(account.fullServerUrl, it, userId, account.database) == SessionOwnership.Belongs\n'
     '        }\n'
     '\n'
     '        // Sign in again only when no stored session is proven; the result is published at the commit below.\n'
     '        val result = if (reused != null) {\n'
     ,
     '                odooClient.sessionOwnership(account.fullServerUrl, it, userId, account.database) == SessionOwnership.Belongs\n'
     '        }\n'
     '        if (reused != null) odooClient.publishSession(account.fullServerUrl, reused)\n'
     '\n'
     '        // Try to re-authenticate (this overwrites the cookie jar for the host)\n'
     '        val result = if (reused != null) {\n'
    ),
    (
     '\n'
     '        // pi 1001f P1: one commit boundary — attempt still current, target unchanged, rows written — then the jar\n'
     '        // and the record. A loser keeps the existing jar and revokes only its own unpublished session.\n'
     '        var published = false\n'
     '        val committed = try {\n'
     '            result is AuthResult.Success && selectionMutex.withLock {\n'
     '                val current = accountDao.getAccountById(accountId)\n'
     '                if (attempt != selectionAttempt || current == null || current.serverUrl != account.serverUrl ||\n'
     '                    current.database != account.database || current.username != account.username ||\n'
     '                    current.userId != account.userId\n'
     '                ) return@withLock false\n'
     '                commitApporoSelection(current, current, null, result.sessionId, onPublished = {\n'
     '                    published = true\n'
     '                    knownSessions[accountId] = result.sessionId\n'
     "                    // pi 1001f P2: the target's previous session is revoked only after the new one is committed,\n"
     "                    // and only when it is positively still the target's and no other account holds it (pi 1001d P1).\n"
     '                    if (reused == null && stored != null && stored != result.sessionId) revokeReplacedIfOwn(current, stored)\n'
     '                }) {\n'
     '                    accountDao.activateAccount(accountId)\n'
     '                    accountDao.updateLastLogin(accountId)\n'
     '                }\n'
     "                // The switch away from A committed: A's unregistration is now intended, nothing is owed to it.\n"
     '                if (previousActiveAccountId != null && previousActiveAccountId != accountId) {\n'
     '                    pendingPushReRegister -= previousActiveAccountId\n'
     '                }\n'
     '                fcmTokenRepository?.onManualLogin(accountId, result.sessionId)\n'
     '                sessionReauthenticator?.onManualReloginSucceeded(accountId)\n'
     '                true\n'
     '            }\n'
     '        } finally {\n'
     '            if (!published && reused == null && result is AuthResult.Success) revokeLater(account.fullServerUrl, result.sessionId)\n'
     '        }\n'
     '\n'
     '        // Same reason as authenticate(): the FCM token may have been\n'
     '        // saved before this account became active. Replay it.\n'
     '        if (committed) registerSavedFcmToken(accountId)\n'
     '        return committed\n'
     '    }\n'
     ,
     '\n'
     '        return if (result is AuthResult.Success) {\n'
     '            knownSessions[accountId] = result.sessionId\n'
     "            // The target's previous session is revoked only after the new one is committed, and only when it is\n"
     "            // positively still the target's and no other account holds it (pi 1001d P1 rule).\n"
     '            if (reused == null && stored != null && stored != result.sessionId) revokeReplacedIfOwn(account, stored)\n'
     '            accountDao.deactivateAllAccounts()\n'
     '            accountDao.activateAccount(accountId)\n'
     '            accountDao.updateLastLogin(accountId)\n'
     '            fcmTokenRepository?.onManualLogin(accountId, result.sessionId)\n'
     '            sessionReauthenticator?.onManualReloginSucceeded(accountId)\n'
     '            // Same reason as authenticate(): the FCM token may have been\n'
     '            // saved before this account became active. Replay it.\n'
     '            registerSavedFcmToken(accountId)\n'
     '            true\n'
     '        } else {\n'
     '            false\n'
     '        }\n'
     '    }\n'
    ),
    (
     '     */\n'
     '    private suspend fun registerSavedFcmToken(accountId: String): Boolean {\n'
     '        val repo = fcmTokenRepository ?: return true\n'
     '        val token = repo.getStoredToken() ?: return true\n'
     '        return repo.registerToken(accountId = accountId, token = token)\n'
     '            .onSuccess { Timber.d("FCM token registered for account %s on login", accountId) }\n'
     ,
     '     */\n'
     '    private suspend fun registerSavedFcmToken(accountId: String) {\n'
     '        val repo = fcmTokenRepository ?: return\n'
     '        val token = repo.getStoredToken() ?: return\n'
     '        repo.registerToken(accountId = accountId, token = token)\n'
     '            .onSuccess { Timber.d("FCM token registered for account %s on login", accountId) }\n'
    ),
    (
     '            }\n'
     '            .isSuccess\n'
     '    }\n'
     ,
     '            }\n'
     '    }\n'
    ),
    (
     '        val account = accountDao.getAccountById(id) ?: return false\n'
     '        // pi 1001g P1: before any network wait, supersede every sign-in / switch in flight and tombstone this\n'
     '        // identity, so none of them can recreate or reactivate the account being logged out.\n'
     '        val attempt = beginIdentityRemoval(account)\n'
     '        try {\n'
     '            return logoutAccount(account)\n'
     '        } finally {\n'
     '            endIdentityRemoval(account, attempt)\n'
     '        }\n'
     '    }\n'
     '\n'
     '    private suspend fun logoutAccount(account: OdooAccount): Boolean {\n'
     '        val id = account.id\n'
     '        val wasActive = account.isActive\n'
     ,
     '        val account = accountDao.getAccountById(id) ?: return false\n'
     '        val wasActive = account.isActive\n'
    ),
    (
     '        fcmTokenRepository?.let { repo ->\n'
     '            unregisterPush(repo, id)\n'
     '                .onSuccess { Timber.d("FCM token unregistered for account %s before logout", id) }\n'
     ,
     '        fcmTokenRepository?.let { repo ->\n'
     '            repo.unregisterToken(id)\n'
     '                .onSuccess { Timber.d("FCM token unregistered for account %s before logout", id) }\n'
    ),
    (
     '        }\n'
     '        pendingPushReRegister -= id\n'
     '        fcmTokenRepository?.forgetAccount(id)\n'
     ,
     '        }\n'
     '        fcmTokenRepository?.forgetAccount(id)\n'
    ),
    (
     '    suspend fun removeAccount(accountId: String) {\n'
     '        // pi 1001g P1: same removal fence as logout, before any network wait.\n'
     '        val target = accountDao.getAccountById(accountId)\n'
     '        val attempt = if (target != null) beginIdentityRemoval(target) else beginTrackedSelection()\n'
     '        try {\n'
     '            removeAccountRow(accountId)\n'
     '        } finally {\n'
     '            if (target != null) endIdentityRemoval(target, attempt) else endSelection(attempt)\n'
     '        }\n'
     '    }\n'
     '\n'
     '    private suspend fun removeAccountRow(accountId: String) {\n'
     '        // Best-effort FCM unregister before local deletion. If the device\n'
     ,
     '    suspend fun removeAccount(accountId: String) {\n'
     '        // Best-effort FCM unregister before local deletion. If the device\n'
    ),
    (
     '        fcmTokenRepository?.let { repo ->\n'
     '            unregisterPush(repo, accountId)\n'
     '                .onSuccess { Timber.d("FCM token unregistered for account %s before removal", accountId) }\n'
     ,
     '        fcmTokenRepository?.let { repo ->\n'
     '            repo.unregisterToken(accountId)\n'
     '                .onSuccess { Timber.d("FCM token unregistered for account %s before removal", accountId) }\n'
    ),
    (
     '        }\n'
     '        pendingPushReRegister -= accountId\n'
     '        fcmTokenRepository?.forgetAccount(accountId)\n'
     ,
     '        }\n'
     '        fcmTokenRepository?.forgetAccount(accountId)\n'
    ),
)
# Android F5 (2026-09-30, iOS demo111 D1/D2 parity): logout / account removal wipes only that account's
# WebView data and native session and revokes its sessions server-side; an Odoo 18 AccessDenied is a
# wrong password and a non-JSON 200 a localized server error. Same (current, baseline) contract as
# W1-10: exactly these insertions/replacements, the rest of each file stays byte-level.
# pi 1001c (2026-10-02): logout / removal run their local cleanup in one non-cancellable boundary and forget
# the session record only together with the account row (deleteAccountAndRecord). Same (current, baseline)
# contract; applied before F5_ACCOUNT_WOOW, which then restores the pre-D1 baseline.
# pi 1001d P1 (2026-10-02): a WOOW manual sign-in / switch supersedes every self-heal in flight
# (invalidateHeals) so an older heal can no longer replace the winner's session. Same (current, baseline).
# Live 1001e W3 (2026-10-02): a WOOW switch reuses the target's known session when the server proves it is
# that uid AND db (Apporo parity), and revokes the target's replaced session only after the new one is
# committed and only when it is positively the target's and held by no other account. Same (current, baseline).
F5F_ACCOUNT_WOOW = (
    (
     "        // Live 1001e W3 (Apporo parity): reuse the target's known session when the server proves it is this uid\n"
     '        // AND db, instead of signing in again on every switch and orphaning the session it replaces.\n'
     '        val stored = knownSessions[accountId]\n'
     '        val userId = account.userId\n'
     '        val reused = stored?.takeIf {\n'
     '            userId != null && validApporoSession(it) &&\n'
     '                odooClient.sessionOwnership(account.fullServerUrl, it, userId, account.database) == SessionOwnership.Belongs\n'
     '        }\n'
     '        if (reused != null) odooClient.publishSession(account.fullServerUrl, reused)\n'
     '\n'
     '        // Try to re-authenticate (this overwrites the cookie jar for the host)\n'
     '        val result = if (reused != null) {\n'
     '            AuthResult.Success(userId!!, reused, account.username, account.displayName)\n'
     '        } else odooClient.authenticate(\n'
     '            account.fullServerUrl,\n'
     '            account.database,\n'
     '            account.username,\n'
     '            password\n'
     '        )\n'
     '\n'
     '        return if (result is AuthResult.Success) {\n'
     '            knownSessions[accountId] = result.sessionId\n'
     "            // The target's previous session is revoked only after the new one is committed, and only when it is\n"
     "            // positively still the target's and no other account holds it (pi 1001d P1 rule).\n"
     '            if (reused == null && stored != null && stored != result.sessionId) revokeReplacedIfOwn(account, stored)\n'
     '            accountDao.deactivateAllAccounts()\n'
     ,
     '        // Try to re-authenticate (this overwrites the cookie jar for the host)\n'
     '        val result = odooClient.authenticate(\n'
     '            account.fullServerUrl,\n'
     '            account.database,\n'
     '            account.username,\n'
     '            password\n'
     '        )\n'
     '\n'
     '        return if (result is AuthResult.Success) {\n'
     '            knownSessions[accountId] = result.sessionId\n'
     '            accountDao.deactivateAllAccounts()\n'
    ),
)
F5E_ACCOUNT_WOOW = (
    ('        invalidateHeals()\n'
     '        val fullUrl = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"\n',
     '        val fullUrl = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"\n'),
    ('        invalidateHeals()\n'
     '        val account = accountDao.getAccountById(accountId) ?: return false\n',
     '        val account = accountDao.getAccountById(accountId) ?: return false\n'),
)
F5D_ACCOUNT_WOOW = (
    ('        // pi 1001c P2: one non-cancellable boundary, and the session record is forgotten only together with the\n'
     '        // account row, so a cancellation can never leave the account without the record needed to clean it up.\n'
     '        withContext(NonCancellable) {\n'
     '            beginRemoval(id)\n'
     '            try {\n'
     '                removeAccountSessions(account)\n'
     '                encryptedPrefs.removePassword(id)\n'
     '                deleteAccountAndRecord(id)\n'
     '            } finally {\n'
     '                removing -= id\n'
     '            }\n'
     '        }\n',
     '        removeAccountSessions(account)\n'
     '\n'
     '        // Remove password\n'
     '        encryptedPrefs.removePassword(id)\n'
     '\n'
     '        // Delete account from database\n'
     '        accountDao.deleteAccountById(id)\n'),
    ('        // D1 (iOS parity): the removed account\'s sessions are wiped and revoked like on logout (same boundary).\n'
     '        withContext(NonCancellable) {\n'
     '            beginRemoval(accountId)\n'
     '            try {\n'
     '                accountDao.getAccountById(accountId)?.let { removeAccountSessions(it) }\n'
     '                encryptedPrefs.removePassword(accountId)\n'
     '                deleteAccountAndRecord(accountId)\n'
     '            } finally {\n'
     '                removing -= accountId\n'
     '            }\n'
     '        }\n',
     '        // D1 (iOS parity): the removed account\'s sessions are wiped and revoked like on logout.\n'
     '        accountDao.getAccountById(accountId)?.let { removeAccountSessions(it) }\n'
     '        encryptedPrefs.removePassword(accountId)\n'
     '        accountDao.deleteAccountById(accountId)\n'),
)
KNOWN_SESSIONS_EXCLUDE = '<exclude domain="sharedpref" path="known_sessions.xml" />'
BACKUP_EXCLUSION_RULES = {'app/src/main/res/xml/backup_rules.xml': 1,
                          'app/src/main/res/xml/data_extraction_rules.xml': 2}
F5_ACCOUNT_WOOW = (
    ('        // D1 (iOS parity): wipe only this account\'s WebView data and native session, then revoke it server-side.\n'
     '        removeAccountSessions(account)\n',
     '        // Clear cookies\n'
     '        val host = account.fullServerUrl.removePrefix("https://").split("/").first()\n'
     '        odooClient.clearCookies(host)\n'),
    ('        // D1 (iOS parity): the removed account\'s sessions are wiped and revoked like on logout.\n'
     '        accountDao.getAccountById(accountId)?.let { removeAccountSessions(it) }\n', ''),
    ('            // D5 (iOS parity): hand the promoted account its still-valid session before it is shown.\n'
     '            publishKnownSession(remaining.first())\n', ''),
)
F5_WOOW_API = (
    ('                    isAccessDenied(response.error.data?.name, errorMessage) ->\n'
     '                        AuthResult.Error(errorMessage, AuthResult.ErrorType.INVALID_CREDENTIALS)\n', ''),
    ('        } catch (e: InvalidSignInResponseException) {\n'
     '            AuthResult.Error("Invalid sign-in response", AuthResult.ErrorType.SERVER_ERROR)\n', ''),
    ('        return try {\n'
     '            gson.fromJson(responseBody, JsonRpcResponse::class.java)\n'
     '        } catch (e: JsonParseException) {\n'
     '            null\n'
     '        } ?: throw InvalidSignInResponseException()\n',
     '        return gson.fromJson(responseBody, JsonRpcResponse::class.java)\n'),
    ('import com.google.gson.JsonParseException\n', ''),
    # pi 0930 F5 P2: a JSON 200 without a JSON-RPC result/error is a server error, not a wrong password.
    ('            val result = response.result\n'
     '                ?: return@withContext AuthResult.Error("Invalid sign-in response", AuthResult.ErrorType.SERVER_ERROR)\n'
     '            if (!result.has("uid") || result.get("uid").isJsonNull) {\n',
     '            val result = response.result\n'
     '            if (result == null || !result.has("uid") || result.get("uid").isJsonNull) {\n'),
)
# Android 1001 (demo111 live B4, 2026-09-30): a WOOW sign-in never carries the host's stored session_id —
# Odoo re-authenticated that session as the new user and rotated it, killing the other account's server
# session. The sign-in jar still saves the new session; it just sends none. Same (current, baseline) contract.
WOOW_SIGNIN_NO_SESSION_API = (
    ('        // 1001 (demo111 B4): a sign-in never sends another account\'s session_id; Odoo would\n'
     '        // re-authenticate that session as the new user and rotate it away.\n'
     '        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()\n',
     '        override fun loadForRequest(url: HttpUrl): List<Cookie> {\n'
     '            return cookieStore[url.host] ?: emptyList()\n'
     '        }\n'),
)
# pi 0930b Android P1 (2026-10-01): a WOOW sign-in takes its session only from THIS response's live
# `Set-Cookie: session_id` (iOS isolated response-cookie rule) and keeps that response's cookies only after
# the sign-in succeeded; the host jar's other-account session can no longer become the sign-in result, and
# a failed / cookie-less answer no longer replaces it. Same (current, baseline) contract.
# pi 1001c P1 (2026-10-02): a top-level tri-state SessionOwnership answer next to the JSON-RPC types; only a
# server answer is evidence, never a failure to ask. Same (current, baseline) contract.
SESSION_OWNERSHIP_API = (
    (
     "/** pi 1001c P1: the server's answer about a session's owner; see [OdooJsonRpcClient.sessionOwnership]. */\n"
     'sealed interface SessionOwnership {\n'
     "    /** The server answered: this session is the account's (uid AND db). */\n"
     '    object Belongs : SessionOwnership\n'
     "    /** The server answered: another uid/db, or the session is expired. Not the account's — and maybe someone else's. */\n"
     '    object ProvenMismatch : SessionOwnership\n'
     '    /** No usable answer (offline, timeout, non-200, unparsable, db missing): proves nothing either way. */\n'
     '    object Unknown : SessionOwnership\n'
     '}\n'
     '\n'
     'data class JsonRpcRequest(',
     'data class JsonRpcRequest('),
)
WOOW_RESPONSE_SID_API = (
    # pi 1001b P1 (2026-10-01): the WOOW sign-in client no longer follows redirects (Apporo isolated parity),
    # and the session is parsed against the URL that actually answered; another host is a failure.
    ('        .cookieJar(cookieJar)\n'
     '        // pi 1001b P1: a sign-in never follows a redirect (Apporo isolated parity); a 3xx is a failure.\n'
     '        .followRedirects(false).followSslRedirects(false)\n',
     '        .cookieJar(cookieJar)\n'),
    ('        // pi 0930b P1: a sign-in response\'s cookies are kept only once that sign-in succeeded (see\n'
     '        // [authenticate]); a failed or cookie-less answer never replaces another account\'s session.\n'
     '        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit\n',
     '        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {\n'
     '            cookieStore.getOrPut(url.host) { mutableListOf() }.apply {\n'
     '                clear()\n'
     '                addAll(cookies)\n'
     '            }\n'
     '        }\n'),
    ('            val signIn = executeRequest(url, requestBody)\n'
     '            val response = signIn.body\n',
     '            val response = executeRequest(url, requestBody)\n'),
    ('            // pi 0930b P1 (iOS isolated response-cookie rule): only THIS response\'s session counts; the\n'
     '            // host jar may still hold another account\'s session, which must never become this sign-in\'s.\n'
     '            val sessionId = signIn.sessionId\n'
     '                ?: return@withContext AuthResult.Error("Sign-in session was not established", AuthResult.ErrorType.SESSION_EXPIRED)\n'
     # pi 1001f P1: the repository publishes this session at its selection commit; the client no longer does.
     '            // pi 1001f P1: nothing is published here; the repository publishes at its selection commit.\n',
     '            val sessionId = getSessionId(extractHost(serverUrl)) ?: ""\n'),
    # SignInResponse itself sits in the Apporo-isolated span stripped above (just before executeRequest).
    ('    private fun executeRequest(url: String, body: JsonRpcRequest): SignInResponse {\n',
     '    private fun executeRequest(url: String, body: JsonRpcRequest): JsonRpcResponse {\n'),
    ('        // pi 1001b P1: cookies belong to the URL that actually answered; a different host is no sign-in.\n'
     '        val answered = response.request.url\n'
     '        if (answered.host != request.url.host) {\n'
     '            response.close()\n'
     '            throw SignInHttpStatusException(response.code)\n'
     '        }\n'
     '        val now = System.currentTimeMillis()\n'
     '        val cookies = Cookie.parseAll(answered, response.headers).filter { it.matches(answered) && it.expiresAt > now }\n'
     '        val sessionId = cookies.firstOrNull { it.name == "session_id" }\n'
     '            ?.value?.takeIf { sid -> sid.isNotBlank() && sid.none { it <= \' \' || it == \';\' || it >= \'\\u007f\' } }\n'
     '\n'
     '        val parsed = try {\n',
     '\n'
     '        return try {\n'),
    ('        } ?: throw InvalidSignInResponseException()\n'
     '        return SignInResponse(parsed, request.url.host, cookies, sessionId)\n',
     '        } ?: throw InvalidSignInResponseException()\n'),
    ('    private class SignInHttpStatusException(val code: Int) : Exception("HTTP $code")\n'
     '}\n',
     '    private class SignInHttpStatusException(val code: Int) : Exception("HTTP $code")\n'
     '\n'
     '    private fun extractHost(url: String): String {\n'
     '        return url.removePrefix("https://").removePrefix("http://").split("/").first()\n'
     '    }\n'
     '}\n'),
)
# pi 0930b Android P2 (2026-10-01): WOOW records its own sign-in sessions and remembers the displayed
# account's WebView session before a sign-in / switch replaces it, so a promotion after a logout can hand
# the promoted account its still-valid session (publishKnownSession, now for both brands) instead of a new
# sign-in that orphaned the original on the server. Same (current, baseline) contract.
F5B_ACCOUNT_WOOW = (
    ('        // pi 0930b P2: keep the displayed account\'s live WebView session so a later promotion can reuse it.\n'
     '        accountDao.getActiveAccountOnce()?.id?.let { rememberWebViewSession(it) }\n', ''),
    ('            accountDao.insertAccount(account)\n'
     '            knownSessions[account.id] = result.sessionId\n',
     '            accountDao.insertAccount(account)\n'),
    ('        if (previousActiveAccountId != null && previousActiveAccountId != accountId) rememberWebViewSession(previousActiveAccountId)\n', ''),
    ('            knownSessions[accountId] = result.sessionId\n'
     '            accountDao.deactivateAllAccounts()\n',
     '            accountDao.deactivateAllAccounts()\n'),
)
# pi 1001b Android P2 (2026-10-01): the repository's known sessions are written through to an encrypted
# store (EncryptedKnownSessionStore) so a promotion after an app restart can reuse or revoke them, and the
# native self-heal reports the session it established. Same (current, baseline) contract.
F5C_APP_MODULE = (
    ('        knownSessionStore: io.woowtech.odoo.data.local.EncryptedKnownSessionStore,\n', ''),
    ('            // pi 1001b P2: known sessions survive a restart (encrypted) and self-heal sessions are recorded.\n'
     '            repo.knownSessionStore = knownSessionStore\n'
     '            sessionReauthenticator.healCommitter = repo\n', ''),
)
F5_APP_MODULE = (
    ('        accountWebDataCleaner: io.woowtech.odoo.ui.main.AndroidAccountWebDataCleaner,\n', ''),
    ('            // D1 (iOS parity): logout / removal wipes the account\'s WebView data.\n'
     '            repo.webDataCleaner = accountWebDataCleaner\n', ''),
)

# Server-error status (owner-approved 2026-09-26, iOS b9ebd0d parity): a non-200 sign-in response on
# the WOOW path keeps its HTTP status for the localized `error_server_http` login text instead of
# being parsed as JSON. Same (current, baseline) contract as W1-10; the rest stays byte-level.
SERVER_HTTP_STATUS_WOOW_API = (
    ('        } catch (e: SignInHttpStatusException) {\n'
     '            signInHttpStatus(e.code)\n', ''),
    ('        if (response.code != 200) {\n'
     '            response.close()\n'
     '            throw SignInHttpStatusException(response.code)\n'
     '        }\n', ''),
    ('    /** Status only; LoginScreen renders the localized `error_server_http` text (iOS parity). */\n'
     '    private fun signInHttpStatus(code: Int) =\n'
     '        AuthResult.Error("HTTP $code", AuthResult.ErrorType.SERVER_ERROR, httpStatus = code)\n'
     '\n'
     '    private class SignInHttpStatusException(val code: Int) : Exception("HTTP $code")\n'
     '\n', ''),
)
SERVER_HTTP_STATUS_STRINGS = {'error_server_http'}

# LIVE-0927 Android r2 (owner-requested 2026-09-27, iOS 32462a3 parity): new three-locale keys.
# Same additive contract as above — every baseline key keeps its exact value.
LIVE_0927_R2_STRINGS = {'app_lock_disable_pin_subtitle', 'language_system', 'enter_pin_subtitle'}
# LIVE-0927 Android r3 (owner-requested 2026-09-27): changing an existing PIN first asks for the
# current one. Same additive contract.
LIVE_0927_R3_STRINGS = {'change_pin_verify_subtitle'}
# PIN lockout (2026-09-28, verify-20260928 Android defect): the PIN screen counts the lockout down
# every second (iOS `lockout_timer_%lld` parity) and brings the keypad back when it ends. Additive
# three-locale <plurals>; zh has only the `other` quantity.
PIN_LOCKOUT_STRINGS = {'pin_lockout_countdown'}
# Remove PIN (2026-09-28, iOS "Remove PIN" parity): the Settings item, its note that App Lock is turned
# off too, and the current-PIN prompt subtitle. Same additive three-locale contract.
REMOVE_PIN_STRINGS = {'remove_pin', 'remove_pin_subtitle', 'remove_pin_verify_subtitle'}
# PIN keypad delete key (2026-09-29, owner-approved batch with external links): its screen-reader label was a
# hardcoded English "Delete"; now a three-locale string. Same additive contract.
PIN_DELETE_KEY_STRINGS = {'pin_delete'}
# Same round: "1 attempts remaining" — these baseline <string>s become <plurals> of the same name.
# The baseline text survives verbatim as the `other` quantity; English adds `one`, zh has only `other`.
PIN_PLURALS_RETYPED = {'wrong_pin_attempts_remaining'}
# W2-4 L7 (owner-approved 2026-10-08, Pixel 7a vc5 acceptance): the file chooser title was a hardcoded
# Chinese "選擇檔案" in every UI language; now a three-locale string. Same additive contract.
FILE_CHOOSER_STRINGS = {'file_chooser_title'}
# LIVE-0927 Android r3 approved seam: AccountRepository gets the shared SessionReauthenticator so a
# successful manual sign-in re-closes that account's auto re-auth circuit breaker.
LIVE_0927_R3_APP_MODULE = (
    ('        sessionReauthenticator: SessionReauthenticator,\n'
     '    ): AccountRepository {\n'
     '        // A successful manual sign-in re-closes the account\'s auto re-auth circuit breaker.\n'
     '        return AccountRepository(accountDao, encryptedPrefs, odooClient, sessionReauthenticator).also { repo ->',
     '    ): AccountRepository {\n'
     '        return AccountRepository(accountDao, encryptedPrefs, odooClient).also { repo ->'),
)
# LIVE-0927 Android r2 (iOS 32462a3 parity): the zh panel title no longer repeats its "Settings"
# option. Only these baseline values may change, and only from exactly this text to exactly that.
LIVE_0927_R2_RETITLED = {('values-zh-rTW', 'configuration'): ('設定', '帳號與設定'),
                         ('values-zh-rCN', 'configuration'): ('设置', '账号与设置')}


def reverse_apply(test, text, deltas):
    for current, original in deltas:
        test.assertEqual(1, text.count(current), current)
        text = text.replace(current, original)
    return text


class BrandIdentityContracts(unittest.TestCase):
    def test_four_independent_variant_identities(self):
        expected = [("woowtechDebug", "io.woowtech.odoo.debug", "woowodoo"),
                    ("woowtechRelease", "io.woowtech.odoo", "woowodoo"),
                    ("apporoDebug", "com.apporo.odoo.debug", "apporoodoo-dev"),
                    ("apporoRelease", "com.apporo.odoo", "apporoodoo")]
        for variant, package, scheme in expected:
            with self.subTest(variant=variant):
                target = target_for(variant)
                self.assertEqual(package, target.package)
                self.assertEqual(scheme, target.scheme)
                self.assertIn(target.brand, target.apk_path)
        self.assertIn('namespace = "io.woowtech.odoo"', GRADLE)
        self.assertIn('applicationId = "com.apporo.odoo"', GRADLE)
        self.assertIn('applicationIdSuffix = ".debug"', GRADLE)
        self.assertIn('versionName = "1.0"', GRADLE)
        self.assertIn('versionCode = 5\n', GRADLE)
        for voided in (1, 2, 3, 4):  # vc1 voided by W1 code changes; vc2/vc3/vc4 already on Play internal testing
            self.assertNotIn(f'versionCode = {voided}\n', GRADLE)
        self.assertIn('versionName = "1.4.2"', GRADLE)
        self.assertIn('versionCode = 23', GRADLE)

    def test_scheme_is_variant_placeholder_not_new_external_router(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:scheme="${brandScheme}"', manifest)
        self.assertNotIn('android:scheme="woowodoo"', manifest)
        self.assertIn('variant.manifestPlaceholders.put("brandScheme", "apporoodoo-dev")', GRADLE)
        self.assertIn('${applicationId}.fileprovider', manifest)
        # Owner-approved 2026-09-29 exception (see EXTERNAL_LINK_MAIN_ACTIVITY): the ONLY external
        # router is ExternalLinkIntake — brand scheme from AppBrand, host `open`, shared validator, and
        # bound to the active account. No other class may read VIEW intent data.
        intake = (K / 'data/push/ExternalLinkIntake.kt').read_text()
        self.assertIn('linkUrl(intent.dataString, AppBrand.current.scheme)', intake)
        self.assertIn('private const val HOST = "open"', intake)
        self.assertIn('DeepLinkValidator.isValid(url = trimmed, serverHost = serverHost)', intake)
        self.assertIn('deepLinkManager.setPending(url = url, accountId = active.id)', intake)
        self.assertIn('if (intent.hasExtra(NotificationHelper.EXTRA_ACTION_URL)) return false', intake)
        # Push-link approval 2026-09-29: the intake's own iOS-level checks moved into the shared validator.
        for duplicate in ('isISOControl', 'contains("%2e%2e")', 'startsWith("https://")'):
            self.assertNotIn(duplicate, intake)
        for literal in ('woowodoo', 'apporoodoo'):
            self.assertNotIn(literal, intake)
        readers = sorted(str(p.relative_to(K)) for p in K.rglob('*.kt') if '.dataString' in p.read_text())
        self.assertEqual(['data/push/ExternalLinkIntake.kt'], readers)
        activity = (K / 'ui/MainActivity.kt').read_text()
        self.assertIsNone(re.search(r'intent\??\.(data\b|dataString)', activity))
        self.assertEqual(2, activity.count('        handleDeepLinkIntent(intent)\n'))  # onCreate + onNewIntent
        self.assertEqual(1, activity.count('externalLinkIntake.accept(intent)'))

    def test_push_tap_and_external_links_share_one_ios_strict_validator(self):
        # Owner-approved 2026-09-29 (PUSH_LINK_VALIDATOR): push taps (router + old-payload path), external
        # links and the WebView apply layer all go through the one shared DeepLinkValidator.
        validator = (K / 'data/push/DeepLinkValidator.kt').read_text()
        for rule in ('Regex("^/web([/?#].*)?$")', '!parsed.scheme.equals("https", ignoreCase = true)',
                     'serverHost.isBlank()', 'TRAVERSAL.containsMatchIn(url)', 'hasControlOrFormat(url)',
                     'Character.FORMAT', 'url != url.trim()'):
            self.assertIn(rule, validator)
        users = {'data/push/DeepLinkRouter.kt': 1, 'ui/MainActivity.kt': 1,
                 'data/push/ExternalLinkIntake.kt': 1, 'ui/main/DeepLinkWebPlanner.kt': 1}
        for name, count in users.items():
            with self.subTest(file=name):
                self.assertEqual(count, (K / name).read_text().count('DeepLinkValidator.isValid('))
        callers = sorted(str(p.relative_to(K)) for p in K.rglob('*.kt') if 'DeepLinkValidator.isValid(' in p.read_text())
        self.assertEqual(sorted(users), callers)
        # pi review P2: every push/external-link host comes from OdooAccount.serverHost (URI hostname, iOS
        # `URL(fullServerUrl).host`), never a string split that keeps the port.
        account = (K / 'domain/model/OdooAccount.kt').read_text()
        self.assertIn('runCatching { URI(withScheme).host }.getOrNull().orEmpty()', account)
        activity = (K / 'ui/MainActivity.kt').read_text()
        self.assertEqual(1, activity.count('serverHost = account.serverHost,'))
        self.assertEqual(1, activity.count('serverHost = active.serverHost)'))
        self.assertNotIn('.split("/")', activity)
        self.assertIn('val serverHost = active.serverHost\n', (K / 'data/push/ExternalLinkIntake.kt').read_text())

    def test_same_server_account_switch_reloads_the_webview(self):
        # pi review P1 (2026-09-29): the WebView reload/cookie isolation keys on the whole account
        # (id + server + database), not only the server URL; two accounts on one server must not share.
        screen = (K / 'ui/main/MainScreen.kt').read_text()
        self.assertIn('private data class WebViewLoadTarget(val accountId: String, val serverUrl: String, val database: String)', screen)
        # pi 0929 recheck-2 replaced the single reloaded WebView (`if (target != lastLoadedTarget)`) by one
        # instance per whole-account target: any change of id, server or database composes a new WebView.
        self.assertIn('val target = WebViewLoadTarget(accountId, serverUrl, database)\n    key(target) {', screen)
        self.assertNotIn('lastLoadedServerUrl', screen)

    def test_account_switch_load_is_generation_gated_and_waits_for_cookies(self):
        # pi 0929 recheck P1: a late page event of the previous account (same host included) must not
        # open the new account's load gate, and no page loads before the cookie removal completed.
        screen = (K / 'ui/main/MainScreen.kt').read_text()
        switch = (K / 'ui/main/WebViewAccountSwitch.kt').read_text()
        self.assertIn('val targetPageLoaded = loadGate.acceptFinished(', screen)
        # Indentation grew by one level when the WebView moved inside key(target) (recheck-2).
        self.assertIn('targetPageLoaded &&\n                                clearHistoryOnNextPage.compareAndSet(true, false)', screen)
        self.assertIn('loadGate.onPageStarted()', screen)
        # recheck-2: the switch load is gated on the target generation instead of loadGate.isCurrent().
        # recheck-4: the first load moved from the factory into a DisposableEffect after the commit-time token
        # (baseline: '...,\n                    ) {\n                        switchGeneration...' inside the factory).
        self.assertIn('isCurrent = ::isCurrentTarget,\n                ) {\n                    switchGeneration?.let { loadGate.onLoadIssued(it) }\n                    view.loadUrl(', screen)
        self.assertNotIn('isCurrent = ::isCurrentTarget,\n                    ) {\n                        switchGeneration?.let { loadGate.onLoadIssued(it) }', screen)
        self.assertNotIn('removeAllCookies(null)', screen)
        self.assertIn('store.removeAllCookies {', switch)
        self.assertIn('if (!loadIssued || !started || !onTargetHost) return false', switch)

    def test_replaced_account_webview_and_async_work_cannot_act(self):
        # pi 0929 recheck-2 (three P1s): a replaced account's WebView events, reordered cookie callbacks and
        # a self-heal / cold-start continuation finishing after a switch must all be inert.
        screen = (K / 'ui/main/MainScreen.kt').read_text()
        switch = (K / 'ui/main/WebViewAccountSwitch.kt').read_text()
        self.assertIn('fun owns(view: WebView?): Boolean = view === thisView && isCurrentTarget()', screen)
        self.assertEqual(3, screen.count('if (!owns(view)) return\n'))
        self.assertIn('if (!owns(view)) {\n                                // A replaced account', screen)
        self.assertIn('if (!owns(webView)) {\n                                callback?.onReceiveValue(null)', screen)
        self.assertIn('if (!owns(thisView)) {\n                                callback.invoke(origin, false, false)', screen)
        # recheck-3: whether async work may act is decided by the process-wide coordinator token (was the
        # per-composition `targetGenerations.get() == generation`), so a disposed Main composition is inert too.
        # recheck-4: current vs baseline — the token is the commit-time [WebViewTargetToken]
        # (baseline: 'fun isCurrentTarget(): Boolean = cookieCoordinator.isCurrent(targetToken)').
        self.assertIn('fun isCurrentTarget(): Boolean = targetToken.isCurrent()', screen)
        self.assertNotIn('fun isCurrentTarget(): Boolean = cookieCoordinator.isCurrent(targetToken)', screen)
        self.assertIn('if (!isCurrentTarget()) {\n                                            Timber.d("Self-heal finished after an account switch', screen)
        self.assertIn('cookieSequencer.enqueue(::isCurrentTarget) { done ->', screen)
        self.assertIn('cookieSequencer.enqueue(isCurrent) { done ->', screen)
        self.assertIn('released.destroy()', screen)
        self.assertIn('class WebViewCookieSequencer', switch)
        self.assertEqual(3, switch.count('if (!isCurrent()) return@removeAllCookies'))

    def test_cookie_work_is_serialized_and_invalidated_process_wide(self):
        # pi 0929 recheck-3 P1: the cookie queue and the "current target" decision are process-wide, not per
        # OdooWebView composition; leaving the Main screen releases the composition's token so its pending
        # cookie work cannot install the previous account's session after the next composition's.
        screen = (K / 'ui/main/MainScreen.kt').read_text()
        switch = (K / 'ui/main/WebViewAccountSwitch.kt').read_text()
        self.assertIn('cookieCoordinator: WebViewCookieCoordinator = WebViewCookieCoordinator.Process,', screen)
        self.assertIn('val cookieSequencer = cookieCoordinator.sequencer\n', screen)
        self.assertNotIn('remember { WebViewCookieSequencer() }', screen)
        # recheck-4 P2, current vs baseline: the token is taken on commit (RememberObserver.onRemembered) and
        # released in onForgotten, never while composing (baseline: 'val targetToken = remember {
        # cookieCoordinator.beginTarget() }' + 'onDispose { cookieCoordinator.release(targetToken) }').
        self.assertIn('val targetToken = rememberWebViewTargetToken(cookieCoordinator)', screen)
        self.assertNotIn('remember { cookieCoordinator.beginTarget() }', screen)
        self.assertIn(') : androidx.compose.runtime.RememberObserver {', switch)
        self.assertIn('override fun onAbandoned() = Unit', switch)
        self.assertIn('token = coordinator.beginTarget()', switch)
        self.assertIn('token?.let(coordinator::release)', switch)
        self.assertIn('if (isReplacedInComposition()) {', screen)
        self.assertIn('class WebViewCookieCoordinator', switch)
        self.assertIn('val Process = WebViewCookieCoordinator()', switch)
        self.assertIn('current.compareAndSet(token, token + 1)', switch)

    def test_provider_has_no_compose_or_context_dependency_and_unknown_fails(self):
        self.assertNotIn('import androidx.compose', BRAND)
        self.assertNotIn('import android.', BRAND)
        self.assertIn('forCode(BuildConfig.APP_BRAND, BuildConfig.DEBUG)', BRAND)
        self.assertIn('else -> error("Unknown app brand")', BRAND)
        with self.assertRaises(ValueError):
            target_for('unknown')

    def test_shared_consumers_must_use_provider(self):
        required = {'domain/model/AppSettings.kt': 'AppBrand.current.primaryHex',
                    'data/local/EncryptedPrefs.kt': 'AppBrand.current.primaryHex',
                    'ui/config/SettingsScreen.kt': 'AppBrand.current.website',
                    'ui/theme/Color.kt': 'AppBrand.current.primaryArgb',
                    'ui/theme/Theme.kt': 'AppBrand.current.secondary'}
        for name, seam in required.items():
            with self.subTest(file=name):
                self.assertIn(seam, (K / name).read_text())

    def test_shared_consumers_must_not_hardcode_brand(self):
        for name in ('domain/model/AppSettings.kt', 'data/local/EncryptedPrefs.kt',
                     'ui/config/SettingsScreen.kt', 'ui/theme/Theme.kt', 'ui/theme/Color.kt'):
            text = (K / name).read_text()
            for forbidden in ('#6183FC', '0xFF6183FC', 'aiot.woowtech.io', 'designsmart.com.tw', '#8B6B24'):
                with self.subTest(file=name, value=forbidden):
                    self.assertNotIn(forbidden, text)

    def test_webview_console_log_tag_is_provider_backed_not_woow_literal(self):
        # W1-12: the Apporo APK must not emit "[WoowTech]" into the Odoo page console.
        text = (K / 'ui/main/MainScreen.kt').read_text()
        self.assertNotIn("[WoowTech]", text)
        self.assertEqual(6, text.count("console.log('[${AppBrand.current.webLogTag}] "))
        self.assertIn('"woowodoo",\n                "WoowTech"\n', BRAND)
        self.assertIn('"apporoodoo", "Apporo"\n', BRAND)

    def test_preferences_only_default_changes(self):
        for file in ('data/local/EncryptedPrefs.kt', 'domain/model/AppSettings.kt'):
            path = f'app/src/main/java/io/woowtech/odoo/{file}'
            expected = baseline(path).decode().replace('"#6183FC"', 'AppBrand.current.primaryHex')
            actual = (ROOT / path).read_text().replace('import io.woowtech.odoo.brand.AppBrand\n', '')
            self.assertEqual(expected.replace('\n\n\n', '\n\n'), actual.replace('\n\n\n', '\n\n'))

    def test_stage_three_and_security_seams_are_byte_identical(self):
        # Phase 3 approved exception: only the five explicit push/session seams below may change.
        # DeepLinkValidator: owner-approved 2026-09-29 push-link tightening, see PUSH_LINK_VALIDATOR.
        # All unrelated security/entry-point code retains its byte-level baseline gate.
        files = ['ui/MainActivity.kt', 'ui/login/ServerUrlInput.kt', 'data/push/DeepLinkValidator.kt',
                 'data/push/DeepLinkRouter.kt', 'WoowOdooApp.kt', 'data/push/WoowFcmService.kt']
        for name in files:
            with self.subTest(file=name):
                path = f'app/src/main/java/io/woowtech/odoo/{name}'
                current = (ROOT / path).read_bytes()
                if name == 'ui/MainActivity.kt':
                    current = reverse_apply(self, current.decode(), W1_10_MAIN_ACTIVITY)
                    current = reverse_apply(self, current, EXTERNAL_LINK_MAIN_ACTIVITY)
                    current = reverse_apply(self, current, PUSH_LINK_SERVER_HOST_MAIN_ACTIVITY).encode()
                if name == 'data/push/DeepLinkValidator.kt':
                    current = reverse_apply(self, current.decode(), PUSH_LINK_VALIDATOR).encode()
                self.assertEqual(baseline(path), current)
        account = (K / 'data/repository/AccountRepository.kt').read_text()
        account = reverse_apply(self, account, F5G_ACCOUNT_WOOW)
        apporo_account = account.split('    // Apporo selection/session commit boundary.', 1)[1].split('    fun getSessionId(', 1)[0]
        self.assertIn('if (attempt != selectionAttempt)', apporo_account)
        self.assertIn('validApporoSession(result.sessionId)', apporo_account)
        self.assertIn('odooClient.publishApporoSession', apporo_account)
        self.assertEqual(2, apporo_account.count('odooClient.authenticateApporoIsolated('))
        self.assertNotIn('odooClient.authenticate(', apporo_account)
        self.assertIn('currentCoroutineContext().ensureActive()', apporo_account)
        commit = apporo_account.split('    private suspend fun commitApporoSelection(', 1)[1]
        self.assertIn('withContext(NonCancellable)', commit)
        self.assertNotIn('fcmTokenRepository', commit)
        self.assertNotIn('authenticate', commit)
        self.assertIn('accountDao.insertAccount(original)', commit)
        self.assertIn('accountDao.activateAccount(previous.id)', commit)
        account = account.replace('    // Apporo selection/session commit boundary.' + apporo_account, '')
        start = account.index('class AccountRepository(')
        end = account.index('    val allAccounts:', start)
        account = account[:start] + ('class AccountRepository @Inject constructor(\n'
            '    private val accountDao: AccountDao,\n'
            '    private val encryptedPrefs: EncryptedPrefs,\n'
            '    private val odooClient: OdooJsonRpcClient\n) {\n') + account[end:]
        for line in ('import io.woowtech.odoo.brand.AppBrand\n',
                     'import io.woowtech.odoo.data.api.SessionReauthenticator\n',
                     # pi 1001c P1: tri-state ownership proof for promotions (publishKnownSession).
                     'import io.woowtech.odoo.data.api.SessionOwnership\n',
                     'import kotlinx.coroutines.sync.Mutex\n', 'import kotlinx.coroutines.sync.withLock\n',
                     'import kotlinx.coroutines.NonCancellable\n', 'import kotlinx.coroutines.currentCoroutineContext\n',
                     # pi 1001g P2: push compensation runs outside the selection lock and is cancelled by a newer unregister.
                     'import kotlinx.coroutines.cancelAndJoin\n', 'import kotlinx.coroutines.launch\n',
                     'import kotlinx.coroutines.ensureActive\n', 'import kotlinx.coroutines.withContext\n',
                     '        if (brand.isApporo) return authenticateApporo(serverUrl, database, username, password, rememberPassword)\n',
                     '        if (brand.isApporo) return switchApporoAccount(accountId)\n'):
            self.assertEqual(1, account.count(line))
            account = account.replace(line, '')
        for line in ('            fcmTokenRepository?.onManualLogin(account.id, result.sessionId)\n',
                     '            fcmTokenRepository?.onManualLogin(accountId, result.sessionId)\n',
                     # LIVE-0927 r3 approved seam: manual sign-in re-closes the auto re-auth breaker.
                     '            sessionReauthenticator?.onManualReloginSucceeded(account.id)\n',
                     '            sessionReauthenticator?.onManualReloginSucceeded(accountId)\n',
                     '        fcmTokenRepository?.forgetAccount(id)\n',
                     '        fcmTokenRepository?.forgetAccount(accountId)\n'):
            self.assertEqual(1, account.count(line))
            account = account.replace(line, '')
        account = reverse_apply(self, account, F5F_ACCOUNT_WOOW)
        account = reverse_apply(self, account, F5E_ACCOUNT_WOOW)
        account = reverse_apply(self, account, F5D_ACCOUNT_WOOW)
        account = reverse_apply(self, account, F5B_ACCOUNT_WOOW)
        account = reverse_apply(self, account, F5_ACCOUNT_WOOW)
        account = reverse_apply(self, account, W1_10_ACCOUNT_WOOW)
        self.assertEqual(baseline('app/src/main/java/io/woowtech/odoo/data/repository/AccountRepository.kt').decode(), account)
        # Fifth approved seam: Apporo response-derived SID; byte-preserve the entire WOOW auth path.
        api = (K / 'data/api/OdooJsonRpcClient.kt').read_text()
        isolated = api.split('    /** Apporo login reads only THIS response', 1)[1].split('    private fun executeRequest(', 1)[0]
        self.assertIn('Cookie.parseAll(request.url, response.headers)', isolated)
        self.assertIn("if (sid.isBlank() || sid.any { it <= ' ' || it == ';' || it >= '\\u007f' }) return AuthResult.Error", isolated)
        self.assertIn('internal suspend fun authenticateApporoIsolated(', isolated)
        self.assertNotIn('getSessionId(', isolated)
        self.assertIn('if (response.code != 200) return signInHttpStatus(response.code)', isolated)
        api = api.replace('    /** Apporo login reads only THIS response' + isolated, '')
        start = api.index('class OdooJsonRpcClient internal constructor(')
        end = api.index('    private val gson = Gson()', start)
        api = api[:start] + 'class OdooJsonRpcClient @Inject constructor() {\n\n' + api[end:]
        # Accepted P1 fix: only shared authenticate publishes; isolated manual entry above does not.
        shared_apporo = '''        if (brand.isApporo) {
            val result = authenticateApporo(serverUrl, database, username, password)
            // Shared WebView reauth retains its publish-on-success contract.
            if (result is AuthResult.Success) publishApporoSession(serverUrl, result.sessionId)
            return@withContext result
        }
'''
        self.assertEqual(1, api.count(shared_apporo))
        api = api.replace(shared_apporo, '')
        # Approved test-only shared client injection; production null still uses original builder.
        test_builder = '    private val client: OkHttpClient = (sharedAuthClient?.newBuilder() ?: OkHttpClient.Builder())'
        self.assertEqual(1, api.count(test_builder))
        api = api.replace(test_builder, '    private val client: OkHttpClient = OkHttpClient.Builder()')
        for line in ('import okhttp3.HttpUrl.Companion.toHttpUrlOrNull\n',
                     'import io.woowtech.odoo.brand.AppBrand\n'):
            self.assertEqual(1, api.count(line))
            api = api.replace(line, '')
        api = reverse_apply(self, api, SESSION_OWNERSHIP_API)
        api = reverse_apply(self, api, WOOW_RESPONSE_SID_API)
        api = reverse_apply(self, api, WOOW_SIGNIN_NO_SESSION_API)
        api = reverse_apply(self, api, F5_WOOW_API)
        api = reverse_apply(self, api, SERVER_HTTP_STATUS_WOOW_API)
        self.assertEqual(baseline('app/src/main/java/io/woowtech/odoo/data/api/OdooJsonRpcClient.kt').decode(), api)
        module = (K / 'di/AppModule.kt').read_text()
        for line in ('        apporoPushTransport: io.woowtech.odoo.data.repository.ApporoPushTransport,\n',
                     '            apporoTransport = apporoPushTransport,\n'):
            self.assertEqual(1, module.count(line))
            module = module.replace(line, '')
        module = reverse_apply(self, module, F5C_APP_MODULE)
        module = reverse_apply(self, module, F5_APP_MODULE)
        module = reverse_apply(self, module, LIVE_0927_R3_APP_MODULE)
        self.assertEqual(baseline('app/src/main/java/io/woowtech/odoo/di/AppModule.kt').decode(), module)
        interface = (K / 'data/repository/FcmTokenRepository.kt').read_text()
        added = interface.split('interface FcmTokenRepository {\n', 1)[1].split('    /**\n     * Registers', 1)[0]
        self.assertIn('val registrationStatuses:', added)
        self.assertIn('suspend fun onManualLogin(accountId: String, sessionId: String? = null)', added)
        self.assertIn('suspend fun forgetAccount(accountId: String)', added)
        self.assertEqual(baseline('app/src/main/java/io/woowtech/odoo/data/repository/FcmTokenRepository.kt').decode(),
                         interface.replace(added, '\n', 1))
        impl = (K / 'data/repository/FcmTokenRepositoryImpl.kt').read_text()
        self.assertIn('if (!brand.isApporo) return postToOdoo(account.fullServerUrl, path, params, account)', impl)
        self.assertEqual(4, impl.count('writeToOdoo('))  # definition + register + unregister + rotation
        self.assertEqual(2, len(re.findall(r'(?<![\w])postToOdoo\(', impl)))  # definition + WOOW only
        old_impl = baseline('app/src/main/java/io/woowtech/odoo/data/repository/FcmTokenRepositoryImpl.kt').decode()
        for method in ('reconcileToken', 'reconcileOnAccountAvailable'):
            pattern = r'    override suspend fun ' + method + r'\(.*?(?=\n    /\*\*)'
            self.assertEqual(re.search(pattern, old_impl, re.S).group(), re.search(pattern, impl, re.S).group())
        self.assertIn('brand = AppBrand.current,', impl)
        self.assertIn('ApporoPushContract.requireRegistration(body)', impl)
        self.assertIn('ApporoPushContract.requireUnregistration(body)', impl)
        # Executable behavioral counterparts exist, but this static gate does NOT count as JVM PASS.
        jvm = (ROOT / 'app/src/test/kotlin/io/woowtech/odoo/data/repository/ApporoPushTransportTest.kt').read_text()
        for scenario in ('Given legacy WOOW when register and unregister',
                         'Given unsupported cap for register and unregister then zero writes',
                         'Given write expires when healed then new session must pass cap'):
            self.assertIn(scenario, jvm)

    def test_woow_resources_and_firebase_unchanged(self):
        files = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', BASELINE,
                                         'app/src/main/res'], cwd=ROOT).decode().splitlines()
        self.assertTrue(files)
        for name in files:
            with self.subTest(file=name):
                if name in {f'app/src/main/res/{locale}/strings.xml'
                            for locale in ('values', 'values-zh-rTW', 'values-zh-rCN')}:
                    old = {node.attrib['name']: node for node in ET.fromstring(baseline(name))}
                    new = {node.attrib['name']: node for node in ET.parse(ROOT / name).getroot()}
                    allowed = {'push_registration_title', 'push_registration_not_checked',
                               'push_registration_checking', 'push_registration_acknowledged',
                               'push_registration_not_configured', 'push_registration_contract_rejected',
                               'push_registration_retry', 'push_registration_sign_in',
                               'push_registration_unregistered', 'push_registration_disclaimer',
                               'login_server_url_required', 'login_database_required',
                               'login_username_required', 'login_password_required'} | SERVER_HTTP_STATUS_STRINGS \
                              | LIVE_0927_R2_STRINGS | LIVE_0927_R3_STRINGS | PIN_LOCKOUT_STRINGS \
                              | REMOVE_PIN_STRINGS | PIN_DELETE_KEY_STRINGS | FILE_CHOOSER_STRINGS
                    self.assertEqual(set(old) | allowed, set(new))
                    self.assertTrue(allowed.isdisjoint(old))
                    def semantic(node):
                        return (node.tag, sorted(node.attrib.items()), node.text,
                                [(semantic(child), child.tail) for child in node])
                    for key, node in old.items():
                        if key in PIN_PLURALS_RETYPED:
                            self.assertEqual(('string', {'name': key}), (node.tag, dict(node.attrib)))
                            self.assertEqual(('plurals', {'name': key}), (new[key].tag, dict(new[key].attrib)))
                            items = {item.attrib['quantity']: item.text for item in new[key]}
                            self.assertEqual({'one', 'other'} if '/values/' in name else {'other'}, set(items), key)
                            self.assertEqual(node.text, items['other'], key)
                            self.assertTrue(all('%d' in text for text in items.values()), key)
                            continue
                        retitled = LIVE_0927_R2_RETITLED.get((name.split('/')[-2], key))
                        if retitled:
                            self.assertEqual((node.tag, dict(node.attrib), retitled[0]), (node.tag, dict(node.attrib), node.text))
                            self.assertEqual((node.tag, dict(node.attrib), retitled[1]),
                                             (new[key].tag, dict(new[key].attrib), new[key].text), key)
                            continue
                        self.assertEqual(semantic(node), semantic(new[key]), key)
                    english = {node.attrib['name']: node
                               for node in ET.parse(ROOT / 'app/src/main/res/values/strings.xml').getroot()}
                    def text(node):
                        # A <plurals> is judged by its items' text; a <string> by its own text.
                        if node.tag == 'plurals':
                            return '|'.join(item.text or '' for item in node)
                        return node.text
                    for key in allowed:
                        self.assertEqual({'name': key}, new[key].attrib)
                        self.assertTrue(text(new[key]))
                        if new[key].tag == 'plurals':
                            quantities = [item.attrib['quantity'] for item in new[key]]
                            self.assertEqual(['one', 'other'] if '/values/' in name else ['other'], quantities, key)
                            self.assertTrue(all(item.text and '%d' in item.text for item in new[key]), key)
                        if '/values/' not in name:
                            self.assertNotEqual(text(english[key]), text(new[key]))
                            self.assertRegex(text(new[key]), r'[\u4e00-\u9fff]')
                elif name in BACKUP_EXCLUSION_RULES:
                    # pi 1001c P2: the only change is one more excluded secret file (BackupExclusionContracts).
                    current = (ROOT / name).read_text()
                    self.assertEqual(BACKUP_EXCLUSION_RULES[name], current.count(KNOWN_SESSIONS_EXCLUDE))
                    self.assertEqual(baseline(name).decode(), re.sub(r'\n *' + re.escape(KNOWN_SESSIONS_EXCLUDE), '', current))
                else:
                    self.assertEqual(hashlib.sha256(baseline(name)).digest(),
                                     hashlib.sha256((ROOT / name).read_bytes()).digest())
        # Git compares the client internally; Python never reads or prints its contents.
        client = 'app/google-services.json'
        self.assertTrue((ROOT / client).is_file())
        for args in (['cat-file', '-e', f'{BASELINE}:{client}'],
                     ['ls-files', '--error-unmatch', '--', client],
                     ['diff', '--quiet', '--no-ext-diff', '--no-textconv', BASELINE, '--', client]):
            result = subprocess.run(['git', '--no-optional-locks', *args], cwd=ROOT,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.assertEqual(0, result.returncode, 'Firebase baseline/presence/unchanged gate failed')

    def test_release_dependency_locks_preserve_all_versions(self):
        old = baseline('app/gradle.lockfile').decode()
        self.assertEqual(old.replace('=releaseRuntimeClasspath', '=apporoReleaseRuntimeClasspath,woowtechReleaseRuntimeClasspath'),
                         (ROOT / 'app/gradle.lockfile').read_text())


class BrandResourceContracts(unittest.TestCase):
    def test_three_locales_have_same_complete_overlay_keys(self):
        default = strings('apporo', 'values')
        for locale in ('values', 'values-zh-rTW', 'values-zh-rCN'):
            self.assertEqual(set(default), set(strings('apporo', locale)))

    def test_both_brands_names_legal_copy_and_six_links(self):
        for brand in ('main', 'apporo'):
            for locale in ('values', 'values-zh-rTW', 'values-zh-rCN'):
                with self.subTest(brand=brand, locale=locale):
                    values = strings(brand, locale)
                    is_apporo = brand == 'apporo'
                    self.assertEqual('Apporo platform' if is_apporo else 'woowtech platform', values['app_name'])
                    self.assertEqual('Apporo platform / APPORO UNION INC.' if is_apporo else '© 2026 WoowTech', values['copyright'])
                    host = 'www.apporo.ai' if is_apporo else 'aiot.woowtech.io'
                    suffix = '-en' if locale == 'values' else ''
                    for key, page in [('url_support', 'support'), ('url_privacy_policy', 'privacy'),
                                      ('url_account_deletion', 'account-deletion')]:
                        self.assertEqual(f'https://{host}/odoo-{page}{suffix}', values[key])
                    if is_apporo:
                        self.assertEqual('Apporo platform', values['notification_channel_messages'])
                        self.assertFalse(any('woow' in v.lower() or '渥屋' in v or '©' in v for v in values.values()))

    def test_woowtech_zh_tw_name_lives_only_in_woowtech_flavor_overlay(self):
        # engineering b1b052f: WOOW zh-TW launcher/title name is 渥屋平台; it must not leak into
        # main/ (shared by both brands) nor into any Apporo locale.
        overlay = ROOT / 'app/src/woowtech'
        files = sorted(str(p.relative_to(overlay)) for p in overlay.rglob('*') if p.is_file())
        self.assertEqual(['res/values-zh-rTW/strings.xml'], files)
        self.assertEqual({'app_name': '渥屋平台'}, strings('woowtech', 'values-zh-rTW'))
        for locale in ('values', 'values-zh-rTW', 'values-zh-rCN'):
            with self.subTest(locale=locale):
                self.assertEqual('woowtech platform', strings('main', locale)['app_name'])
                self.assertEqual('Apporo platform', strings('apporo', locale)['app_name'])
        engineering = subprocess.run(['git', 'merge-base', '--is-ancestor', 'b1b052f', 'HEAD'], cwd=ROOT,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.assertEqual(0, engineering.returncode, 'engineering b1b052f must be merged into this line')

    def test_settings_links_are_resource_backed_and_mail_is_provider_backed(self):
        text = (K / 'ui/config/SettingsScreen.kt').read_text()
        for resource in ('supportUrlResource', 'privacyUrlResource', 'deletionUrlResource'):
            self.assertIn(f'context.getString(AppBrand.current.{resource})', text)
        self.assertIn('mailto:${AppBrand.current.supportEmail}', text)
        for resource in ('url_support', 'url_privacy_policy', 'url_account_deletion'):
            self.assertIn('R.string.' + resource, BRAND)

    def test_logo_and_channel_consumers_must_use_resources(self):
        login = (K / 'ui/login/LoginScreen.kt').read_text()
        self.assertIn('painterResource(id = R.drawable.woow_logo)', login)
        self.assertIn('stringResource(R.string.content_description_logo)', login)
        app = (K / 'WoowOdooApp.kt').read_text()
        self.assertIn('getString(R.string.notification_channel_messages)', app)
        self.assertIn('getString(R.string.notification_channel_messages_desc)', app)

    def test_file_chooser_title_is_a_three_locale_resource(self):
        # W2-4 L7: no hardcoded chooser title; both brands read the shared three-locale string.
        screen = (K / 'ui/main/MainScreen.kt').read_text()
        self.assertNotIn('選擇檔案', screen)
        self.assertIn('context.getString(R.string.file_chooser_title)', screen)
        for locale in ('values', 'values-zh-rTW', 'values-zh-rCN'):
            with self.subTest(locale=locale):
                self.assertTrue(strings('main', locale)['file_chooser_title'])

    def test_every_legacy_asset_qualifier_has_an_apporo_overlay(self):
        base = ROOT / 'app/src/main/res'
        overlay = ROOT / 'app/src/apporo/res'
        for path in base.rglob('*'):
            if path.is_file() and path.parent.name.startswith(('drawable', 'mipmap')):
                with self.subTest(path=str(path.relative_to(base))):
                    self.assertTrue((overlay / path.relative_to(base)).is_file())

    def test_generated_asset_hashes_sizes_opacity_and_white_corners(self):
        manifest = json.loads((ROOT / 'docs/plans/2026-09-24-apporo-assets.json').read_text())
        self.assertEqual(27, len(manifest['outputs']))
        for record in manifest['outputs']:
            with self.subTest(path=record['path']):
                path = ROOT / record['path']
                self.assertEqual(record['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                w, h, pixels = read_rgba(path)
                self.assertEqual(record['size'], [w, h])
                if 'badge' in record:
                    # Circular login badge: transparency is the point; checked in the badge test below.
                    self.assertTrue(record['path'].endswith('/woow_logo.png'))
                    continue
                self.assertTrue(all(a == 255 for a in pixels[3::4]))
                for i in (0, w - 1, (h - 1) * w, w * h - 1):
                    self.assertEqual(b'\xff\xff\xff\xff', pixels[i * 4:i * 4 + 4])
                self.assertLess(min(pixels), 255)

    def test_login_logo_is_circular_badge_with_transparent_corners_and_grey_ring(self):
        # Owner: every logo gets a round frame. Shared spec with iOS: disc = canvas, fill #FFFFFF,
        # inner stroke #D9D9D9 at 3% of the edge, mark 60% centred, alpha 0 outside the circle.
        manifest = json.loads((ROOT / 'docs/plans/2026-09-24-apporo-assets.json').read_text())
        badges = {r['path']: r for r in manifest['outputs'] if 'badge' in r}
        expected = {f'app/src/apporo/res/drawable-{d}/woow_logo.png': s
                    for d, s in (('mdpi', 72), ('hdpi', 108), ('xhdpi', 144), ('xxhdpi', 216), ('xxxhdpi', 288))}
        self.assertEqual(set(expected), set(badges))
        for relative, size in expected.items():
            with self.subTest(path=relative):
                record = badges[relative]
                self.assertEqual(0.6, record['fraction'])
                self.assertEqual({'shape': 'circle', 'fill': '#FFFFFF', 'stroke': '#D9D9D9',
                                  'stroke_fraction': 0.03, 'supersample': 4}, record['badge'])
                w, h, pixels = read_rgba(ROOT / relative)
                self.assertEqual((size, size), (w, h))

                def px(x, y):
                    return tuple(pixels[(y * w + x) * 4:(y * w + x) * 4 + 4])
                for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
                    self.assertEqual(0, px(x, y)[3])
                self.assertEqual(255, px(w // 2, h // 2)[3])
                # Middle of the stroke band on all four sides: opaque, close to #D9D9D9.
                band = int(w * 0.015)
                for x, y in ((w // 2, band), (w // 2, h - 1 - band), (band, h // 2), (w - 1 - band, h // 2)):
                    r, g, b, a = px(x, y)
                    self.assertEqual(255, a)
                    for channel in (r, g, b):
                        self.assertLessEqual(abs(channel - 0xD9), 8)
                # Just inside the stroke (above the mark): white fill.
                self.assertEqual((255, 255, 255, 255), px(w // 2, int(w * 0.08)))
                # Anti-aliased edge: some partially transparent pixels exist; nothing outside the disc is visible.
                alphas = pixels[3::4]
                self.assertTrue(any(0 < a < 255 for a in alphas))
                for i in range(w * h):
                    if math.hypot(i % w + .5 - w / 2, i // w + .5 - h / 2) > w / 2 + .75:
                        self.assertEqual(0, alphas[i])

    def test_adaptive_mark_fits_safe_circle_without_cropping(self):
        w, h, pixels = read_rgba(ROOT / 'app/src/apporo/res/drawable-xxxhdpi/ic_launcher_foreground.png')
        radius = max(math.hypot(i % w + .5 - w / 2, i // w + .5 - h / 2)
                     for i in range(w * h) if min(pixels[i * 4:i * 4 + 3]) < 250)
        self.assertLessEqual(radius / 4, 33)

    def test_apporo_xml_theme_splash_and_adaptive_background_are_not_woow_blue(self):
        root = ROOT / 'app/src/apporo/res'
        colors = {n.attrib['name']: n.text for n in ET.parse(root / 'values/colors.xml').getroot()}
        self.assertEqual({'woowtech_blue': '#8B6B24', 'woowtech_blue_dark': '#4C3B14',
                          'woowtech_blue_light': '#EEE9DE', 'splash_background': '#FFFFFF'}, colors)
        for name in ('ic_launcher', 'ic_launcher_round'):
            text = (root / f'mipmap-anydpi-v26/{name}.xml').read_text()
            self.assertIn('@drawable/ic_launcher_background', text)
            self.assertIn('@drawable/ic_launcher_foreground', text)
        self.assertIn('#FFFFFF', (root / 'drawable/ic_launcher_background.xml').read_text())
        splash = (root / 'values-v31/themes.xml').read_text()
        self.assertIn('android:windowSplashScreenBackground', splash)
        self.assertIn('@drawable/ic_launcher_foreground', splash)
        for path in root.rglob('*.xml'):
            ET.parse(path)
            self.assertNotIn('#6183FC', path.read_text())

    def test_apporo_color_derivation_and_independent_wcag(self):
        def mix(target, fraction):
            return tuple(math.floor(c * (1 - fraction) + target * fraction + .5) for c in (139, 107, 36))
        def luminance(rgb):
            linear = [v / 255 / 12.92 if v / 255 <= .04045 else ((v / 255 + .055) / 1.055) ** 2.4 for v in rgb]
            return sum(c * w for c, w in zip(linear, (.2126, .7152, .0722)))
        for target, fraction, expected in [(255, .85, 'EEE9DE'), (0, .45, '4C3B14'), (0, .74, '241C09')]:
            self.assertEqual(expected, ''.join(f'{v:02X}' for v in mix(target, fraction)))
            self.assertIn(f'mix(0x{target:02X}{target:02X}{target:02X}, {fraction})', BRAND)
        for a, b in [('8B6B24', 'FFFFFF'), ('EEE9DE', '241C09'), ('4C3B14', 'EEE9DE')]:
            la, lb = [luminance(tuple(bytes.fromhex(c))) for c in (a, b)]
            self.assertGreaterEqual((max(la, lb) + .05) / (min(la, lb) + .05), 4.5)

    def test_fixed_theme_keeps_primary_and_container_foregrounds_paired(self):
        text = (K / 'ui/theme/Theme.kt').read_text().split('fun WoowFixedBrandTheme', 1)[1]
        for seam in ('primary = WoowTechBlue', 'onPrimary = Color.White', 'onPrimaryContainer = if',
                     'PrimaryContainerLight', 'PrimaryContainerDark', 'OnPrimaryContainerLight', 'OnPrimaryContainerDark'):
            self.assertIn(seam, text)
        self.assertNotIn('ThemeManager.primaryColor', text)


class BrandFirebaseContracts(unittest.TestCase):
    def test_apporo_uses_owner_confirmed_project_not_rejected_candidate(self):
        for kind in ('debug', 'release'):
            self.assertEqual(target_for('apporo' + kind.title()).firebase_project, 'apporo-odoo-app')
            for forbidden in ('apporo-odoo', 'apporo-aiot-app', 'woow-odoo-de2cb'):
                config = fixture('apporo', kind)
                config['project_info']['project_id'] = forbidden
                with self.subTest(kind=kind, project=forbidden), self.assertRaises(BrandConfigError):
                    validate_client(config, 'apporo', kind)

    def test_all_four_client_identities_pass_policy_with_memory_only_fixtures(self):
        for brand in ('woowtech', 'apporo'):
            for kind in ('debug', 'release'):
                validate_client(fixture(brand, kind), brand, kind)

    def test_cross_brand_project_rejected_both_directions(self):
        for brand, other in [('woowtech', 'apporo'), ('apporo', 'woowtech')]:
            config = fixture(brand, 'debug')
            config['project_info']['project_id'] = target_for(other + 'Debug').firebase_project
            with self.assertRaises(BrandConfigError):
                validate_client(config, brand, 'debug')

    def test_wrong_package_or_dev_prod_rejected(self):
        for brand in ('woowtech', 'apporo'):
            with self.assertRaises(BrandConfigError):
                validate_client(fixture(brand, 'release'), brand, 'debug')

    def test_missing_files_rejected_without_root_fallback(self):
        for variant in ('woowtechDebug', 'woowtechRelease', 'apporoDebug', 'apporoRelease'):
            brand = target_for(variant).brand
            with self.assertRaises(BrandConfigError):
                validate_file(ROOT / f'nonexistent-brand-test/{variant}/google-services.json', brand, 'debug')

    def test_invalid_sender_appid_duplicate_or_missing_api_rejected(self):
        for mutation in ('sender', 'appid', 'duplicate', 'api'):
            config = fixture('apporo', 'debug')
            if mutation == 'sender': config['project_info']['project_number'] = ''
            if mutation == 'appid': config['client'][0]['client_info']['mobilesdk_app_id'] = '1:999:android:wrong'
            if mutation == 'duplicate': config['client'].append(copy.deepcopy(config['client'][0]))
            if mutation == 'api': config['client'][0]['api_key'] = []
            with self.subTest(mutation=mutation), self.assertRaises(BrandConfigError):
                validate_client(config, 'apporo', 'debug')

    def test_service_accounts_and_unknown_brand_are_rejected(self):
        with self.assertRaises(BrandConfigError):
            validate_client({'type': 'service_account'}, 'apporo', 'debug')
        with self.assertRaises(BrandConfigError):
            validate_client({}, 'unknown', 'debug')

    def test_gradle_must_select_explicit_file_and_wire_validation(self):
        self.assertIn('brand == "woowtech" && !variantConfig.exists()', GRADLE)
        self.assertIn('googleServicesJsonFiles.set(listOf(firebaseConfig))', GRADLE)
        self.assertIn('scripts/validate_brand_config.py', GRADLE)
        self.assertEqual(2, GRADLE.count('dependsOn(validateBrand)'))
        self.assertIn('if (brand == "apporo" && release)', GRADLE)
        self.assertIn('check(hasSigning("apporo"))', GRADLE)
        self.assertIn('apporoStore != woowStore', GRADLE)
        self.assertIn('apporo-keystore.properties', GRADLE)
        self.assertNotIn('if (file("google-services.json").exists())', GRADLE)


class BrandCheckoutContracts(unittest.TestCase):
    def test_ci_checkout_fetches_full_history_before_contracts(self):
        text = (ROOT / '.github/workflows/build.yml').read_text()
        # Scope the assertion to the checkout step, not another action's inputs.
        steps = text.split('    steps:\n', 1)[1].split('      - ')
        checkout = [step for step in steps if step.startswith('uses: actions/checkout@v4\n')]
        self.assertEqual(1, len(checkout))
        self.assertRegex(checkout[0], r'(?m)^        with:\n(?:          #[^\n]*\n)*          fetch-depth: 0\s*$')
        self.assertLess(text.index('uses: actions/checkout@v4'), text.index('name: Offline brand contracts'))

    def test_required_baseline_commit_and_source_objects_exist(self):
        for obj in (f'{BASELINE}^{{commit}}', f'{BASELINE}:app/src/main/res',
                    f'{BASELINE}:app/gradle.lockfile',
                    f'{BASELINE}:app/src/main/java/io/woowtech/odoo/data/local/EncryptedPrefs.kt'):
            with self.subTest(object=obj):
                result = subprocess.run(['git', 'cat-file', '-e', obj], cwd=ROOT,
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                self.assertEqual(0, result.returncode, 'Required baseline object missing; do not skip contracts')

    def test_local_clean_checkout_full_history_restores_baseline_show_and_tree(self):
        # Synthetic history only: no product files, Firebase configuration or network.
        with tempfile.TemporaryDirectory(prefix='brand-checkout-') as directory:
            root = Path(directory)
            env = {'PATH': os.environ['PATH'], 'HOME': directory, 'LANG': 'C',
                   'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                   'GIT_ALLOW_PROTOCOL': 'file', 'GIT_TERMINAL_PROMPT': '0',
                   'GIT_AUTHOR_NAME': 'Fixture', 'GIT_AUTHOR_EMAIL': 'fixture@example.invalid',
                   'GIT_COMMITTER_NAME': 'Fixture', 'GIT_COMMITTER_EMAIL': 'fixture@example.invalid'}

            def git(cwd, *args, check=True):
                return subprocess.run(['git', '-c', 'core.hooksPath=' + os.devnull,
                                       '-c', 'commit.gpgsign=false', *args], cwd=cwd, env=env,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check)

            source = root / 'source'
            git(root, 'init', '--template=', str(source))
            (source / 'protected.txt').write_text('synthetic baseline\n')
            git(source, 'add', 'protected.txt')
            git(source, 'commit', '-m', 'Synthetic baseline')
            base = git(source, 'rev-parse', 'HEAD').stdout.decode().strip()
            (source / 'candidate.txt').write_text('synthetic candidate\n')
            git(source, 'add', 'candidate.txt')
            git(source, 'commit', '-m', 'Synthetic candidate')
            for name, depth in (('shallow', ['--depth', '1']), ('full', [])):
                with self.subTest(checkout=name):
                    target = root / name
                    # file:// honors --depth; a plain local path clone would ignore it.
                    git(root, 'clone', *depth, source.as_uri(), str(target))
                    self.assertEqual(b'', git(target, 'status', '--porcelain').stdout)
                    self.assertEqual(b'true\n' if depth else b'false\n',
                                     git(target, 'rev-parse', '--is-shallow-repository').stdout)
                    show = git(target, 'show', f'{base}:protected.txt', check=False)
                    tree = git(target, 'ls-tree', '-r', '--name-only', base, check=False)
                    if depth:
                        self.assertNotEqual(0, show.returncode)
                        self.assertNotEqual(0, tree.returncode)
                    else:
                        self.assertEqual(0, show.returncode)
                        self.assertEqual(b'synthetic baseline\n', show.stdout)
                        self.assertEqual(0, tree.returncode)
                        self.assertEqual(b'protected.txt\n', tree.stdout)


class BrandToolingContracts(unittest.TestCase):
    def test_unapproved_writes_fail_for_both_brands(self):
        for variant in ('woowtechDebug', 'apporoDebug', 'woowtechRelease', 'apporoRelease'):
            with self.assertRaises(ValueError):
                authorize_live(target_for(variant), 'https://demo222-odoo.woowtech.io', {})

    def test_explicit_woow_authorization_is_package_and_origin_bound(self):
        target = target_for('woowtechDebug')
        url = 'https://demo222-odoo.woowtech.io'
        env = {'APP_VARIANT': target.variant, 'ALLOW_DEVICE_TEST_WRITES': target.package, 'ALLOW_ODOO_TEST_WRITES': url}
        authorize_live(target, url, env)
        for key in env:
            bad = env.copy(); bad[key] = 'other'
            with self.subTest(key=key), self.assertRaises(ValueError):
                authorize_live(target, url, bad)
        for bad_url in ('https://evil.example', url + '/web', 'http://demo222-odoo.woowtech.io'):
            bad = env.copy(); bad['ALLOW_ODOO_TEST_WRITES'] = bad_url
            with self.assertRaises(ValueError):
                authorize_live(target, bad_url, bad)

    APPORO_ACCOUNT = 'designated.reviewer@example.invalid'

    def apporo_ui_env(self, target, url):
        return {'APP_VARIANT': target.variant, 'ALLOW_DEVICE_TEST_WRITES': target.package,
                'ALLOW_ODOO_TEST_WRITES': url, 'ALLOW_APPORO_LIVE_UI': target.package,
                'APPORO_LIVE_ACCOUNT': self.APPORO_ACCOUNT}

    def test_apporo_debug_ui_allowed_only_with_explicit_flag_and_designated_account(self):
        target = target_for('apporoDebug')
        url = 'https://demo111-odoo.woowtech.io'
        env = self.apporo_ui_env(target, url)
        authorize_live(target, url, env, scope='ui', account=self.APPORO_ACCOUNT)
        for key in env:
            with self.subTest(missing=key), self.assertRaises(ValueError):
                bad = env.copy(); del bad[key]
                authorize_live(target, url, bad, scope='ui', account=self.APPORO_ACCOUNT)
            with self.subTest(wrong=key), self.assertRaises(ValueError):
                bad = env.copy(); bad[key] = 'other'
                authorize_live(target, url, bad, scope='ui', account=self.APPORO_ACCOUNT)
        for account in ('', 'someone.else@example.invalid'):
            with self.subTest(account=account), self.assertRaisesRegex(ValueError, 'APPORO_LIVE_ACCOUNT'):
                authorize_live(target, url, env, scope='ui', account=account)
        with self.assertRaisesRegex(ValueError, 'APPORO_LIVE_ACCOUNT'):
            authorize_live(target, url, {**env, 'APPORO_LIVE_ACCOUNT': ''}, scope='ui', account='')

    def test_apporo_ui_never_targets_demo222_or_release(self):
        url = 'https://demo222-odoo.woowtech.io'
        target = target_for('apporoDebug')
        with self.assertRaisesRegex(ValueError, 'demo222 is read-only'):
            authorize_live(target, url, self.apporo_ui_env(target, url), scope='ui', account=self.APPORO_ACCOUNT)
        release = target_for('apporoRelease')
        demo111 = 'https://demo111-odoo.woowtech.io'
        with self.assertRaisesRegex(ValueError, 'release packages are protected'):
            authorize_live(release, demo111, self.apporo_ui_env(release, demo111), scope='ui',
                           account=self.APPORO_ACCOUNT)

    def test_apporo_push_scope_remains_blocked_even_when_fully_flagged(self):
        target = target_for('apporoDebug')
        url = 'https://demo111-odoo.woowtech.io'
        env = self.apporo_ui_env(target, url)
        for kwargs in ({}, {'scope': 'push'}):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(ValueError, 'push tests BLOCKED'):
                authorize_live(target, url, env, account=self.APPORO_ACCOUNT, **kwargs)
        with self.assertRaisesRegex(ValueError, "'ui' or 'push'"):
            authorize_live(target, url, env, scope='all', account=self.APPORO_ACCOUNT)

    def test_woow_scopes_unchanged_and_apporo_flag_does_not_unlock_woow(self):
        target = target_for('woowtechDebug')
        url = 'https://demo222-odoo.woowtech.io'
        env = {'APP_VARIANT': target.variant, 'ALLOW_DEVICE_TEST_WRITES': target.package, 'ALLOW_ODOO_TEST_WRITES': url}
        for scope in ('ui', 'push'):
            authorize_live(target, url, env, scope=scope)
        with self.assertRaises(ValueError):
            authorize_live(target, url, {'APP_VARIANT': target.variant, 'ALLOW_APPORO_LIVE_UI': target.package,
                                         'ALLOW_ODOO_TEST_WRITES': url}, scope='ui')

    def test_only_ui_only_scripts_request_ui_scope_and_push_checks_skip_for_apporo(self):
        ui_only = {'verify-on-device.py', 'e2e_15_clockin_full.py'}
        for name in SCRIPTS:
            with self.subTest(script=name):
                text = (ROOT / 'scripts' / name).read_text()
                self.assertEqual(name in ui_only, 'require_live_test_authorization(scope="ui")' in text)
        verify = (ROOT / 'scripts/verify-on-device.py').read_text()
        self.assertIn('if not APPORO_PUSH_BLOCKED and os.path.exists(SA_FILE):', verify)
        self.assertIn('APPORO_PUSH_BLOCKED = APP_TARGET.brand == "apporo"', verify)
        config = (ROOT / 'scripts/test_config.py').read_text()
        self.assertIn('def require_live_test_authorization(scope="push"):', config)
        self.assertIn('scope=scope, account=ODOO_USER', config)

    def test_all_six_live_scripts_gate_before_device_or_network_statements(self):
        for name in SCRIPTS:
            with self.subTest(script=name):
                tree = ast.parse((ROOT / 'scripts' / name).read_text())
                gate_line = next(n.lineno for n in tree.body if isinstance(n, ast.Expr) and
                                 isinstance(n.value, ast.Call) and isinstance(n.value.func, ast.Name) and
                                 n.value.func.id == 'require_live_test_authorization')
                for node in tree.body:
                    if node.lineno >= gate_line or isinstance(node, (ast.Import, ast.ImportFrom)):
                        continue
                    # Only sys.path insertion/string docstrings are allowed ahead of the gate.
                    calls = [n for n in ast.walk(node) if isinstance(n, ast.Call)]
                    for call in calls:
                        self.assertNotRegex(ast.unparse(call.func), r'^(requests|subprocess|u2|d)\.')
                for n in ast.walk(tree):
                    if isinstance(n, ast.Constant) and isinstance(n.value, str):
                        self.assertNotEqual('io.woowtech.odoo.debug', n.value)

    def test_config_paths_and_namespace_are_explicit_and_secrets_not_dumped(self):
        text = (ROOT / 'scripts/test_config.py').read_text()
        self.assertIn('APP_ACTIVITY = "io.woowtech.odoo.ui.MainActivity"', text)
        self.assertIn('APP_PACKAGE != APP_TARGET.package', text)
        self.assertIn('FIREBASE_PROJECT_ID != APP_TARGET.firebase_project', text)
        self.assertIn('/ APP_VARIANT)', text)
        self.assertNotIn('globals()[k]', text)
        self.assertIn('if APP_TARGET.brand == "woowtech" else ""', text)

    def test_ci_builds_and_tests_both_flavors_without_publishing(self):
        text = (ROOT / '.github/workflows/build.yml').read_text()
        for variant in ('WoowtechDebug', 'ApporoDebug'):
            self.assertIn('task: ' + variant, text)
        self.assertIn(':app:test${{ matrix.task }}UnitTest', text)
        self.assertIn('contents: read', text)
        for forbidden in ('action-gh-release', 'contents: write', 'publish', 'upload-google-play'):
            self.assertNotIn(forbidden, text.replace('no publishing', ''))

    def test_all_python_and_xml_sources_parse_without_execution(self):
        for path in [*(ROOT / 'scripts').glob('*.py'), *(ROOT / 'scripts/tests').glob('*.py')]:
            ast.parse(path.read_text(), filename=str(path))
        for path in (ROOT / 'app/src').rglob('*.xml'):
            ET.parse(path)


class BackupExclusionContracts(unittest.TestCase):
    # pi 1001c P2: the encrypted session-id file (EncryptedKnownSessionStore) must never leave the device
    # in a backup or device transfer, exactly like encrypted_prefs.xml.
    def test_session_secrets_are_excluded_from_backup_and_transfer(self):
        res = ROOT / 'app/src/main/res/xml'
        backup = (res / 'backup_rules.xml').read_text()
        self.assertIn('<exclude domain="sharedpref" path="encrypted_prefs.xml" />', backup)
        self.assertIn('<exclude domain="sharedpref" path="known_sessions.xml" />', backup)
        extraction = (res / 'data_extraction_rules.xml').read_text()
        for section in ('cloud-backup', 'device-transfer'):
            body = extraction.split(f'<{section}>', 1)[1].split(f'</{section}>', 1)[0]
            with self.subTest(section=section):
                self.assertIn('<exclude domain="sharedpref" path="encrypted_prefs.xml" />', body)
                self.assertIn('<exclude domain="sharedpref" path="known_sessions.xml" />', body)
        store = (K / 'data/local/KnownSessionStore.kt').read_text()
        self.assertIn('const val FILE = "known_sessions"', store)


if __name__ == '__main__':
    unittest.main()
