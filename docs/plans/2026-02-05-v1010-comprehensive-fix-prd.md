# PRD: v1.0.10 綜合修復方案

## 問題總結

從 MVP 版本開始，WebView 主要內容區域就是空白的。這不是後續修改造成的問題。

## 根本原因分析

### Chrome vs WebView 差異

| 項目 | Chrome | WebView |
|------|--------|---------|
| JavaScript 執行 | 完整、快速 | 精簡、較慢 |
| 預設設定 | 優化過 | 需手動開啟 |
| 視窗處理 | 完整支援 | 預設不支援 |

### OWL 框架架構

```
WebClient
├── MainComponentsContainer（靜態，初始化時載入）
│   ├── Sidebar/Menu ✅ 正常
│   ├── Notification ✅ 正常
│   └── User Settings ✅ 正常
└── ActionContainer（動態，依賴事件觸發）
    └── Dashboard/Apps ❌ 空白
```

**關鍵發現**：ActionContainer 需要 `ACTION_MANAGER:UPDATE` 事件才會渲染內容。

### 缺少的 WebView 設定

1. `javaScriptCanOpenWindowsAutomatically` - 預設 false
2. `setSupportMultipleWindows` - 預設 false
3. `onCreateWindow` 處理 - 未實作
4. Console 錯誤監聽 - 未實作

## v1.0.10 修復方案

### 修改內容

1. **擴充 WebView 設定**
   - `javaScriptCanOpenWindowsAutomatically = true`
   - `mediaPlaybackRequiresUserGesture = false`
   - `setSupportMultipleWindows(true)`

2. **添加 WebChromeClient 視窗處理**
   - `onCreateWindow` - 處理 OWL 視窗請求
   - `onConsoleMessage` - 記錄 JavaScript 錯誤

3. **注入 JavaScript 修復**
   - 頁面載入後觸發 `resize` 事件
   - 嘗試顯示隱藏的 `.o_action_manager`

4. **使用 debug=assets URL**
   - 讓 Odoo 重新生成 assets bundle

## 測試計畫

測試優先順序：
1. v1.0.10 綜合方案
2. 如果無效，查看 Console log 找具體錯誤
