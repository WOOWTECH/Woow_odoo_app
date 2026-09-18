package io.woowtech.odoo.data.push

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * EP-08F-Android: ambiguous tenant id must be REFUSED, not resolved by "take the first match".
 *
 * The server-side plugin's `notification_targeting.py` states the client contract explicitly:
 *
 * > Clients must COUNT and refuse an ambiguous value rather than take a first match
 *
 * and [DeepLinkRouter]'s own KDoc already promises:
 *
 * > Present-but-unresolved tenant id -> Drop, never a fall-back to the active account
 *
 * An ambiguous tenant id (two or more local accounts carrying the same value) is *unresolved*:
 * the router cannot know which account owns the notification, so picking `firstOrNull` silently
 * routes account B's notification into whichever account happens to sort first — the exact
 * cross-tenant leak the contract forbids.
 *
 * This is the Android half of the fix already landed on iOS (commit 69c2e66).
 */
class DeepLinkRouterAmbiguousTenantTest {

    private val accountA = RoutableAccount(id = "acc-A", tenantId = "tenant-DUP", serverHost = "a-odoo.woowtech.io")
    private val accountB = RoutableAccount(id = "acc-B", tenantId = "tenant-DUP", serverHost = "b-odoo.woowtech.io")

    private val allLoggedIn: (String) -> Boolean = { true }

    @Test
    fun `Given two accounts sharing one tenant id when route then drops instead of taking the first`() {
        val route = DeepLinkRouter.route(
            tenantId = "tenant-DUP",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(accountA, accountB),
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    @Test
    fun `Given a duplicate tenant id when route then order of the account list cannot change the outcome`() {
        // Same two accounts, reversed. A "first match" implementation flips its answer here;
        // a COUNT-and-refuse implementation drops both ways.
        val route = DeepLinkRouter.route(
            tenantId = "tenant-DUP",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(accountB, accountA),
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    @Test
    fun `Given a duplicate tenant id where only one of the two is logged in when route then still drops`() {
        // Tempting shortcut: "disambiguate by login state". The contract says refuse an ambiguous
        // VALUE — login state is not identity, and the logged-out account may log back in.
        val route = DeepLinkRouter.route(
            tenantId = "tenant-DUP",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(accountA, accountB),
            isLoggedIn = { it == "acc-B" },
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    @Test
    fun `Given exactly one account with the tenant id when route then still resolves (no over-correction)`() {
        // Guard against fixing ambiguity by dropping everything: the unambiguous case must survive.
        val unique = RoutableAccount(id = "acc-C", tenantId = "tenant-C", serverHost = "c-odoo.woowtech.io")

        val route = DeepLinkRouter.route(
            tenantId = "tenant-C",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(accountA, accountB, unique),
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.SwitchAndApply::class.java, route)
        route as DeepLinkRoute.SwitchAndApply
        assert(route.accountId == "acc-C")
    }

    @Test
    fun `Given duplicate NULL tenant ids when route with a real tenant id then drops as unresolved`() {
        // Several accounts may legitimately have a null tenantId (never registered). That is not
        // an ambiguity of the queried value — it is simply no match — but it must still Drop.
        val nullA = accountA.copy(tenantId = null)
        val nullB = accountB.copy(tenantId = null)

        val route = DeepLinkRouter.route(
            tenantId = "tenant-DUP",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(nullA, nullB),
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }
}
