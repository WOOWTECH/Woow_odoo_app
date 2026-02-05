package io.woowtech.odoo.ui.main

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import io.woowtech.odoo.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onMenuClick: () -> Unit
) {
    val account by viewModel.activeAccount.collectAsState(initial = null)
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
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

        // WebView
        Box(modifier = Modifier.fillMaxSize()) {
            account?.let { acc ->
                // Get session ID and sync to WebView's CookieManager
                val sessionId = viewModel.getSessionId(acc.fullServerUrl)

                OdooWebView(
                    serverUrl = acc.fullServerUrl,
                    database = acc.database,
                    sessionId = sessionId,
                    onWebViewCreated = { webView = it },
                    onLoadingChanged = { isLoading = it },
                    onSessionExpired = onMenuClick // Navigate to menu/login on session expiry
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OdooWebView(
    serverUrl: String,
    database: String,
    sessionId: String?,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onSessionExpired: () -> Unit
) {
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

                    // v1.0.12: CRITICAL FIX - Disable wide viewport settings
                    // These settings cause Odoo OWL to miscalculate layout dimensions
                    // Playwright tests work WITHOUT these settings
                    loadWithOverviewMode = false
                    useWideViewPort = false

                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                    // v1.0.10: Additional settings for OWL framework compatibility
                    javaScriptCanOpenWindowsAutomatically = true
                    mediaPlaybackRequiresUserGesture = false
                    setSupportMultipleWindows(true)

                    // v1.0.12: Use standard Chrome Mobile User-Agent (no custom suffix)
                    // Some sites check for exact UA match
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                // Enable cookies and sync session cookie from OkHttp to WebView
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                // Sync session cookie from native authentication to WebView
                if (sessionId != null) {
                    cookieManager.setCookie(serverUrl, "session_id=$sessionId; Path=/; Secure")
                    cookieManager.flush()
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChanged(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
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
                        onLoadingChanged(false)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        Log.d("WoowTechOdoo", "shouldOverrideUrlLoading: $url")

                        // Detect session expiry - if redirected to login page
                        if (url.contains("/web/login")) {
                            Log.d("WoowTechOdoo", "Session expired, redirecting to login")
                            onSessionExpired()
                            return true
                        }

                        // v1.0.13: Allow all URLs from the same host (not just same prefix)
                        // This handles /odoo/ redirects in Odoo 17/18
                        val serverHost = java.net.URI(serverUrl).host
                        val urlHost = try { java.net.URI(url).host } catch (e: Exception) { null }
                        if (urlHost == serverHost) {
                            Log.d("WoowTechOdoo", "Same host, allowing: $url")
                            return false
                        }

                        // v1.0.13: Allow blob: and data: URLs (used by OWL framework)
                        if (url.startsWith("blob:") || url.startsWith("data:")) {
                            Log.d("WoowTechOdoo", "Allowing blob/data URL")
                            return false
                        }

                        // Allow HTTPS URLs
                        if (url.startsWith("https://")) {
                            Log.d("WoowTechOdoo", "Allowing HTTPS URL: $url")
                            return false
                        }

                        Log.d("WoowTechOdoo", "Blocking URL: $url")
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
                            Log.d("WoowTechOdoo", "Resource request: $url")
                        }
                        return null // Don't intercept, let WebView handle it
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        Log.e("WoowTechOdoo", "Resource error: ${request?.url} - ${error?.description}")
                    }
                }

                // v1.0.10: Enhanced WebChromeClient with window handling and console logging
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        // Handle window creation requests from OWL framework
                        Log.d("WoowTechOdoo", "onCreateWindow called: isDialog=$isDialog, isUserGesture=$isUserGesture")
                        // Create a new WebView for the popup and pass it back
                        val newWebView = WebView(view?.context ?: return false)
                        newWebView.settings.javaScriptEnabled = true
                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        transport?.webView = newWebView
                        resultMsg?.sendToTarget()
                        return true
                    }

                    override fun onCloseWindow(window: WebView?) {
                        Log.d("WoowTechOdoo", "onCloseWindow called")
                        window?.destroy()
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d(
                                "WoowTechOdoo",
                                "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                            )
                        }
                        return true
                    }
                }

                // v1.0.12: Load Odoo with standard URL (no debug parameter)
                // Debug parameter can cause slower loading and is not needed
                loadUrl("$serverUrl/web?db=$database")
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { webView ->
            // Handle updates if needed
        }
    )
}
