# PRD: WebView Dashboard 空白頁面問題分析

## 問題描述

WoowTech Odoo Android App 在 WebView 中載入 Odoo 17/18 時，主頁 Dashboard 顯示空白，但其他頁面正常。

## 測試結果摘要

### 能正常顯示的頁面
- ✅ 側邊選單（應用程式列表）
- ✅ 通知面板
- ✅ 個人設定頁面

### 空白的頁面
- ❌ 主頁 Dashboard 內容區域

### 環境資訊
- Odoo 版本：17 或 18（使用 OWL 框架）
- 手機瀏覽器（Chrome）：能正常顯示 Dashboard
- App WebView：Dashboard 空白，其他頁面正常
- 問題持續性：返回主頁仍然空白，刷新無效

## 根本原因分析

### 1. Odoo 17/18 OWL 框架特性

Odoo 17/18 使用 [OWL 框架](https://www.odoo.com/documentation/18.0/developer/reference/frontend/owl_components.html) 來渲染前端組件。OWL 是類似 Vue/React 的響應式框架，對 JavaScript 執行環境有特定要求。

根據 [Odoo 18 Dashboard 文檔](https://www.odoo.com/documentation/18.0/developer/tutorials/discover_js_framework/02_build_a_dashboard.html)，Dashboard 使用 Layout 組件和 OWL 模板系統。

### 2. Android WebView Viewport 問題

根據 [Android 開發者文檔](https://developer.android.com/develop/ui/views/layout/webapps/targeting)：

> When your page is rendered in a WebView, it doesn't use wide viewport mode by default. You can enable wide viewport mode with `setUseWideViewPort()`.

目前程式碼設定：
```kotlin
loadWithOverviewMode = false
useWideViewPort = false
```

這可能導致 OWL 框架的 Dashboard 組件無法正確計算和渲染布局。

### 3. 為什麼其他頁面能正常顯示？

| 頁面 | 渲染方式 | 是否依賴 viewport |
|------|----------|-------------------|
| 側邊選單 | 固定寬度 | 否 |
| 通知面板 | 彈出層 | 否 |
| 個人設定 | 簡單表單 | 否 |
| Dashboard | OWL 動態布局 | **是** |

Dashboard 使用 OWL 的響應式布局系統，需要正確的 viewport 來計算組件位置和大小。

### 4. 已知的 Odoo 18 Mobile UI 問題

根據 [Odoo 論壇](https://www.odoo.com/forum/help-1/odoo-18-0-community-edition-mobile-ui-error-269071)，Odoo 18 Community Edition 在 Mobile UI 有已知問題。

## 可能的解決方案

### 方案 A：調整 Viewport 設定（推薦先嘗試）

```kotlin
settings.apply {
    // 啟用 wide viewport 讓 OWL 框架正確計算布局
    loadWithOverviewMode = true
    useWideViewPort = true

    // 設定初始縮放比例
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false
}
```

**優點**：最小改動，可能直接解決問題
**缺點**：可能影響其他頁面的顯示

### 方案 B：使用 Desktop User-Agent

讓 Odoo 認為是桌面瀏覽器，使用桌面版 Dashboard：

```kotlin
// 使用桌面版 User-Agent
userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
```

**優點**：可能獲得更穩定的 Dashboard 渲染
**缺點**：失去 mobile 優化的 UI

### 方案 C：注入 JavaScript 修復

在 `onPageFinished` 中注入 JavaScript 來觸發 OWL 重新渲染：

```kotlin
override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    // 觸發視窗大小變化事件，讓 OWL 重新計算布局
    view?.evaluateJavascript(
        "window.dispatchEvent(new Event('resize'));",
        null
    )
    onLoadingChanged(false)
}
```

**優點**：針對性修復
**缺點**：可能有副作用

### 方案 D：組合方案

結合方案 A + C：
1. 啟用 wide viewport
2. 頁面載入完成後觸發 resize 事件

## 測試計畫

1. **方案 A 測試**：只修改 viewport 設定
2. **方案 B 測試**：只修改 User-Agent
3. **方案 C 測試**：只注入 resize 事件
4. **方案 D 測試**：組合測試

每個方案都需要測試：
- [ ] Dashboard 主頁是否顯示
- [ ] 側邊選單是否正常
- [ ] 通知面板是否正常
- [ ] 個人設定是否正常
- [ ] 其他 Odoo 模組是否正常

## 下一步行動

1. 建立 v1.0.9，測試方案 A（調整 viewport）
2. 如果方案 A 無效，測試方案 D（組合方案）
3. 記錄測試結果，迭代修復

## 參考資料

- [Odoo OWL Components](https://www.odoo.com/documentation/18.0/developer/reference/frontend/owl_components.html)
- [Odoo 18 Dashboard Tutorial](https://www.odoo.com/documentation/18.0/developer/tutorials/discover_js_framework/02_build_a_dashboard.html)
- [Android WebView Viewport](https://developer.android.com/develop/ui/views/layout/webapps/targeting)
- [Odoo 18 Mobile UI Issues](https://www.odoo.com/forum/help-1/odoo-18-0-community-edition-mobile-ui-error-269071)
