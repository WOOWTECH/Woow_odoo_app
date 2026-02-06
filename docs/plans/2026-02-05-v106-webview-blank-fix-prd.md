# PRD: v1.0.6 WebView 空白頁面修復

## 問題描述

用戶報告：所有版本（v1.0.0 ~ v1.0.5）登入後 WebView 都顯示完全空白頁面。

## 根本原因分析

### 問題 1：`/web/login` URL 被錯誤攔截 (Critical)

**位置**：`MainScreen.kt:173-176`

```kotlin
if (url.contains("/web/login")) {
    onSessionExpired()
    return true  // <-- 攔截 URL，阻止 WebView 載入
}
```

**問題流程**：
```
App 啟動 → 有帳號 → MainScreen
    ↓
loadUrl("$serverUrl/web?db=$database")
    ↓
Odoo 檢測無有效 session → 302 重導向到 /web/login
    ↓
shouldOverrideUrlLoading 被呼叫
    ↓
url.contains("/web/login") == true
    ↓
return true (攔截 URL，不讓 WebView 載入)
    ↓
WebView 空白！（沒有任何內容）
```

### 問題 2：Session Cookie 無法持久化

**位置**：`OdooJsonRpcClient.kt:29`

```kotlin
private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()  // 記憶體儲存
```

- OkHttp 的 cookieStore 只存在記憶體中
- App 重啟後，`viewModel.getSessionId()` 永遠返回 `null`
- 即使 Room DB 保存了帳號資訊，session cookie 已經遺失

### 問題 3：Viewport 設定不正確

**位置**：`MainScreen.kt:133-134`

```kotlin
loadWithOverviewMode = false
useWideViewPort = false
```

- 這些設定可能導致某些網頁無法正確渲染

## 解決方案

### 採用方案 A：允許 WebView 自行處理登入（推薦）

原因：
1. 最簡單有效
2. WebView 的 CookieManager 會自動管理 session
3. 用戶在 WebView 中登入後，cookie 會被保存
4. 下次開啟 App，WebView 會自動使用保存的 cookie

### 修改內容

#### 1. 移除 `/web/login` 攔截邏輯

```kotlin
override fun shouldOverrideUrlLoading(
    view: WebView?,
    request: WebResourceRequest?
): Boolean {
    val url = request?.url?.toString() ?: return false

    // 允許所有 Odoo 相關 URL（包括 /web/login）
    if (url.startsWith(serverUrl)) {
        return false
    }

    // Block non-HTTPS
    if (!url.startsWith("https://")) {
        return true
    }

    return false
}
```

#### 2. 恢復 Viewport 設定

```kotlin
loadWithOverviewMode = true
useWideViewPort = true
```

#### 3. 恢復原生 User-Agent

```kotlin
userAgentString = settings.userAgentString + " WoowTechOdoo/1.0"
```

#### 4. 簡化 URL（移除 db 參數）

```kotlin
loadUrl("$serverUrl/web")
```

#### 5. 移除無效的 session 同步邏輯

因為 sessionId 在 App 重啟後永遠是 null，這段代碼無效：

```kotlin
// 移除這段
if (sessionId != null) {
    cookieManager.setCookie(serverUrl, "session_id=$sessionId; Path=/; Secure")
    cookieManager.flush()
}
```

#### 6. 簡化函數簽名

```kotlin
@Composable
fun OdooWebView(
    serverUrl: String,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit
)
```

## 完整修改後的 OdooWebView

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
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgentString = settings.userAgentString + " WoowTechOdoo/1.0"
                }

                // Enable cookies - WebView 會自動管理
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

                        // 允許所有 Odoo 相關 URL（包括 /web/login）
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

                // 載入 Odoo 網頁介面
                loadUrl("$serverUrl/web")
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { }
    )
}
```

## 測試計畫

1. 清除 App 資料，重新安裝
2. 輸入伺服器 URL 和帳密
3. 確認能看到 Odoo 登入頁面（如果需要）
4. 登入後確認能看到 Odoo 後台
5. 關閉 App 重開，確認自動登入（WebView cookie 保持）

## 版本資訊

- 版本號：1.0.6
- versionCode: 6
