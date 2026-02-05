# PRD: WebView Auto-Login & Login UI Enhancement

## 問題分析

### 問題 1: WebView 內容被遮擋/需要重複登入
**現象**: 使用者在 App 登入後，WebView 仍顯示 Odoo 登入頁面，需要再次輸入帳號密碼。

**根本原因**:
- `OdooAccount` 模型沒有儲存密碼
- WebView 載入 `/web` 時沒有自動帶入認證資訊
- 沒有使用 Odoo Session Cookie 進行自動登入

**解決方案**:
1. 在 `OdooAccount` 中安全儲存加密密碼
2. WebView 載入時使用 JavaScript 自動填入帳密並提交
3. 或使用 Odoo JSON-RPC API 獲取 session cookie 後載入 WebView

### 問題 2: 登入欄位可見性差
**現象**: 登入表單的輸入欄位邊框不清晰，難以辨識輸入範圍。

**根本原因**:
- `OutlinedTextField` 在深色主題背景 (primary color) 上對比度不足
- 缺少明確的欄位背景和邊框樣式

**解決方案**:
1. 為輸入欄位添加白色/淺色背景
2. 增強邊框對比度
3. 使用 Card 或 Surface 包裝表單區塊

---

## 技術實現計劃

### Phase 1: 修改 OdooAccount 模型
```kotlin
// 添加加密密碼欄位
data class OdooAccount(
    ...
    val encryptedPassword: String? = null,  // 新增
    ...
)
```

### Phase 2: 實現 WebView 自動登入
```kotlin
// MainScreen.kt - 修改 OdooWebView
fun OdooWebView(
    serverUrl: String,
    username: String,      // 新增
    password: String,      // 新增
    database: String,      // 新增
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit
) {
    // 在 onPageFinished 中注入 JavaScript 自動登入
    override fun onPageFinished(view: WebView?, url: String?) {
        if (url?.contains("/web/login") == true) {
            // 自動填入並提交登入表單
            view?.evaluateJavascript("""
                document.querySelector('input[name="login"]').value = '$username';
                document.querySelector('input[name="password"]').value = '$password';
                document.querySelector('input[name="db"]').value = '$database';
                document.querySelector('form').submit();
            """.trimIndent(), null)
        }
        onLoadingChanged(false)
    }
}
```

### Phase 3: 重新設計登入 UI
**設計方向**: 現代化、高對比度、專業感

**視覺改進**:
1. 表單區塊使用半透明白色卡片背景
2. 輸入欄位使用白色背景 + 明確邊框
3. 邊框使用 2dp 粗細，顏色使用 primary 或深灰色
4. 添加欄位圖標 (server, database, user, lock)
5. 按鈕使用漸層或高對比色

---

## 檔案修改清單

| 檔案 | 修改內容 |
|------|----------|
| `OdooAccount.kt` | 添加 `encryptedPassword` 欄位 |
| `OdooAccountDao.kt` | 更新查詢以包含密碼 |
| `LoginViewModel.kt` | 登入時儲存加密密碼 |
| `MainScreen.kt` | 傳遞帳密給 WebView，實現自動登入 |
| `MainViewModel.kt` | 提供解密後的密碼 |
| `LoginScreen.kt` | 重新設計表單 UI，添加背景和邊框 |
| `EncryptionHelper.kt` | 新增 - 密碼加密工具類 |

---

## UI 設計規格

### 登入表單卡片
- 背景: `Color.White.copy(alpha = 0.95f)`
- 圓角: `24.dp`
- 內邊距: `24.dp`
- 陰影: `elevation = 8.dp`

### 輸入欄位
- 背景: `Color.White`
- 邊框: `2.dp`, `MaterialTheme.colorScheme.outline`
- 圓角: `12.dp`
- 高度: `56.dp`
- 前置圖標: 使用 Material Icons
- 文字顏色: `onSurface` (深色)

### 按鈕
- 背景: `MaterialTheme.colorScheme.onPrimary` (白色)
- 文字: `MaterialTheme.colorScheme.primary`
- 高度: `56.dp`
- 圓角: `12.dp`

---

## 安全考量

1. 密碼使用 Android Keystore 加密存儲
2. 加密使用 AES-256-GCM
3. 密碼不以明文形式傳遞或記錄
4. JavaScript 注入時防止 XSS

---

## 測試計劃

1. [ ] 登入後 WebView 自動進入 Odoo 主頁面
2. [ ] 不需要在 WebView 中重複輸入帳密
3. [ ] 登入欄位清晰可見
4. [ ] 深色/淺色模式下 UI 正常
5. [ ] 密碼正確加密存儲
