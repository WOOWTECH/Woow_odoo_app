package io.woowtech.odoo.ui.main

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.woowtech.odoo.R
import io.woowtech.odoo.ui.theme.brandSolidButtonColors
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Which WebView errors replace the page with the app's own offline screen (W2-4 L1, 2026-10-08).
 *
 * Before, a cold start without network showed Chromium's "Webpage not available … net::ERR_NAME_NOT_RESOLVED"
 * page, with the server address, no retry and no way back except leaving the app. Only a MAIN-FRAME
 * failure to reach the server counts: a failed image, script or XHR of a loaded Odoo page is not "offline",
 * and HTTP error statuses or bad URLs are not connection problems.
 */
internal object MainFrameErrorPolicy {
    // Chromium maps ERR_INTERNET_DISCONNECTED / ERR_NETWORK_CHANGED and similar to ERROR_UNKNOWN.
    private val CONNECTION_ERRORS = setOf(
        WebViewClient.ERROR_UNKNOWN,
        WebViewClient.ERROR_HOST_LOOKUP,
        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_IO,
        WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
    )

    fun showsOffline(isForMainFrame: Boolean, errorCode: Int): Boolean =
        isForMainFrame && errorCode in CONNECTION_ERRORS
}

/**
 * Offline state of ONE account target's WebView (it lives inside that target's `key(target)` block, so a
 * replaced account's WebView can never show or clear the displayed account's offline screen). Main thread.
 *
 * Chromium may report the main-frame error before or after the error page's navigation starts, but always
 * before that error page finishes; so the error is remembered until the next finished page, which is then
 * the error page and not a real one. A finished page without a pending error is a real page: back online.
 *
 * @param fallbackUrl the account's base page, retried when the failed URL is not on the account's server.
 */
internal class WebViewOfflineState(private val fallbackUrl: String) {
    /** Whether the offline screen covers the WebView. */
    var isOffline by mutableStateOf(false)
        private set

    private var failedUrl: String? = null
    private var errorPending = false

    /** A main-frame connection error ([MainFrameErrorPolicy]) for [url]. */
    fun onMainFrameError(url: String?) {
        failedUrl = url
        errorPending = true
        isOffline = true
    }

    /** A page finished; returns true when it is the error page of a pending main-frame error. */
    fun onPageFinished(): Boolean {
        if (errorPending) {
            errorPending = false
            return true
        }
        isOffline = false
        return false
    }

    /**
     * The URL to load again while offline — the one that failed when [isOnTargetHost] says it belongs to
     * this account's server, otherwise [fallbackUrl] — or null when not offline. The screen stays until
     * that load finished as a real page.
     */
    fun retryUrl(isOnTargetHost: (String) -> Boolean): String? {
        if (!isOffline) return null
        errorPending = false
        return failedUrl?.takeIf(isOnTargetHost) ?: fallbackUrl
    }
}

/**
 * Follows the app's default network ([android.net.ConnectivityManager.registerDefaultNetworkCallback]) and
 * counts recoveries: each change from "no usable network" to "usable" bumps [recoveries] once, which the
 * offline screen turns into one automatic retry. A network counts as usable while it is the default one and
 * not blocked for this app (data saver, `cmd connectivity set-package-networking-enabled false`, …).
 * Keyed by the network so a late `onLost` of a replaced default network does not hide its successor.
 * Main thread only. [N] is `android.net.Network` in the app, any type in tests.
 */
internal class NetworkRecoveryTracker<N : Any> {
    private var current: N? = null
    private var blocked = false

    /** Whether the app has a usable default network right now. */
    val usable: Boolean get() = current != null && !blocked

    /** Number of unusable → usable changes seen; Compose state so an effect can react to it. */
    var recoveries by mutableIntStateOf(0)
        private set

    /** The default network when the watch started (`ConnectivityManager.activeNetwork`, null when none or blocked). */
    fun start(activeNetwork: N?) {
        current = activeNetwork
        blocked = false
    }

    fun onAvailable(network: N) = update { current = network; blocked = false }

    fun onLost(network: N) = update { if (network == current) { current = null; blocked = false } }

    fun onBlockedStatusChanged(network: N, isBlocked: Boolean) = update { if (network == current) blocked = isBlocked }

    private inline fun update(change: () -> Unit) {
        val before = usable
        change()
        if (!before && usable) recoveries += 1
    }
}

/**
 * The app's own offline screen over an account's WebView: brand colours, localized text, a Retry button;
 * never the error code or the server address. [onRetry] reloads the page that failed.
 */
@Composable
internal fun WebViewOfflineScreen(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag(WEBVIEW_OFFLINE_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.offline_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.offline_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry, colors = brandSolidButtonColors()) {
                Text(text = stringResource(R.string.retry))
            }
        }
    }
}

internal const val WEBVIEW_OFFLINE_TAG = "webview_offline"

/** Short settle time: a network that appears and is blocked right away (app-level block) is not retried. */
private const val NETWORK_RECOVERY_SETTLE_MS = 750L

/**
 * While composed, watches the default network and calls [onRecovered] once per recovery
 * ([NetworkRecoveryTracker]) that is still usable after a short settle time.
 */
@Composable
internal fun NetworkRecoveryEffect(onRecovered: () -> Unit) {
    val context = LocalContext.current
    val currentOnRecovered by rememberUpdatedState(onRecovered)
    val tracker = remember { NetworkRecoveryTracker<Network>() }
    DisposableEffect(context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = tracker.onAvailable(network)
            override fun onLost(network: Network) = tracker.onLost(network)
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) =
                tracker.onBlockedStatusChanged(network, blocked)
        }
        val registered = connectivity != null && runCatching {
            tracker.start(connectivity.activeNetwork)
            connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        }.onFailure { Timber.w("Network watch unavailable: %s", it.message) }.isSuccess
        onDispose {
            if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }
    val recoveries = tracker.recoveries
    LaunchedEffect(recoveries) {
        if (recoveries == 0) return@LaunchedEffect
        delay(NETWORK_RECOVERY_SETTLE_MS)
        if (tracker.usable) {
            Timber.d("Network recovered — retrying the offline page")
            currentOnRecovered()
        }
    }
}
