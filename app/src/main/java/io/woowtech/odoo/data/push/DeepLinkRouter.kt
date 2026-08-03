package io.woowtech.odoo.data.push

/**
 * Minimal view of a local account needed to route a notification deep link. Kept free of Room /
 * Android types so [DeepLinkRouter] stays a pure, exhaustively unit-testable function.
 *
 * @property id local account id
 * @property tenantId opaque tenant id previously registered for this account, or null if unknown
 * @property serverHost the bare host of the account's Odoo server (no scheme, no path)
 */
data class RoutableAccount(
    val id: String,
    val tenantId: String?,
    val serverHost: String,
    /**
     * The ACCOUNT-scoped routing key — this account's `woow.fcm.device` row id (P2-9).
     *
     * Null until the next successful FCM registration, which is why [tenantId] remains the
     * fallback rather than being replaced.
     */
    val deviceId: String? = null,
)

/**
 * The decision produced by [DeepLinkRouter.route]. Callers must handle every branch — there is
 * deliberately no default, so a new outcome cannot be silently ignored.
 */
sealed interface DeepLinkRoute {

    /**
     * The link resolved to a specific account: switch to [accountId], then queue [url] bound to it.
     * Used for both cross-account and same-account taps.
     */
    data class SwitchAndApply(val accountId: String, val url: String) : DeepLinkRoute

    /**
     * Backward-compatible path: the payload carried no tenant id (old plugin). Apply [url] to
     * whichever account is currently active, exactly as the app behaved before tenant routing.
     */
    data class ApplyToActive(val url: String) : DeepLinkRoute

    /**
     * The link must be discarded and the active account left untouched. [reason] is for logging
     * only. This is returned for an unresolved tenant id, a target account that is not logged in,
     * and a link that fails host validation — never a fall-back to the active account.
     */
    data class Drop(val reason: String) : DeepLinkRoute
}

/**
 * Resolves which account (if any) a push-notification deep link belongs to, using the opaque
 * tenant id carried in the FCM payload.
 *
 * The frozen security contract this encodes:
 * - **Present-but-unresolved tenant id -> [DeepLinkRoute.Drop]**, never a fall-back to the active
 *   account (that would leak account B's notification into account A).
 * - **Target account not logged in -> [DeepLinkRoute.Drop]**.
 * - **Link fails [DeepLinkValidator] against the resolved account's host -> [DeepLinkRoute.Drop]**.
 * - **Missing tenant id (old plugin) -> [DeepLinkRoute.ApplyToActive]**, preserving prior behaviour.
 */
object DeepLinkRouter {

    /**
     * @param deviceId the ACCOUNT-scoped routing key from the FCM payload, or null when the server
     *   is older than this key. Preferred over [tenantId] whenever present — see below.
     * @param tenantId the opaque tenant id from the FCM payload, or null/blank for old-plugin payloads
     * @param actionUrl the relative Odoo deep-link path from the payload
     * @param accounts all locally known accounts
     * @param isLoggedIn predicate returning true when the given account id has usable credentials
     * @return the routing decision; see [DeepLinkRoute]
     */
    fun route(
        deviceId: String? = null,
        tenantId: String?,
        actionUrl: String,
        accounts: List<RoutableAccount>,
        isLoggedIn: (String) -> Boolean,
    ): DeepLinkRoute {
        if (actionUrl.isBlank()) {
            return DeepLinkRoute.Drop("blank action url")
        }

        // P2-9 root cause: prefer the ACCOUNT-scoped key when the server sent one.
        //
        // A tenant id names a TENANT — the server resolves it to the Odoo database name — so
        // two users on ONE database share it unavoidably and it cannot select between them.
        // The device row id is unique per (fcm_token, user_id) by construction, which is the
        // whole reason it was added to the payload.
        //
        // A device id that matches NOTHING drops rather than falling back to the tenant id.
        // Falling back would re-introduce exactly the guess this key exists to remove, and an
        // unmatched id means our stored row is stale — not that the server failed to identify
        // the account.
        if (!deviceId.isNullOrBlank()) {
            val target = accounts.firstOrNull { it.deviceId == deviceId }
                ?: return DeepLinkRoute.Drop("unresolved device id")
            if (!isLoggedIn(target.id)) {
                return DeepLinkRoute.Drop("target account not logged in")
            }
            if (!DeepLinkValidator.isValid(url = actionUrl, serverHost = target.serverHost)) {
                return DeepLinkRoute.Drop("action url failed validation for target host")
            }
            return DeepLinkRoute.SwitchAndApply(accountId = target.id, url = actionUrl)
        }

        // Old-plugin payload: no tenant id at all -> current behaviour (host-validated against the
        // active account by the caller).
        if (tenantId.isNullOrBlank()) {
            return DeepLinkRoute.ApplyToActive(actionUrl)
        }

        // Story 8-1 (P2-9): an AMBIGUOUS tenant id must be refused, never guessed.
        //
        // `odoo_tenant_id` defaults to the Odoo database name, and spec §4.3 ships every
        // box with the same POSTGRES_DB unless an operator overrides it — so two customer
        // servers routinely produce two local accounts with an IDENTICAL tenant id. The
        // previous `firstOrNull` made the target depend on the order the DAO happened to
        // return rows in, so a legitimate push from one server could open the other one's
        // account. That needs no attacker; it is the default deployment as written.
        //
        // Dropping is the correct trade because acting on a wrong guess opens ANOTHER
        // server's session and resolves the payload's record id in the wrong database. On a
        // colliding deployment a deep link now does nothing instead of doing the wrong thing.
        //
        // ⚠️ This does NOT make routing correct — it makes it safe. `odoo_tenant_id` names a
        // TENANT (the plugin resolves it to the database name: "one database == one
        // tenant/box"), while this function selects an ACCOUNT. Two users on ONE database are
        // two local accounts that NECESSARILY share the id, so their deep links are dropped
        // too — a real functional loss for a supported configuration. The client cannot do
        // better: the payload carries no account identity. Fixing it properly requires the
        // server to stamp something account-scoped. Recorded in story 8-1's follow-ups.
        val matches = accounts.filter { it.tenantId == tenantId }
        if (matches.size > 1) {
            return DeepLinkRoute.Drop("ambiguous tenant id (${matches.size} accounts share it)")
        }
        val target = matches.firstOrNull()
            ?: return DeepLinkRoute.Drop("unresolved tenant id")

        if (!isLoggedIn(target.id)) {
            return DeepLinkRoute.Drop("target account not logged in")
        }

        if (!DeepLinkValidator.isValid(url = actionUrl, serverHost = target.serverHost)) {
            return DeepLinkRoute.Drop("action url failed validation for target host")
        }

        return DeepLinkRoute.SwitchAndApply(accountId = target.id, url = actionUrl)
    }
}
