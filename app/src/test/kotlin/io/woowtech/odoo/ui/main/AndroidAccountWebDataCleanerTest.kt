package io.woowtech.odoo.ui.main

import io.woowtech.odoo.data.local.WebViewCookieOwnerStore
import io.woowtech.odoo.data.push.DeepLinkManager
import io.woowtech.odoo.data.repository.WebDataRemoval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** iOS D1 parity (2026-09-30): the WebView side of logout / account removal. */
class AndroidAccountWebDataCleanerTest {
    @TempDir
    lateinit var dir: File

    private class Store : WebViewCookieStore {
        var cookies = mutableMapOf<String, String>()
        var removals = 0
        var flushes = 0
        var pending: (() -> Unit)? = null
        var holdRemoval = false
        override fun getCookie(url: String) = cookies[url]
        override fun removeAllCookies(onDone: () -> Unit) {
            removals++
            val finish = { cookies.clear(); onDone() }
            if (holdRemoval) pending = finish else finish()
        }
        override fun setCookie(url: String, value: String) { cookies[url] = value }
        override fun flush() { flushes++ }
    }

    private val store = Store()
    private val coordinator = WebViewCookieCoordinator()
    private val deletedOrigins = mutableListOf<String>()
    private var everything = 0
    private val links = DeepLinkManager()

    private fun cleaner(owner: WebViewCookieOwnerStore) = AndroidAccountWebDataCleaner(
        owner, links, store, coordinator,
        deleteOriginStorage = { deletedOrigins += it },
        clearEverything = { everything++ },
        mainDispatcher = Dispatchers.Unconfined,
    )

    private fun owner(accountId: String?) = WebViewCookieOwnerStore(File(dir, "owner")).also {
        if (accountId != null) it.recordInstalled(accountId, "sid")
    }

    @Test
    fun `Given the WebView cookies are B's then B's session id is read back, and nobody else's`() = runBlocking {
        store.cookies["https://fixture.test"] = "frontend_lang=en_US; session_id=b-web-sid"
        val cleaner = cleaner(owner("b"))
        assertEquals("b-web-sid", cleaner.webViewSessionIdOf("b", "https://fixture.test"))
        assertNull(cleaner.webViewSessionIdOf("a", "https://fixture.test"))
    }

    @Test
    fun `Given cookie removal then owner is cleared, cookies removed and flushed through the shared sequencer, and B's link dropped`() = runBlocking {
        val owner = owner("b")
        links.setPending("/web#action=1", "b")
        store.cookies["https://fixture.test"] = "session_id=b-web-sid"
        val displayedB = coordinator.beginTarget()

        cleaner(owner).removeAccountData("b", "https://fixture.test", WebDataRemoval(cookies = true, originStorage = false, everything = false))

        assertNull(owner.ownerAccountId())
        assertTrue(store.cookies.isEmpty())
        assertEquals(1, store.flushes)
        assertNull(links.pending.value)
        assertFalse(coordinator.isCurrent(displayedB)) // B's still-displayed composition can no longer act
        assertTrue(deletedOrigins.isEmpty()) // another account still uses the origin
    }

    @Test
    fun `Given a newer account target starts before the queued removal then the removal is dropped and cannot flush over it`() = runBlocking {
        store.holdRemoval = true
        // Something else is running on the shared sequencer, so the logout removal has to queue.
        coordinator.sequencer.enqueue({ true }) { done -> store.removeAllCookies { done() } }
        cleaner(owner("b")).removeAccountData("b", "https://fixture.test", WebDataRemoval(cookies = true, originStorage = false, everything = false))
        coordinator.beginTarget() // account A's composition commits
        store.holdRemoval = false
        store.pending!!.invoke()
        assertEquals(1, store.removals) // the logout removal never started; A's own job clears cookies
    }

    @Test
    fun `Given the origin is no longer used then only its site storage is deleted, with a default port omitted`() = runBlocking {
        val links = links.also { it.setPending("/web", "a") }
        cleaner(owner("a")).removeAccountData("b", "https://fixture.test", WebDataRemoval(cookies = false, originStorage = true, everything = false))
        cleaner(owner("a")).removeAccountData("c", "https://other.test:8443", WebDataRemoval(cookies = false, originStorage = true, everything = false))
        assertEquals(listOf("https://fixture.test", "https://other.test:8443"), deletedOrigins)
        assertEquals(0, store.removals) // cookies belong to the displayed account and stay
        assertEquals("a", links.pending.value?.accountId) // other accounts' links survive
    }

    @Test
    fun `Given no account remains then everything is cleared`() = runBlocking {
        cleaner(owner("a")).removeAccountData("a", "https://fixture.test", WebDataRemoval(cookies = true, originStorage = true, everything = true))
        assertEquals(1, everything)
        assertEquals(1, store.removals)
        assertTrue(deletedOrigins.isEmpty())
    }
}
