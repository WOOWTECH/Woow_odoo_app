package io.woowtech.odoo.ui.main

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
    val credentials by viewModel.credentials.collectAsState()
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
            credentials?.let { creds ->
                OdooWebView(
                    credentials = creds,
                    onWebViewCreated = { webView = it },
                    onLoadingChanged = { isLoading = it }
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
    credentials: WebViewCredentials,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit
) {
    var hasAutoLoggedIn by remember { mutableStateOf(false) }

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
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgentString = settings.userAgentString + " WoowTechOdoo/1.0"
                }

                // Enable cookies
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChanged(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        // Auto-login when on login page
                        if (url != null && (url.contains("/web/login") || url.endsWith("/web")) && !hasAutoLoggedIn) {
                            hasAutoLoggedIn = true

                            // Escape special characters in password for JavaScript
                            val escapedPassword = credentials.password
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")

                            val escapedUsername = credentials.username
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")

                            val escapedDatabase = credentials.database
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")

                            // Inject JavaScript to auto-fill and submit the login form
                            val jsCode = """
                                (function() {
                                    // Wait for page to be ready
                                    function tryLogin() {
                                        var loginField = document.querySelector('input[name="login"]');
                                        var passwordField = document.querySelector('input[name="password"]');
                                        var dbField = document.querySelector('input[name="db"]') || document.querySelector('select[name="db"]');
                                        var form = document.querySelector('form.oe_login_form') || document.querySelector('form');

                                        if (loginField && passwordField) {
                                            // Fill in credentials
                                            loginField.value = '${escapedUsername}';
                                            passwordField.value = '${escapedPassword}';

                                            // Set database if field exists
                                            if (dbField) {
                                                if (dbField.tagName === 'SELECT') {
                                                    for (var i = 0; i < dbField.options.length; i++) {
                                                        if (dbField.options[i].value === '${escapedDatabase}') {
                                                            dbField.selectedIndex = i;
                                                            break;
                                                        }
                                                    }
                                                } else {
                                                    dbField.value = '${escapedDatabase}';
                                                }
                                            }

                                            // Trigger input events for any JS validation
                                            loginField.dispatchEvent(new Event('input', { bubbles: true }));
                                            passwordField.dispatchEvent(new Event('input', { bubbles: true }));

                                            // Submit the form
                                            setTimeout(function() {
                                                var submitBtn = document.querySelector('button[type="submit"]') ||
                                                               document.querySelector('input[type="submit"]') ||
                                                               form.querySelector('button');
                                                if (submitBtn) {
                                                    submitBtn.click();
                                                } else if (form) {
                                                    form.submit();
                                                }
                                            }, 300);

                                            return true;
                                        }
                                        return false;
                                    }

                                    // Try immediately, then retry a few times
                                    if (!tryLogin()) {
                                        var retries = 0;
                                        var interval = setInterval(function() {
                                            if (tryLogin() || retries > 10) {
                                                clearInterval(interval);
                                            }
                                            retries++;
                                        }, 500);
                                    }
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(jsCode) { result ->
                                Log.d("OdooWebView", "Auto-login script executed: $result")
                            }
                        }

                        onLoadingChanged(false)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false

                        // Allow navigation within the same Odoo instance
                        if (url.startsWith(credentials.serverUrl)) {
                            return false
                        }

                        // Block non-HTTPS
                        if (!url.startsWith("https://")) {
                            return true
                        }

                        return false
                    }
                }

                webChromeClient = WebChromeClient()

                // Load the Odoo web interface
                loadUrl("${credentials.serverUrl}/web/login")
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { webView ->
            // Handle updates if needed
        }
    )
}
