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
}
