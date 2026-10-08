package io.woowtech.odoo.ui.main

import android.webkit.WebViewClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * W2-4 L1 (Pixel 7a, Play vc5): a cold start without network showed Chromium's "Webpage not available"
 * page with the server address, no retry, and no reload when the network came back. Now a main-frame
 * connection error shows the app's offline screen, Retry reloads the page that failed, and a network
 * recovery retries once by itself.
 */
class WebViewOfflineTest {

    private val base = "https://odoo.example.test/web?db=prod"
    private val onHost: (String) -> Boolean = { it.startsWith("https://odoo.example.test/") }

    @Test
    fun `Given main frame connection errors then the offline screen is shown`() {
        for (code in listOf(WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO, WebViewClient.ERROR_UNKNOWN, WebViewClient.ERROR_FAILED_SSL_HANDSHAKE)) {
            assertTrue(MainFrameErrorPolicy.showsOffline(isForMainFrame = true, errorCode = code), "code $code")
        }
    }

    @Test
    fun `Given a subresource error or a non connection error then no offline screen`() {
        assertFalse(MainFrameErrorPolicy.showsOffline(isForMainFrame = false, errorCode = WebViewClient.ERROR_HOST_LOOKUP))
        assertFalse(MainFrameErrorPolicy.showsOffline(isForMainFrame = true, errorCode = WebViewClient.ERROR_BAD_URL))
        assertFalse(MainFrameErrorPolicy.showsOffline(isForMainFrame = true, errorCode = WebViewClient.ERROR_UNSUPPORTED_SCHEME))
        assertFalse(MainFrameErrorPolicy.showsOffline(isForMainFrame = true, errorCode = WebViewClient.ERROR_REDIRECT_LOOP))
    }

    @Test
    fun `Given a main frame error when its error page finishes then the screen stays and the page is not loaded`() {
        val state = WebViewOfflineState(base)
        state.onMainFrameError(base)
        assertTrue(state.isOffline)
        assertTrue(state.onPageFinished(), "the error page must not count as a loaded page")
        assertTrue(state.isOffline)
    }

    @Test
    fun `Given offline when retried and the page loads then the screen goes away`() {
        val state = WebViewOfflineState(base)
        state.onMainFrameError("https://odoo.example.test/odoo/discuss")
        state.onPageFinished()
        assertEquals("https://odoo.example.test/odoo/discuss", state.retryUrl(onHost))
        assertTrue(state.isOffline, "stays until the retried page really loads")
        assertFalse(state.onPageFinished())
        assertFalse(state.isOffline)
    }

    @Test
    fun `Given offline when the retry fails again then the screen stays`() {
        val state = WebViewOfflineState(base)
        state.onMainFrameError(base)
        state.onPageFinished()
        state.retryUrl(onHost)
        state.onMainFrameError(base)
        assertTrue(state.onPageFinished())
        assertTrue(state.isOffline)
    }

    @Test
    fun `Given the failed URL is not on the account server or unknown when retried then the base page loads`() {
        val foreign = WebViewOfflineState(base)
        foreign.onMainFrameError("https://elsewhere.test/")
        assertEquals(base, foreign.retryUrl(onHost))
        val unknown = WebViewOfflineState(base)
        unknown.onMainFrameError(null)
        assertEquals(base, unknown.retryUrl(onHost))
    }

    @Test
    fun `Given online when asked to retry then nothing is loaded`() {
        val state = WebViewOfflineState(base)
        assertNull(state.retryUrl(onHost))
        assertFalse(state.onPageFinished())
        assertFalse(state.isOffline)
    }

    @Test
    fun `Given no network when one becomes available then one recovery is counted`() {
        val tracker = NetworkRecoveryTracker<String>()
        tracker.start(null)
        assertFalse(tracker.usable)
        tracker.onAvailable("wifi")
        assertTrue(tracker.usable)
        assertEquals(1, tracker.recoveries)
        tracker.onAvailable("wifi")
        assertEquals(1, tracker.recoveries, "already usable: no second recovery")
    }

    @Test
    fun `Given a usable network at start when its callbacks replay then no recovery`() {
        val tracker = NetworkRecoveryTracker<String>()
        tracker.start("wifi")
        tracker.onAvailable("wifi")
        tracker.onBlockedStatusChanged("wifi", false)
        assertEquals(0, tracker.recoveries)
    }

    @Test
    fun `Given the app is blocked when it is unblocked then that is a recovery`() {
        val tracker = NetworkRecoveryTracker<String>()
        tracker.start(null) // activeNetwork is null for a blocked app
        tracker.onAvailable("wifi")
        tracker.onBlockedStatusChanged("wifi", true)
        assertFalse(tracker.usable, "the screen effect waits, so this short-lived 'usable' is not retried")
        val before = tracker.recoveries
        tracker.onBlockedStatusChanged("wifi", false)
        assertTrue(tracker.usable)
        assertEquals(before + 1, tracker.recoveries)
    }

    @Test
    fun `Given the default network switched when the old one is lost late then still usable`() {
        val tracker = NetworkRecoveryTracker<String>()
        tracker.start("wifi")
        tracker.onAvailable("cell")
        tracker.onLost("wifi")
        assertTrue(tracker.usable)
        tracker.onLost("cell")
        assertFalse(tracker.usable)
        tracker.onAvailable("wifi")
        assertEquals(1, tracker.recoveries)
    }

    @Test
    fun `Given a blocked status for another network then it is ignored`() {
        val tracker = NetworkRecoveryTracker<String>()
        tracker.start("wifi")
        tracker.onBlockedStatusChanged("cell", true)
        assertTrue(tracker.usable)
    }
}
