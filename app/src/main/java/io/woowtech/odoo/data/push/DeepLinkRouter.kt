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
     * @param tenantId the opaque tenant id from the FCM payload, or null/blank for old-plugin payloads
     * @param actionUrl the relative Odoo deep-link path from the payload
     * @param accounts all locally known accounts
     * @param isLoggedIn predicate returning true when the given account id has usable credentials
     * @return the routing decision; see [DeepLinkRoute]
     */
    fun route(
        tenantId: String?,
        actionUrl: String,
        accounts: List<RoutableAccount>,
        isLoggedIn: (String) -> Boolean,
    ): DeepLinkRoute {
        if (actionUrl.isBlank()) {
            return DeepLinkRoute.Drop("blank action url")
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
        // Dropping is the correct trade because the switch is DESTRUCTIVE: the caller's
        // `switchAccount` unregisters the previously-active account's FCM token, so acting
        // on a wrong guess silently kills push for an unrelated account. On a colliding
        // deployment a deep link now does nothing instead of doing the wrong thing.
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
