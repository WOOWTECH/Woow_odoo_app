package io.woowtech.odoo.data.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [DeepLinkRouter] — the pure resolution of a push-notification deep link to an account
 * by opaque tenant id. This is the P0 cross-tenant isolation logic.
 *
 * Account A is the "active"/attacker-adjacent account; account B is the notification's true owner.
 * The negative invariant under test: a link that belongs to B (or to nobody) must NEVER resolve to
 * A.
 */
class DeepLinkRouterTest {

    private val accountA = RoutableAccount(id = "acc-A", tenantId = "tenant-A", serverHost = "a-odoo.woowtech.io")
    private val accountB = RoutableAccount(id = "acc-B", tenantId = "tenant-B", serverHost = "b-odoo.woowtech.io")
    private val accounts = listOf(accountA, accountB)

    private val allLoggedIn: (String) -> Boolean = { true }

    @Test
    fun `Given active A and tenant B when route then switches to B never A`() {
        val route = DeepLinkRouter.route(
            tenantId = "tenant-B",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.SwitchAndApply::class.java, route)
        route as DeepLinkRoute.SwitchAndApply
        assertEquals("acc-B", route.accountId)
        // Negative assertion: the resolved account is never A.
        assertTrue(route.accountId != "acc-A")
    }

    @Test
    fun `Given tenant A and A's own url when route then switches to A (same-account regression)`() {
        val route = DeepLinkRouter.route(
            tenantId = "tenant-A",
            actionUrl = "/web#active_id=mail.channel_1",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.SwitchAndApply::class.java, route)
        assertEquals("acc-A", (route as DeepLinkRoute.SwitchAndApply).accountId)
    }

    @Test
    fun `Given unresolved tenant id when route then drops never falls back to active account`() {
        val route = DeepLinkRouter.route(
            tenantId = "tenant-UNKNOWN",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    @Test
    fun `Given resolved tenant B but B not logged in when route then drops`() {
        val route = DeepLinkRouter.route(
            tenantId = "tenant-B",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = accounts,
            isLoggedIn = { it != "acc-B" }, // B is known locally but not logged in
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    @Test
    fun `Given no tenant id (old plugin) when route then applies to active account`() {
        val route = DeepLinkRouter.route(
            tenantId = null,
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.ApplyToActive::class.java, route)
        assertEquals("/web#active_id=mail.channel_7", (route as DeepLinkRoute.ApplyToActive).url)
    }

    @Test
    fun `Given blank tenant id when route then applies to active account`() {
        val route = DeepLinkRouter.route(
            tenantId = "   ",
            actionUrl = "/web#id=1",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.ApplyToActive::class.java, route)
    }

    @Test
    fun `Given resolved tenant B but url points to a foreign host when route then drops`() {
        // A malformed / hostile action URL that would target A's host must not be applied to B.
        val route = DeepLinkRouter.route(
            tenantId = "tenant-B",
            actionUrl = "https://a-odoo.woowtech.io/web#id=1",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    @Test
    fun `Given resolved tenant B and absolute url on B's own host when route then switches to B`() {
        val route = DeepLinkRouter.route(
            tenantId = "tenant-B",
            actionUrl = "https://b-odoo.woowtech.io/web#id=1",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.SwitchAndApply::class.java, route)
        assertEquals("acc-B", (route as DeepLinkRoute.SwitchAndApply).accountId)
    }

    @Test
    fun `Given blank action url when route then drops`() {
        val route = DeepLinkRouter.route(
            tenantId = "tenant-B",
            actionUrl = "",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    @Test
    fun `Given tenant B but B has no persisted tenant id yet when route then drops`() {
        // B is present locally but has never registered a tenant id (null) — cannot be matched.
        val accountsWithNullTenant = listOf(accountA, accountB.copy(tenantId = null))

        val route = DeepLinkRouter.route(
            tenantId = "tenant-B",
            actionUrl = "/web#id=1",
            accounts = accountsWithNullTenant,
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    // ---------------------------------------------------------------------
    // Story 8-1 (P2-9) — an ambiguous tenant id must be refused, not guessed
    // ---------------------------------------------------------------------
    //
    // `odoo_tenant_id` defaults to the Odoo DATABASE NAME, and spec §4.3 ships every
    // box with the same POSTGRES_DB unless an operator overrides it. Two customer
    // servers therefore produce two local accounts with an IDENTICAL tenantId, and
    // `firstOrNull` made routing depend on DAO row order — a legitimate push from
    // server Y opening server X's account. No attacker required; that is the default
    // deployment behaving exactly as written.
    //
    // It matters because acting on a wrong guess opens ANOTHER server's session and
    // resolves the payload's record id in the wrong database. (It USED to be worse still:
    // `switchAccount` unregistered the previously-active account's FCM token, so a
    // mis-routed tap killed push for an unrelated account. Story 8-1 removed that side
    // effect in the same change, so this comment is history, not current behaviour.)

    private val collidingX =
        RoutableAccount(id = "acc-X", tenantId = "odoo18_ecpay", serverHost = "x-odoo.woowtech.io")
    private val collidingY =
        RoutableAccount(id = "acc-Y", tenantId = "odoo18_ecpay", serverHost = "y-odoo.woowtech.io")

    @Test
    fun `Given two accounts sharing a tenant id when route then drops rather than guessing`() {
        val route = DeepLinkRouter.route(
            tenantId = "odoo18_ecpay",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(collidingX, collidingY),
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
        route as DeepLinkRoute.Drop
        assertTrue(
            route.reason.contains("ambiguous"),
            "the drop must name ambiguity so an operator can tell it from an unknown tenant: ${route.reason}",
        )
    }

    @Test
    fun `Given a colliding tenant id when route then never resolves to either candidate`() {
        // The safety property stated negatively: whichever order the DAO returns rows
        // in, no SwitchAndApply may be produced. A test asserting "it picks X" would
        // pass with the bug present, since the bug is that the pick is arbitrary.
        for (order in listOf(listOf(collidingX, collidingY), listOf(collidingY, collidingX))) {
            val route = DeepLinkRouter.route(
                tenantId = "odoo18_ecpay",
                actionUrl = "/web#active_id=mail.channel_7",
                accounts = order,
                isLoggedIn = allLoggedIn,
            )
            assertTrue(
                route !is DeepLinkRoute.SwitchAndApply,
                "row order decided the target — routing is non-deterministic: $route",
            )
        }
    }

    @Test
    fun `Given a colliding pair plus an unrelated account when route to the unique one then still switches`() {
        // The refusal must be scoped to the ambiguous id, not poison the whole list.
        val route = DeepLinkRouter.route(
            tenantId = "tenant-B",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(collidingX, collidingY, accountB),
            isLoggedIn = allLoggedIn,
        )

        assertInstanceOf(DeepLinkRoute.SwitchAndApply::class.java, route)
        assertEquals("acc-B", (route as DeepLinkRoute.SwitchAndApply).accountId)
    }

    // ---------------------------------------------------------------------
    // P2-9 root cause — an ACCOUNT-scoped key ends the ambiguity at the source
    // ---------------------------------------------------------------------
    //
    // Dropping an ambiguous tenant id is safe and costs those users their deep links.
    // The server now stamps the `woow.fcm.device` row id on every push, which is unique
    // per (token, user) BY CONSTRUCTION — so the two-users-on-one-database case that no
    // client-side fix could resolve becomes routable again.

    private val aliceOnShared = RoutableAccount(
        id = "acc-alice", tenantId = "odoo18_ecpay", serverHost = "shared.woowtech.io",
        deviceId = "101",
    )
    private val bobOnShared = RoutableAccount(
        id = "acc-bob", tenantId = "odoo18_ecpay", serverHost = "shared.woowtech.io",
        deviceId = "102",
    )

    @Test
    fun `Given two users on one database when the device id is present then it routes to the right one`() {
        val route = DeepLinkRouter.route(
            deviceId = "102",
            tenantId = "odoo18_ecpay",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(aliceOnShared, bobOnShared),
            isLoggedIn = allLoggedIn,
        )
        assertInstanceOf(DeepLinkRoute.SwitchAndApply::class.java, route)
        assertEquals("acc-bob", (route as DeepLinkRoute.SwitchAndApply).accountId)
    }

    @Test
    fun `Given a device id that matches nothing then it drops rather than falling back to the ambiguous tenant`() {
        // Falling back would re-introduce the guess this key exists to remove — and the
        // server sent a device id, so an unmatched one means our row is stale, not absent.
        val route = DeepLinkRouter.route(
            deviceId = "999",
            tenantId = "odoo18_ecpay",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(aliceOnShared, bobOnShared),
            isLoggedIn = allLoggedIn,
        )
        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }

    @Test
    fun `Given no device id in the payload then the tenant-id path is unchanged`() {
        // Older server, or an account registered before this shipped. Must not regress.
        val route = DeepLinkRouter.route(
            deviceId = null,
            tenantId = "tenant-B",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = accounts,
            isLoggedIn = allLoggedIn,
        )
        assertInstanceOf(DeepLinkRoute.SwitchAndApply::class.java, route)
        assertEquals("acc-B", (route as DeepLinkRoute.SwitchAndApply).accountId)
    }

    @Test
    fun `Given a device id but the account is not logged in then it drops`() {
        val route = DeepLinkRouter.route(
            deviceId = "102",
            tenantId = "odoo18_ecpay",
            actionUrl = "/web#active_id=mail.channel_7",
            accounts = listOf(aliceOnShared, bobOnShared),
            isLoggedIn = { it != "acc-bob" },
        )
        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
    }
}
