# PRD: v1.0.10 WebView Session 修復方案

## 問題摘要

WoowTech Odoo App 的 WebView 主要內容區域顯示空白，但側邊選單、通知、個人設定能正常顯示。

## 測試結果總結

| 項目 | 結果 |
|------|------|
| Dashboard 主頁 | ❌ 空白 |
| 點擊應用程式（銷售、採購等） | ❌ 空白 |
| 側邊選單 | ✅ 正常 |
| 通知面板 | ✅ 正常 |
| 個人設定 | ✅ 正常 |
| 手機瀏覽器訪問同網址 | ✅ 正常 |
| Odoo 版本 | 17/18 |
| Android 版本 | 14+ |
| 登入方式 | App 原生 UI |

## 根本原因分析

### 問題流程

```
1. 用戶在 App 原生 UI 輸入帳密
2. App 用 OkHttp 呼叫 Odoo JSON-RPC API 驗證 ✅ 成功
3. OkHttp 獲得 session_id cookie（存在記憶體 cookieStore）
4. 跳到 MainScreen，嘗試同步 session cookie 到 WebView
5. ❌ sessionId 可能為 null 或無效（記憶體中的 cookie 可能丟失）
6. WebView 載入 /web?db=xxx
7. Odoo 檢測無有效 session → 重導向到 /web/login
8. ❌ shouldOverrideUrlLoading 攔截 /web/login，返回 true
9. ❌ WebView 空白（沒有載入任何內容）
```

### 為什麼側邊選單和通知能顯示？

側邊選單和通知是 Odoo 前端的「殼」（shell），它們在沒有完整驗證的情況下也能渲染。但主要內容區域（ActionContainer）需要完整的 session 才能載入 action/view。

## 解決方案

### 方案 A：移除 /web/login 攔截，讓 WebView 處理登入（推薦）

**原理**：
- 移除 `shouldOverrideUrlLoading` 中對 `/web/login` 的攔截
- 讓 WebView 自己顯示 Odoo 登入頁面
- 用戶在 WebView 中登入後，session cookie 由 WebView 管理
- 後續訪問自動保持登入狀態

**程式碼修改**：
```kotlin
override fun shouldOverrideUrlLoading(
    view: WebView?,
    request: WebResourceRequest?
): Boolean {
    val url = request?.url?.toString() ?: return false

    // 移除 /web/login 攔截
    // 讓 WebView 自己處理登入頁面

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
```

**優點**：
- 最簡單的修復
- 讓 WebView 自己管理 session
- 用戶體驗：在 WebView 中看到 Odoo 登入頁，登入後直接使用

**缺點**：
- 用戶需要登入兩次（App 原生 UI + WebView）
- 可以後續優化為只用 WebView 登入

### 方案 B：改進 Session Cookie 同步

**原理**：
- 將 session_id 持久化到 SharedPreferences
- App 啟動時從持久化儲存恢復 session
- 在 WebView 載入前正確同步 cookie

**程式碼修改**：
1. 修改 `OdooJsonRpcClient` 保存 session 到 EncryptedPrefs
2. 修改 `AccountRepository` 恢復 session
3. 確保 cookie 同步時機正確

**優點**：
- 只需登入一次
- 更好的用戶體驗

**缺點**：
- 需要較多程式碼修改
- Session 可能過期，需要處理重新登入

### 方案 C：簡化登入流程（長期方案）

**原理**：
- 移除 App 原生登入 UI
- 直接讓 WebView 載入 Odoo
- 用戶在 WebView 中登入
- App 只保存伺服器 URL

**優點**：
- 最簡單的架構
- 完全由 WebView 管理 session

**缺點**：
- 需要重構登入流程
- 可能影響已保存的帳號

## 建議實施順序

1. **v1.0.10**：先測試方案 A（移除 /web/login 攔截）
2. 如果方案 A 有效，後續版本可以考慮方案 B 或 C 來優化體驗

## v1.0.10 實施計畫

### 修改 MainScreen.kt

```kotlin
override fun shouldOverrideUrlLoading(
    view: WebView?,
    request: WebResourceRequest?
): Boolean {
    val url = request?.url?.toString() ?: return false

    // 允許所有 Odoo URL（包括 /web/login）
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

### 測試計畫

1. 卸載舊版本 App
2. 安裝 v1.0.10
3. 輸入伺服器 URL 和資料庫
4. 預期：WebView 顯示 Odoo 登入頁面
5. 在 WebView 中登入
6. 預期：能看到 Dashboard 和所有應用程式
