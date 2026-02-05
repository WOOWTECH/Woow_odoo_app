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
import android.webkit.WebViewClient
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
                onWebViewCreated(this)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false

                    // Enable wide viewport for Odoo OWL framework rendering
                    loadWithOverviewMode = true
                    useWideViewPort = true

                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                    // v1.0.10: Additional settings for OWL framework compatibility
                    // OWL may need to open windows/popups for certain operations
                    javaScriptCanOpenWindowsAutomatically = true
                    // Disable media gesture requirement for smoother loading
                    mediaPlaybackRequiresUserGesture = false
                    // Support multiple windows (OWL may create child windows)
                    setSupportMultipleWindows(true)

                    // Mobile User-Agent so Odoo serves mobile-friendly content
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 WoowTechOdoo/1.0"
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
                        // v1.0.10: Inject JavaScript to trigger OWL framework rendering
                        // Dispatch resize event to help OWL recalculate layouts
                        view?.evaluateJavascript(
                            """
                            (function() {
                                // Trigger resize event for OWL framework
                                window.dispatchEvent(new Event('resize'));

                                // Try to make action manager visible if hidden
                                var actionManager = document.querySelector('.o_action_manager');
                                if (actionManager) {
                                    actionManager.style.display = '';
                                    actionManager.style.visibility = 'visible';
                                }

                                // Log for debugging
                                console.log('[WoowTech] Page loaded, resize dispatched');
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

                        // Detect session expiry - if redirected to login page
                        if (url.contains("/web/login")) {
                            onSessionExpired()
                            return true
                        }

                        // Allow navigation within the same Odoo instance
                        if (url.startsWith(serverUrl)) {
                            return false
                        }

                        // Block non-HTTPS
                        if (!url.startsWith("https://")) {
                            return true
                        }

                        return false
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

                // Load the Odoo web interface with database parameter
                // v1.0.10: Use debug=assets to force Odoo to regenerate assets bundle
                loadUrl("$serverUrl/web?db=$database&debug=assets")
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { webView ->
            // Handle updates if needed
        }
    )
}
