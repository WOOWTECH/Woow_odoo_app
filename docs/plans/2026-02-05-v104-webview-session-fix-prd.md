# PRD: v1.0.4 WebView Session Cookie 同步修復

## 1. 問題描述

### 1.1 現象
- 用戶在 App 登入後，WebView 顯示空白頁面
- WebView 實際上顯示的是 Odoo 的登入頁面，而非後台
- v1.0.2 有自動登入功能，但 v1.0.3 失效

### 1.2 根本原因
**Session Cookie 未同步到 WebView**

當前流程（錯誤）：
```
LoginScreen
  → LoginViewModel.login()
  → AccountRepository.authenticate()
  → OdooJsonRpcClient.authenticate()
  → [session_id cookie 只存在 OkHttp cookieStore]
  → Navigate to MainScreen
  → OdooWebView 載入 $serverUrl/web
  → [Android WebView 沒有 session_id cookie]
  → Odoo 認為未登入，重導向到 /web/login
  → 用戶看到空白/登入頁面
```

### 1.3 技術細節
- `OdooJsonRpcClient.kt` 使用 OkHttp 的 `CookieJar` 儲存 session
- `MainScreen.kt` 的 WebView 使用 Android 的 `CookieManager`
- 這兩個 Cookie 儲存是**完全獨立的**，無法自動共享
- `AccountRepository.getSessionCookies()` 是空的 stub 函數

## 2. 解決方案

### 2.1 核心修復：Cookie 同步機制

#### 2.1.1 在 OdooJsonRpcClient 中暴露 Session ID
```kotlin
// OdooJsonRpcClient.kt
fun getSessionId(host: String): String? {
    return cookieStore[host]?.find { it.name == "session_id" }?.value
}
```

#### 2.1.2 在 MainScreen 載入 WebView 前同步 Cookie
```kotlin
// MainScreen.kt - 在 loadUrl 之前
val sessionId = viewModel.getSessionId()
if (sessionId != null) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setCookie(serverUrl, "session_id=$sessionId; Path=/; Secure; HttpOnly")
    cookieManager.flush()
}
```

### 2.2 附加修復

#### 2.2.1 URL 加入資料庫參數
```kotlin
// 從:
loadUrl("$serverUrl/web")

// 改為:
loadUrl("$serverUrl/web?db=${account.database}")
```

#### 2.2.2 Session 過期檢測
```kotlin
override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
    val url = request?.url?.toString() ?: return false

    // 如果被重導向到登入頁，觸發重新認證
    if (url.contains("/web/login")) {
        onSessionExpired()
        return true
    }
    // ...
}
```

#### 2.2.3 JavaScript 自動登入（備選方案）
如果 Cookie 同步仍有問題，可以使用 JS 注入：
```kotlin
val loginJs = """
    (function() {
        document.querySelector('input[name="login"]').value = '$username';
        document.querySelector('input[name="password"]').value = '$password';
        document.querySelector('form').submit();
    })();
""".trimIndent()
webView.evaluateJavascript(loginJs, null)
```

## 3. 實作計劃

### 3.1 檔案修改清單

| 檔案 | 修改內容 |
|------|---------|
| `OdooJsonRpcClient.kt` | 新增 `getSessionId()` 方法 |
| `AccountRepository.kt` | 實作 `getSessionCookies()` |
| `MainViewModel.kt` | 新增 session 相關方法 |
| `MainScreen.kt` | Cookie 同步、URL 參數、Session 檢測 |

### 3.2 測試項目
- [ ] 登入後直接顯示 Odoo 後台
- [ ] Session 過期後自動重新登入
- [ ] 多資料庫環境正確選擇資料庫
- [ ] Cookie 在 App 重啟後正確恢復

## 4. 風險評估

| 風險 | 等級 | 緩解措施 |
|------|------|---------|
| Cookie 同步時機問題 | 中 | 加入 flush() 確保寫入 |
| Session 過期無提示 | 低 | 加入 Session 檢測邏輯 |
| 多資料庫選擇錯誤 | 低 | URL 加入 db 參數 |

## 5. 版本資訊
- 目標版本: v1.0.4
- 優先級: Critical
- 預計修復: 立即
