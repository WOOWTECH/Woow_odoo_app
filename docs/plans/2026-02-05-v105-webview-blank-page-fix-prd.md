# PRD: v1.0.5 WebView 空白頁面修復

## 問題描述

用戶報告：登入後 WebView 顯示空白頁面（v1.0.3, v1.0.4 均有此問題）
用戶確認：v1.0.0 可以正常顯示內容

## 根本原因分析

### v1.0.0 (正常運作) vs v1.0.4 (空白) 差異

| 設定 | v1.0.0 | v1.0.4 | 影響 |
|------|--------|--------|------|
| `loadWithOverviewMode` | `true` | `false` | 頁面縮放模式 |
| `useWideViewPort` | `true` | `false` | 視窗寬度設定 |
| `userAgentString` | 原生 + "WoowTechOdoo/1.0" | 完整 Mobile UA | Odoo 回應格式 |
| `loadUrl` | `$serverUrl/web` | `$serverUrl/web?db=$database` | URL 參數 |
| Session 處理 | WebView 自行登入 | 嘗試同步 OkHttp session | 登入機制 |

### 問題分析

1. **視窗設定問題**
   - `loadWithOverviewMode = false` 和 `useWideViewPort = false` 會導致某些網頁無法正確顯示
   - 需要恢復為 `true`

2. **Session 同步失效**
   - `viewModel.getSessionId()` 可能返回 `null`，因為：
     - App 重啟後 OkHttp cookieStore 是空的（記憶體儲存）
     - 只有 Room DB 保存帳號資訊，不保存 session
   - 即使 sessionId 有值，也可能因為 session 過期而無效

3. **v1.0.0 為何能運作**
   - v1.0.0 直接載入 `/web`，讓 WebView 自行處理登入流程
   - Odoo 檢測到無 session，重導向到 `/web/login`
   - 用戶在 WebView 中輸入帳密，Odoo 設定 session cookie
   - 此後 WebView 正常運作

## 解決方案

### 方案 A：恢復 v1.0.0 設定 (推薦)
恢復視窗設定，移除失效的 session 同步邏輯

**修改內容**：
```kotlin
// 恢復視窗設定
loadWithOverviewMode = true
useWideViewPort = true

// 移除 session 同步 (因為無法可靠地同步)
// 讓 WebView 自行處理登入

// 恢復原本的 URL
loadUrl("$serverUrl/web")

// 移除 /web/login 攔截 (允許 WebView 顯示登入頁)
```

### 方案 B：完整 Session 持久化
需要大量修改，將 session_id 保存到 EncryptedPrefs

**修改內容**：
- 修改 `OdooJsonRpcClient` 保存 session 到持久化儲存
- 每次 App 啟動時從 EncryptedPrefs 恢復 session
- 處理 session 過期重新登入

## 決定：採用方案 A

原因：
1. 簡單有效，恢復已驗證可行的設定
2. 用戶首次登入後，WebView 會記住 cookie
3. 避免複雜的 session 同步邏輯

## 實作步驟

### 1. 修改 MainScreen.kt

```kotlin
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OdooWebView(
    serverUrl: String,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit
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
                    loadWithOverviewMode = true  // 恢復
                    useWideViewPort = true       // 恢復
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
                        onLoadingChanged(false)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false

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

                webChromeClient = WebChromeClient()

                // Load the Odoo web interface
                loadUrl("$serverUrl/web")
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
```

### 2. 簡化 MainScreen 呼叫

移除 `sessionId`, `database`, `onSessionExpired` 參數

### 3. 更新版本號

```kotlin
versionCode = 5
versionName = "1.0.5"
```

## 測試計畫

1. 清除 App 資料，重新登入
2. 確認 WebView 能顯示 Odoo 登入頁
3. 登入後確認能看到 Odoo 後台
4. 關閉 App 重開，確認 session 保持

## 風險評估

- 低風險：恢復到已驗證可行的 v1.0.0 設定
- 用戶可能需要在 WebView 中再次登入（App 首次使用時）
