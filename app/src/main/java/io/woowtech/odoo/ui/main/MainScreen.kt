package io.woowtech.odoo.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.Message
import android.provider.MediaStore
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import io.woowtech.odoo.R
import io.woowtech.odoo.data.location.LocationPermissionGate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/** Holds a pending geolocation permission request while the runtime OS dialog is showing. */
private data class PendingGeolocationRequest(
    val origin: String?,
    val callback: GeolocationPermissions.Callback,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onMenuClick: () -> Unit
) {
    val account by viewModel.activeAccount.collectAsStateWithLifecycle(initialValue = null)
    val pendingDeepLink by viewModel.pendingDeepLink.collectAsStateWithLifecycle(initialValue = null)
    // WI-3: observe the re-login signal (previously emitted into the void). It fires when a session
    // recovery is unrecoverable — bad/missing stored credentials — from either the WebView self-heal
    // or the background FCM re-auth path. Surface the re-login surface once, then clear it so it can
    // never loop.
    val reloginRequest by viewModel.reloginRequest.collectAsStateWithLifecycle(initialValue = null)
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(reloginRequest) {
        if (reloginRequest != null) {
            viewModel.clearReloginRequest()
            onMenuClick()
        }
    }

    // Cache the active account host so we never need to suspend on the WebChromeClient
    // callback thread. Updated whenever the active account changes.
    var activeHostSnapshot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account) {
        activeHostSnapshot = account?.fullServerUrl?.let { url ->
            runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
        }
        // Defence in depth: as soon as an account becomes active, drop any pending deep link that
        // was queued for a different account so it can never leak into this one.
        account?.id?.let { viewModel.dropForeignPendingDeepLink(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
        }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // WI-1: Tracks whether the app is currently allowed to display notifications. Re-checked on
    // every ON_RESUME so the denial banner disappears the moment the user enables notifications
    // from system settings (below API 33 this is always true once the channel exists).
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    // Allows the user to dismiss the denial banner for the current session.
    var notificationBannerDismissed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // WI-1: Runtime POST_NOTIFICATIONS launcher (Android 13+ / API 33). Mirrors the location
    // launcher idiom below. Refreshes the enabled state on the result so the banner reflects the
    // user's choice immediately.
    val postNotificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsEnabled =
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        Timber.d("POST_NOTIFICATIONS result: granted=%s", granted)
    }

    // WI-1: On first composition only, auto-launch the OS permission dialog at most once per
    // install — only on API 33+, only when the permission is not already granted, and only when the
    // persisted "already asked" flag is false. Below API 33 this is a no-op (permission does not
    // exist; notifications work without it).
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect

        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!alreadyGranted && !viewModel.wasNotificationPermissionRequested()) {
            viewModel.markNotificationPermissionRequested()
            postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top toolbar
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            actions = {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = stringResource(R.string.content_description_menu)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // WI-1: Denial affordance. Shown only while notifications are blocked at the app level and
        // the user has not dismissed it this session. Its action deep-links to the system
        // app-notification settings so a denied user can still enable notifications.
        if (!notificationsEnabled && !notificationBannerDismissed) {
            NotificationPermissionBanner(
                onEnableClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                },
                onDismissClick = { notificationBannerDismissed = true },
            )
        }

        // WebView
        Box(modifier = Modifier.fillMaxSize()) {
            account?.let { acc ->
                // Get session ID and sync to WebView's CookieManager
                val sessionId = viewModel.getSessionId(acc.fullServerUrl)

                // Only surface the pending deep link to the WebView when it belongs to the
                // currently active account. It is NOT consumed here (that would be a state-set
                // apply) — the WebView consumes it once, after the target page finishes loading.
                val deepLinkUrl = pendingDeepLink
                    ?.takeIf { it.accountId == acc.id }
                    ?.url

                OdooWebView(
                    serverUrl = acc.fullServerUrl,
                    database = acc.database,
                    sessionId = sessionId,
                    deepLinkUrl = deepLinkUrl,
                    onDeepLinkConsumed = { viewModel.consumePendingDeepLink(acc.id) },
                    locationPermissionGate = viewModel.locationPermissionGate,
                    activeHostSnapshot = activeHostSnapshot,
                    onWebViewCreated = { webView = it },
                    onLoadingChanged = { isLoading = it },
                    onSelfHeal = { host -> viewModel.selfHealActiveAccount(host) },
                    getFreshSessionId = { url -> viewModel.getSessionId(url) },
                    onReloginRequired = onMenuClick,
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * WI-1 denial banner shown at the top of [MainScreen] while notifications are blocked at the app
 * level. [onEnableClick] opens the system app-notification settings; [onDismissClick] hides it for
 * the current session. All text comes from string resources (localized).
 */
@Composable
private fun NotificationPermissionBanner(
    onEnableClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.notification_permission_banner_message),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissClick) {
                    Text(text = stringResource(R.string.notification_permission_banner_dismiss))
                }
                TextButton(onClick = onEnableClick) {
                    Text(text = stringResource(R.string.notification_permission_banner_action))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OdooWebView(
    serverUrl: String,
    database: String,
    sessionId: String?,
    deepLinkUrl: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    locationPermissionGate: LocationPermissionGate? = null,
    activeHostSnapshot: String? = null,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onSelfHeal: suspend (host: String) -> Boolean,
    getFreshSessionId: (serverUrl: String) -> String?,
    onReloginRequired: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Fix for stale-closure bug — the WebChromeClient is created once at WebView
    // factory time and captures whatever activeHostSnapshot was at that moment
    // (typically null because the active-account Flow has not emitted yet).
    // rememberUpdatedState wraps the parameter so the closure reads the latest
    // value on every callback invocation. See architect review notes for details.
    val currentActiveHost by rememberUpdatedState(activeHostSnapshot)

    // The WebViewClient and update{} block are created once but must read the LATEST params on
    // every callback / recomposition, so wrap the deep-link inputs the same way.
    val currentServerUrl by rememberUpdatedState(serverUrl)
    val currentDatabase by rememberUpdatedState(database)
    val currentDeepLinkUrl by rememberUpdatedState(deepLinkUrl)
    val currentOnDeepLinkConsumed by rememberUpdatedState(onDeepLinkConsumed)

    // Session self-heal callbacks. The WebViewClient below is created once at factory time and
    // captures whatever is in scope then, so wrap the callbacks with rememberUpdatedState to read
    // the latest on every invocation (same stale-closure fix as the deep-link inputs above).
    val currentOnSelfHeal by rememberUpdatedState(onSelfHeal)
    val currentGetFreshSessionId by rememberUpdatedState(getFreshSessionId)
    val currentOnReloginRequired by rememberUpdatedState(onReloginRequired)

    // Scope for the async self-heal launched from shouldOverrideUrlLoading. Compose scopes run on
    // the main dispatcher, so the WebView reload after re-auth happens on the main thread; the
    // blocking re-auth itself hops to Dispatchers.IO inside MainViewModel.selfHealActiveAccount.
    val selfHealScope = rememberCoroutineScope()

    // Loop guard: at most one self-heal attempt per expiry cycle. Set true when a /web/login
    // triggers self-heal; reset to false in onPageFinished once a real (non-login) page lands.
    // A second /web/login while still true means self-heal did not recover the session — we route
    // to the re-login surface instead of re-entering self-heal (prevents the Main⇄login bounce).
    val selfHealAttempted = remember { AtomicBoolean(false) }

    // Tracks which server the single WebView last (re)loaded, so update{} can detect an
    // account switch (serverUrl change) and drive a full reload. Seeded in the factory.
    var lastLoadedServerUrl by remember { mutableStateOf(serverUrl) }
    // The deep link already applied to the current page, so it is applied exactly once whether
    // it arrives via onPageFinished (cold / switch) or via the warm full-reload path.
    var appliedDeepLinkUrl by remember { mutableStateOf<String?>(null) }
    // True once the current server's page has finished loading. Gates the warm apply so a
    // full reload is never fired at a page that is still loading (cold start / mid switch).
    var currentPageLoaded by remember { mutableStateOf(false) }

    // v1.0.15: File upload support - state for file chooser callback
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    // Geolocation: holds an in-flight permission request while the OS dialog is open.
    var pendingGeolocationRequest by remember { mutableStateOf<PendingGeolocationRequest?>(null) }

    // Runtime permission launcher for ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION.
    // Invoked when LocationPermissionGate returns NeedsRuntimePrompt.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.any { it }
        pendingGeolocationRequest?.let { req ->
            if (granted && req.origin?.isNotBlank() == true) {
                // Clear any stale per-origin "blocked" entry before granting so that
                // a previously denied WebView database entry cannot override the grant.
                GeolocationPermissions.getInstance().clear(req.origin)
                req.callback.invoke(req.origin, true, true)
                Timber.d("Geolocation: granted after runtime prompt for %s", req.origin)
            } else {
                req.callback.invoke(req.origin, false, false)
                Timber.d("Geolocation: denied at runtime prompt for %s", req.origin)
            }
            pendingGeolocationRequest = null
        }
    }

    // Null-guard: clear any dangling request when the composable leaves the composition.
    DisposableEffect(Unit) {
        onDispose { pendingGeolocationRequest = null }
    }

    // v1.0.15: Create temp file for camera photo
    fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    // v1.0.15: File chooser launcher - handles result from file picker/camera
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Timber.d("File chooser result: ${result.resultCode}")

        val uris = mutableListOf<Uri>()

        // Check if camera photo was taken
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // Check for camera result first
            cameraPhotoUri?.let { cameraUri ->
                // Verify file exists and has content
                try {
                    context.contentResolver.openInputStream(cameraUri)?.use { stream ->
                        if (stream.available() > 0) {
                            uris.add(cameraUri)
                            Timber.d("Camera photo captured: $cameraUri")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e("Error reading camera photo: ${e.message}")
                }
            }

            // Check for file/gallery result
            result.data?.let { intent ->
                // Single selection
                intent.data?.let { uri ->
                    if (!uris.contains(uri)) {
                        uris.add(uri)
                        Timber.d("Single file selected: $uri")
                    }
                }

                // Multiple selection
                intent.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        if (!uris.contains(uri)) {
                            uris.add(uri)
                            Timber.d("Multiple file selected [$i]: $uri")
                        }
                    }
                }
            }
        }

        // Send result to WebView (must always call, even with empty/null result)
        val resultUris = if (uris.isNotEmpty()) uris.toTypedArray() else null
        Timber.d("Sending ${uris.size} URIs to WebView")
        filePathCallback?.onReceiveValue(resultUris)
        filePathCallback = null
        cameraPhotoUri = null
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // v1.0.14: Ensure WebView has proper layout params
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                onWebViewCreated(this)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    // Required for navigator.geolocation to fire
                    // onGeolocationPermissionsShowPrompt in the WebChromeClient.
                    setGeolocationEnabled(true)

                    // v1.0.12: CRITICAL FIX - Disable wide viewport settings
                    // These settings cause Odoo OWL to miscalculate layout dimensions
                    // Playwright tests work WITHOUT these settings
                    loadWithOverviewMode = false
                    useWideViewPort = false

                    // B0.8: Disable file access for security
                    allowFileAccess = false
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                    // B0.7: Disable popup windows for security, but allow JS window calls
                    // OWL framework requires javaScriptCanOpenWindowsAutomatically for proper rendering
                    javaScriptCanOpenWindowsAutomatically = true
                    mediaPlaybackRequiresUserGesture = false
                    setSupportMultipleWindows(false)

                    // v1.0.12: Use standard Chrome Mobile User-Agent (no custom suffix)
                    // Some sites check for exact UA match
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                // Enable cookies for this WebView.
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                // B0.6: Disable third-party cookies for security
                cookieManager.setAcceptThirdPartyCookies(this, false)

                // Per-account cookie isolation: the CookieManager is process-global, so before the
                // first load we clear every cookie and set ONLY the active account's session
                // cookie. This guarantees account A's cookies can never load under account B.
                isolateCookiesForAccount(serverUrl = serverUrl, sessionId = sessionId)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // A fresh document load begins — the warm deep-link apply must wait until
                        // it finishes before it may trigger another full reload.
                        currentPageLoaded = false
                        onLoadingChanged(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Self-heal loop guard: a real (non-login) page landed, so re-arm self-heal
                        // for any future, genuinely-new expiry. A /web/login landing must NOT clear
                        // it — that case is handled in shouldOverrideUrlLoading and re-arming here
                        // would let the bounce loop resume.
                        if (url != null && !url.contains("/web/login")) {
                            selfHealAttempted.set(false)
                        }
                        // v1.0.14: Force layout recalculation for OWL framework
                        view?.evaluateJavascript(
                            """
                            (function() {
                                console.log('[WoowTech] Page loaded: ' + window.location.href);
                                console.log('[WoowTech] Viewport: ' + window.innerWidth + 'x' + window.innerHeight);

                                // Force body to have correct dimensions
                                document.body.style.minHeight = '100vh';
                                document.body.style.height = '100%';
                                document.documentElement.style.height = '100%';

                                // Force action_manager to have correct dimensions
                                var am = document.querySelector('.o_action_manager');
                                if (am) {
                                    am.style.minHeight = 'calc(100vh - 46px)';
                                    am.style.height = 'auto';
                                    am.style.overflow = 'auto';
                                    console.log('[WoowTech] ActionManager found, innerHTML: ' + am.innerHTML.length + ' chars');
                                    console.log('[WoowTech] ActionManager size: ' + am.offsetWidth + 'x' + am.offsetHeight);
                                }

                                // Trigger multiple resize events to wake up OWL
                                window.dispatchEvent(new Event('resize'));
                                setTimeout(function() {
                                    window.dispatchEvent(new Event('resize'));
                                    // Force reflow
                                    document.body.offsetHeight;
                                }, 100);
                                setTimeout(function() {
                                    window.dispatchEvent(new Event('resize'));
                                }, 500);
                                setTimeout(function() {
                                    window.dispatchEvent(new Event('resize'));
                                    var am2 = document.querySelector('.o_action_manager');
                                    if (am2) {
                                        console.log('[WoowTech] After 1s - ActionManager size: ' + am2.offsetWidth + 'x' + am2.offsetHeight);
                                        console.log('[WoowTech] After 1s - innerHTML: ' + am2.innerHTML.length + ' chars');
                                    }
                                }, 1000);
                            })();
                            """.trimIndent(),
                            null
                        )

                        // Load-gated deep-link apply: only navigate to the pending link once a
                        // page from the TARGET account's own host has finished loading. This is
                        // the point that guarantees a link is never applied while the WebView is
                        // still showing the previous account's host.
                        currentPageLoaded = DeepLinkWebPlanner.hostMatches(
                            loadedUrl = url,
                            targetServerUrl = currentServerUrl,
                        )

                        val pending = currentDeepLinkUrl
                        if (view != null &&
                            pending != null &&
                            appliedDeepLinkUrl != pending &&
                            currentPageLoaded
                        ) {
                            applyDeepLink(view, currentServerUrl, pending)
                            appliedDeepLinkUrl = pending
                            currentOnDeepLinkConsumed()
                            Timber.d("Applied pending deep link after page load")
                        }

                        onLoadingChanged(false)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        Timber.d("shouldOverrideUrlLoading: $url")

                        // Detect session expiry — Odoo redirects the expired WebView to /web/login.
                        // Instead of bouncing to Config (the old behaviour, which trapped the user
                        // in a Main⇄login loop), silently self-heal from the stored password and
                        // stay on the Odoo page — parity with iOS attemptSelfHealOrLogin.
                        if (url.contains("/web/login")) {
                            if (selfHealAttempted.compareAndSet(false, true)) {
                                // First expiry this cycle: cancel the login-page load, show the
                                // spinner, and re-authenticate off-thread. All security guardrails
                                // (https-only + exact stored host, one retry, bad-cred STOP,
                                // single-flight, no credential logging) live in SessionReauthenticator.
                                Timber.d("Session expired — attempting silent self-heal")
                                onLoadingChanged(true)
                                val targetServerUrl = currentServerUrl
                                val targetDatabase = currentDatabase
                                selfHealScope.launch {
                                    val host = runCatching { java.net.URI(targetServerUrl).host }
                                        .getOrNull()
                                    val healed = host != null && currentOnSelfHeal(host)
                                    if (healed) {
                                        // Re-inject the refreshed session cookie (host-scoped, same
                                        // proven path as the initial load) and reload the Odoo page.
                                        val freshSessionId = currentGetFreshSessionId(targetServerUrl)
                                        isolateCookiesForAccount(
                                            serverUrl = targetServerUrl,
                                            sessionId = freshSessionId,
                                        )
                                        view?.loadUrl("$targetServerUrl/web?db=$targetDatabase")
                                        // Guard stays set until onPageFinished lands a real page —
                                        // if the reload itself hits /web/login again we must not loop.
                                    } else {
                                        Timber.d("Self-heal failed — surfacing re-login")
                                        onLoadingChanged(false)
                                        currentOnReloginRequired()
                                    }
                                }
                            } else {
                                // Second /web/login before a real page loaded: self-heal already
                                // ran and did not recover the session. Do NOT retry — surface the
                                // re-login prompt so the user can re-authenticate manually.
                                Timber.d("Session still expired after self-heal — surfacing re-login")
                                onLoadingChanged(false)
                                currentOnReloginRequired()
                            }
                            return true
                        }

                        // v1.0.13: Allow all URLs from the same host (not just same prefix)
                        // This handles /odoo/ redirects in Odoo 17/18
                        val serverHost = java.net.URI(serverUrl).host
                        val urlHost = try { java.net.URI(url).host } catch (e: Exception) { null }
                        if (urlHost == serverHost) {
                            Timber.d("Same host, allowing: $url")
                            return false
                        }

                        // Allow blob: URLs (used by OWL framework for downloads)
                        if (url.startsWith("blob:")) {
                            Timber.d("Allowing blob URL")
                            return false
                        }

                        // B0.5: Block all other URLs — open external URLs in system browser
                        Timber.d("External URL, opening in browser: $url")
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            view?.context?.startActivity(intent)
                        } catch (e: android.content.ActivityNotFoundException) {
                            Timber.e("No browser found to open: $url")
                        }
                        return true
                    }

                    // v1.0.13: Monitor all resource requests for debugging
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        // Log failed or important requests
                        if (url.contains(".js") || url.contains(".css") || url.contains("/web/")) {
                            Timber.d("Resource request: $url")
                        }
                        return null // Don't intercept, let WebView handle it
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        Timber.e("Resource error: ${request?.url} - ${error?.description}")
                    }
                }

                // v1.0.15: Enhanced WebChromeClient with file upload, window handling and console logging
                webChromeClient = object : WebChromeClient() {
                    // v1.0.15: File upload support
                    override fun onShowFileChooser(
                        webView: WebView?,
                        callback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        Timber.d("onShowFileChooser called")
                        Timber.d("Accept types: ${fileChooserParams?.acceptTypes?.joinToString()}")
                        Timber.d("Mode: ${fileChooserParams?.mode}")

                        // Cancel any pending callback
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = callback

                        try {
                            // Create camera intent
                            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            val photoFile = createImageFile()
                            val photoUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                photoFile
                            )
                            cameraPhotoUri = photoUri
                            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                            Timber.d("Camera URI: $photoUri")

                            // Create gallery/file intent
                            val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)

                                // Set MIME type based on accept types
                                val acceptTypes = fileChooserParams?.acceptTypes
                                type = if (acceptTypes.isNullOrEmpty() || acceptTypes[0].isNullOrBlank()) {
                                    "*/*"
                                } else {
                                    acceptTypes[0]
                                }

                                // Allow multiple selection if supported
                                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                }
                            }

                            // Create chooser with camera as extra option
                            val chooserIntent = Intent.createChooser(contentIntent, "選擇檔案").apply {
                                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent))
                            }

                            fileChooserLauncher.launch(chooserIntent)
                            return true

                        } catch (e: Exception) {
                            Timber.e("Error launching file chooser: ${e.message}")
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = null
                            cameraPhotoUri = null
                            return false
                        }
                    }

                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        // Handle window creation requests from OWL framework
                        Timber.d("onCreateWindow called: isDialog=$isDialog, isUserGesture=$isUserGesture")
                        // Create a new WebView for the popup and pass it back
                        val newWebView = WebView(view?.context ?: return false)
                        newWebView.settings.javaScriptEnabled = true
                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        transport?.webView = newWebView
                        resultMsg?.sendToTarget()
                        return true
                    }

                    override fun onCloseWindow(window: WebView?) {
                        Timber.d("onCloseWindow called")
                        window?.destroy()
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Timber.d(
                                "[%s] %s (%s:%d)",
                                it.messageLevel(),
                                it.message(),
                                it.sourceId(),
                                it.lineNumber()
                            )
                        }
                        return true
                    }

                    /**
                     * Called by the WebView when a page requests geolocation permission.
                     *
                     * The resolution order is:
                     * 1. Activity-resumed guard — if the Activity is not RESUMED the OS dialog
                     *    cannot be shown, so we deny immediately and let Odoo's error callback
                     *    fire the no-coords clock-in path.
                     * 2. [LocationPermissionGate.resolve] — checks origin, user preference,
                     *    and OS permission state.
                     *
                     * Every code path invokes [callback] exactly once, satisfying the
                     * WebView's contract of calling the callback within the 30s timeout.
                     */
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?,
                    ) {
                        if (callback == null) return

                        // Guard: OS runtime dialog cannot be shown unless Activity is RESUMED.
                        if (lifecycleOwner.lifecycle.currentState < Lifecycle.State.RESUMED) {
                            callback.invoke(origin, false, false)
                            Timber.w("Geolocation: Activity not RESUMED — denied for %s", origin)
                            return
                        }

                        val gate = locationPermissionGate
                        if (gate == null) {
                            // Gate unavailable (e.g. previews, tests without Hilt) — deny safely.
                            callback.invoke(origin, false, false)
                            return
                        }

                        when (val decision = gate.resolve(origin, currentActiveHost)) {
                            is LocationPermissionGate.Decision.Grant -> {
                                // Defense-in-depth: clear any stale per-origin "blocked" cache
                                // entry that could override this grant.
                                if (!origin.isNullOrBlank()) {
                                    GeolocationPermissions.getInstance().clear(origin)
                                }
                                callback.invoke(origin, true, true)
                                Timber.d("Geolocation: granted for %s", origin)
                            }
                            is LocationPermissionGate.Decision.Reject -> {
                                callback.invoke(origin, false, false)
                                Timber.d("Geolocation: rejected (%s)", decision.reason)
                            }
                            is LocationPermissionGate.Decision.NeedsRuntimePrompt -> {
                                pendingGeolocationRequest = PendingGeolocationRequest(
                                    origin = origin,
                                    callback = callback,
                                )
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
                            }
                        }
                    }

                    override fun onGeolocationPermissionsHidePrompt() {
                        super.onGeolocationPermissionsHidePrompt()
                    }
                }

                // Always load the account's base page; any pending deep link is applied in
                // onPageFinished once this host has finished loading (load-gated apply). This
                // keeps the "apply only after load" invariant identical across cold start and
                // account switch.
                lastLoadedServerUrl = serverUrl
                loadUrl("$serverUrl/web?db=$database")
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { webView ->
            if (serverUrl != lastLoadedServerUrl) {
                // Single-view account switch: when the active account changes, serverUrl changes.
                // Re-isolate cookies for the new account and reload its base page. The pending deep
                // link is then applied in onPageFinished (host-gated).
                Timber.d("Account switched — reloading WebView for new server")
                appliedDeepLinkUrl = null
                currentPageLoaded = false
                isolateCookiesForAccount(serverUrl = serverUrl, sessionId = sessionId)
                lastLoadedServerUrl = serverUrl
                webView.loadUrl("$serverUrl/web?db=$database")
            } else {
                // Warm case: already loaded on the target host (no reload happens) and a new deep
                // link is pending for it. Every warm same-account pending link — with OR without a
                // `#fragment` (e.g. "/web/login") — is routed through applyDeepLink -> plan() ->
                // FullLoad (a full cross-document reload), matching iOS which routes every warm tap
                // with no fragment precondition. Gated on currentPageLoaded so it never fires at a
                // page that is still loading.
                val pending = deepLinkUrl
                if (currentPageLoaded &&
                    pending != null &&
                    appliedDeepLinkUrl != pending
                ) {
                    applyDeepLink(webView, serverUrl, pending)
                    appliedDeepLinkUrl = pending
                    onDeepLinkConsumed()
                    Timber.d("Applied pending deep link via full reload (warm)")
                }
            }
        }
    )
}

/**
 * Applies a pending deep link to [view] as a full cross-document load of the account's server plus
 * the relative link (fragment preserved). The decision is delegated to the pure, unit-tested
 * [DeepLinkWebPlanner.plan], which re-validates the link against the account host; an invalid link
 * (e.g. `javascript:`, path traversal, foreign host) yields a null plan and is a safe no-op.
 */
private fun applyDeepLink(view: WebView, serverUrl: String, deepLinkUrl: String) {
    when (val plan = DeepLinkWebPlanner.plan(currentUrl = view.url, serverUrl = serverUrl, deepLink = deepLinkUrl)) {
        is DeepLinkWebPlanner.NavPlan.FullLoad -> view.loadUrl(plan.url)
        null -> Timber.d("Ignored invalid pending deep link at apply layer")
    }
}

/**
 * Enforces per-account cookie isolation on the process-global [CookieManager]: clears every cookie
 * and then sets only [serverUrl]'s session cookie. Called before each account's first load and on
 * every account switch so one account's cookies can never be presented to another account's server.
 */
private fun isolateCookiesForAccount(serverUrl: String, sessionId: String?) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    // Clear ALL cookies — this is the isolation crux for the same-and-different-host cases.
    cookieManager.removeAllCookies(null)
    if (sessionId != null) {
        cookieManager.setCookie(serverUrl, "session_id=$sessionId; Path=/; Secure")
    }
    cookieManager.flush()
}
