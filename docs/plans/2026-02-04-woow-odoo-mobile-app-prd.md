# WoowTech Odoo Mobile App - 產品需求文件 (PRD)

**Product Requirements Document**

---

## 文件資訊 | Document Information

| 項目 | 內容 |
|------|------|
| 專案名稱 | WoowTech Odoo Mobile App |
| 版本 | 1.0.0 |
| 建立日期 | 2026-02-04 |
| 作者 | WoowTech Development Team |
| 狀態 | Draft |

---

## 1. 執行摘要 | Executive Summary

### 1.1 專案概述 | Project Overview

WoowTech Odoo Mobile App 是一款 Android 原生行動應用程式，旨在讓使用者能夠透過手機便捷地存取其 Odoo ERP 系統。應用程式採用 WebView 混合架構，結合原生 Android UI 元素，提供流暢的行動體驗。

The WoowTech Odoo Mobile App is an Android native mobile application designed to provide convenient mobile access to Odoo ERP systems. The app uses a hybrid WebView architecture combined with native Android UI elements for a seamless mobile experience.

### 1.2 目標 | Objectives

- 提供簡單直觀的登入流程（URL + 資料庫 + 帳號密碼）
- 使用 WebView 顯示 Odoo 手機版網頁介面
- 提供原生置頂工具欄，方便存取個人設定、帳號管理等功能
- 支援多帳號切換
- 支援 Odoo 18 Community Edition

### 1.3 目標用戶 | Target Users

- Odoo 系統管理員
- 企業員工（銷售、倉管、人資等）
- 外部維護供應商
- 任何需要行動存取 Odoo 的使用者

---

## 2. 市場調研 | Market Research

### 2.1 競品分析 | Competitor Analysis

| 應用程式 | 技術架構 | 優點 | 缺點 |
|---------|---------|------|------|
| **Cybrosys mobo FullSuite** | Native + WebView | 完整功能、多帳號支援、生物辨識 | 閉源商業軟體 |
| **OdooAppBox** | Ionic 3 (TypeScript) | 跨平台、配置驅動更新 | 依賴後端插件、AGPL 授權限制 |
| **OCA Mobile** | React Native | MIT 授權、跨平台、社群支援 | 功能較基本 |
| **OdooMobileX** | Kotlin + Jetpack Compose | 現代架構、Clean Architecture | 已停止維護 (Archived 2023) |

### 2.2 參考資料來源 | Reference Sources

1. **GitHub Repositories**:
   - [OdooAppBox](https://github.com/youzengjian/OdooAppBox) - Ionic 3 framework
   - [OCA Mobile](https://github.com/kmee/oca-mobile) - React Native
   - [OdooApp-Android](https://github.com/19111OdooApp/OdooApp-Android) - Kotlin/Jetpack Compose

2. **Official Documentation**:
   - [Odoo 18 External API](https://www.odoo.com/documentation/18.0/developer/reference/external_api.html)
   - [Odoo 18 Web Services](https://www.odoo.com/documentation/18.0/developer/howtos/web_services.html)

3. **Reference App**:
   - [Cybrosys mobo FullSuite](https://play.google.com/store/apps/details?id=com.cybrosys.odoo_mobile_community)

---

## 3. 技術規格 | Technical Specifications

### 3.1 技術架構 | Technology Stack

| 層級 | 技術選擇 | 說明 |
|------|---------|------|
| **Platform** | Android | 目標 Android 7.0+ (API 24+) |
| **Language** | Kotlin | 現代 Android 開發首選 |
| **UI Framework** | Jetpack Compose | 宣告式 UI 框架 |
| **Architecture** | MVVM + Clean Architecture | 分層架構設計 |
| **WebView** | Android WebView | 顯示 Odoo 手機版網頁 |
| **Network** | OkHttp + Retrofit | HTTP 客戶端與 API 呼叫 |
| **Local Storage** | DataStore + Room | 偏好設定與資料快取 |
| **DI** | Hilt | 依賴注入 |
| **Security** | Biometric API | 生物辨識認證 |

### 3.2 Odoo API 整合 | Odoo API Integration

#### 3.2.1 認證方式 | Authentication Methods

**JSON-RPC 認證 (主要)**:
```
Endpoint: /web/session/authenticate
Method: POST
Content-Type: application/json

Request Body:
{
  "jsonrpc": "2.0",
  "method": "call",
  "params": {
    "db": "<database_name>",
    "login": "<username>",
    "password": "<password>"
  },
  "id": 1
}

Response:
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "uid": <user_id>,
    "session_id": "<session_token>",
    "username": "<username>",
    "name": "<display_name>",
    ...
  }
}
```

**XML-RPC 認證 (備用)**:
```
Endpoint: /xmlrpc/2/common
Method: authenticate(db, login, password, {})
Returns: uid (user ID) or false
```

#### 3.2.2 Session 管理 | Session Management

- 認證成功後儲存 session_id 作為 Cookie
- WebView 需載入相同的 session Cookie
- 實作 session 過期檢測與自動重新認證

#### 3.2.3 API 版本相容性 | API Compatibility

> **重要提醒**: XML-RPC 和 JSON-RPC API 將在 Odoo 20 (2026 年秋季) 被棄用，未來將由 JSON-2 API 取代。目前版本針對 Odoo 18 Community Edition 設計。

---

## 4. 功能規格 | Functional Specifications

### 4.1 核心功能架構 | Core Feature Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    WoowTech Odoo Mobile App                 │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │   Login     │  │   Main      │  │    Configuration    │ │
│  │   Module    │  │   WebView   │  │       Module        │ │
│  │             │  │             │  │                     │ │
│  │ • URL Input │  │ • Odoo Web  │  │ • Profile Details   │ │
│  │ • Database  │  │ • Floating  │  │ • Settings          │ │
│  │ • Username  │  │   Menu      │  │ • Switch Accounts   │ │
│  │ • Password  │  │   Button    │  │ • Add Account       │ │
│  │             │  │             │  │ • Logout            │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 登入模組 | Login Module

#### 4.2.1 登入畫面 UI 規格

**畫面元素**:
| 元素 | 類型 | 說明 |
|------|------|------|
| App Logo | Image | 頂部顯示 "mobo FullSuite" 或自訂品牌 Logo |
| 標題 | Text | "Add New Account" |
| 副標題 | Text | "Enter your credentials to continue" |
| URL 輸入框 | TextField | Protocol 選擇 (https://) + URL 輸入 |
| Database 輸入框 | TextField | 資料庫名稱輸入 |
| Next 按鈕 | Button | 進入下一步（輸入帳號密碼） |

**登入流程**:
```
Step 1: 輸入伺服器資訊
├── Protocol: https:// (下拉選擇 http:// 或 https://)
├── Server URL: example.odoo.com
└── Database Name: production_db

Step 2: 驗證伺服器連線
├── 檢查 URL 是否可達
├── 驗證資料庫是否存在
└── 顯示錯誤訊息（如有）

Step 3: 輸入帳號密碼
├── Username/Email
├── Password
└── "Remember me" 選項

Step 4: 認證
├── 呼叫 /web/session/authenticate
├── 儲存 session 資訊
├── 儲存帳號資料（加密）
└── 導航至主畫面
```

#### 4.2.2 登入錯誤處理

| 錯誤類型 | 錯誤訊息 (EN) | 錯誤訊息 (繁中) |
|---------|--------------|----------------|
| 網路錯誤 | Unable to connect to server | 無法連接到伺服器 |
| 無效 URL | Invalid server URL | 伺服器網址無效 |
| 資料庫不存在 | Database not found | 找不到資料庫 |
| 認證失敗 | Invalid username or password | 帳號或密碼錯誤 |
| Session 過期 | Session expired, please login again | Session 已過期，請重新登入 |

### 4.3 主畫面模組 | Main Screen Module

#### 4.3.1 WebView 區域

- 載入 Odoo 手機版網頁 (responsive web view)
- 共享 session Cookie 以維持登入狀態
- 支援 JavaScript
- 處理 file upload/download
- 支援返回鍵導航

#### 4.3.2 置頂工具欄 | Floating Action Menu

**位置**: 畫面右下角浮動按鈕 (FAB)

**點擊後展開選項**:
1. **Configuration** - 進入設定頁面
2. **Refresh** - 重新載入 WebView
3. **Share** - 分享當前頁面 URL

### 4.4 Configuration 模組 | Configuration Module

#### 4.4.1 Configuration 主頁面

**畫面結構**:
```
┌─────────────────────────────────────────────┐
│  < Configuration                            │
├─────────────────────────────────────────────┤
│  ┌─────────────────────────────────────┐   │
│  │ [Avatar] 系統管理者                  │   │
│  │          user@example.com        >  │   │
│  └─────────────────────────────────────┘   │
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │ ⚙ Settings                          │   │
│  │   App preferences and sync options >│   │
│  ├─────────────────────────────────────┤   │
│  │ 👥 Switch Accounts                  │   │
│  │   Manage and switch between      ⌄  │   │
│  │   accounts                          │   │
│  │   ┌─────────────────────────────┐   │   │
│  │   │ No accounts                 │   │   │
│  │   └─────────────────────────────┘   │   │
│  │   [+ Add Account]                   │   │
│  ├─────────────────────────────────────┤   │
│  │ ↪ Logout                            │   │
│  │   Sign out from this device         │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

#### 4.4.2 Profile Details 頁面

**畫面元素**:
| 欄位 | 類型 | 可編輯 | API 對應 |
|------|------|--------|----------|
| Avatar | Image | Yes | res.users.image_1920 |
| Full Name | TextField | Yes | res.users.name |
| Email | TextField | Read-only | res.users.login |
| Phone | TextField | Yes | res.users.phone |
| Mobile | TextField | Yes | res.users.mobile |
| Website | TextField | Yes | res.users.website |
| Job Title | TextField | Yes | res.users.function |

#### 4.4.3 Settings 頁面

**設定分類**:

**Appearance (外觀)**:
| 設定項目 | 類型 | 說明 |
|---------|------|------|
| Reduce Motion | Toggle | 減少動畫效果 |

**Security (安全性)**:
| 設定項目 | 類型 | 說明 |
|---------|------|------|
| App Lock | Toggle | 啟用生物辨識鎖定 |

**Language & Region (語言與地區)**:
| 設定項目 | 類型 | 說明 |
|---------|------|------|
| Language | Picker | 選擇偏好語言 |
| Currency | Picker | 預設交易貨幣 |
| Timezone | Picker | 本地時區 |

**Data & Storage (資料與儲存)**:
| 設定項目 | 類型 | 說明 |
|---------|------|------|
| Clear Cache | Button | 清除暫存資料 (顯示目前大小) |

**Help & Support (幫助與支援)**:
| 設定項目 | 類型 | 連結 |
|---------|------|------|
| Odoo Help Center | Link | 官方文件與指南 |
| Odoo Support | Link | 建立支援工單 |
| Odoo Community Forum | Link | 社群論壇 |

**About (關於)**:
| 設定項目 | 類型 | 連結 |
|---------|------|------|
| Visit Website | Link | www.woowtech.com |
| Contact Us | Link | Email 聯絡 |
| More Apps | Link | 其他應用程式 |
| Social Links | Icons | Facebook, LinkedIn, Instagram, YouTube |
| Copyright | Text | © 2026 WoowTech |

### 4.5 多帳號管理 | Multi-Account Management

#### 4.5.1 帳號資料結構

```kotlin
data class OdooAccount(
    val id: String,                    // UUID
    val serverUrl: String,             // https://example.odoo.com
    val database: String,              // database name
    val username: String,              // login email
    val displayName: String,           // user display name
    val avatarUrl: String?,            // profile image URL
    val sessionId: String?,            // current session (encrypted)
    val lastLogin: Long,               // timestamp
    val isActive: Boolean              // current active account
)
```

#### 4.5.2 帳號管理功能

| 功能 | 說明 |
|------|------|
| Add Account | 新增另一個 Odoo 帳號 |
| Switch Account | 快速切換至已儲存的帳號 |
| Remove Account | 移除已儲存的帳號 |
| Edit Account | 修改帳號資訊（重新驗證） |

---

## 5. 使用者介面設計 | UI/UX Design

### 5.1 設計規範 | Design Guidelines

**色彩系統**:
| 用途 | 顏色 | Hex |
|------|------|-----|
| Primary | Crimson Red | #C62828 |
| Primary Dark | Dark Red | #8E0000 |
| Accent | Pink | #E91E63 |
| Background | Light Gray | #F5F5F5 |
| Surface | White | #FFFFFF |
| Text Primary | Dark Gray | #212121 |
| Text Secondary | Medium Gray | #757575 |
| Error | Red | #D32F2F |
| Success | Green | #388E3C |

**Typography**:
| 樣式 | Font | Size | Weight |
|------|------|------|--------|
| H1 | Roboto | 24sp | Bold |
| H2 | Roboto | 20sp | Medium |
| Body | Roboto | 16sp | Regular |
| Caption | Roboto | 12sp | Regular |
| Button | Roboto | 14sp | Medium |

**Spacing**:
| 名稱 | 尺寸 |
|------|------|
| xs | 4dp |
| sm | 8dp |
| md | 16dp |
| lg | 24dp |
| xl | 32dp |

### 5.2 導航架構 | Navigation Architecture

```
App Launch
    │
    ├── Has Active Session?
    │   ├── Yes → Main Screen (WebView)
    │   └── No → Login Screen
    │
Main Screen
    │
    ├── WebView (Odoo Mobile Web)
    │
    └── FAB Menu
        ├── Configuration → Configuration Screen
        │   ├── Profile Card → Profile Details
        │   ├── Settings → Settings Screen
        │   ├── Switch Accounts → Account List
        │   │   └── Add Account → Login Screen
        │   └── Logout → Login Screen
        │
        ├── Refresh → Reload WebView
        └── Share → Share Intent
```

---

## 6. 安全性需求 | Security Requirements

### 6.1 資料安全 | Data Security

| 項目 | 實作方式 |
|------|---------|
| 密碼儲存 | 使用 Android Keystore 加密 |
| Session 儲存 | EncryptedSharedPreferences |
| 網路傳輸 | 強制 HTTPS (可選擇允許 HTTP) |
| Certificate Pinning | 可選配置 |

### 6.2 認證安全 | Authentication Security

| 項目 | 實作方式 |
|------|---------|
| 生物辨識 | Android Biometric API |
| Session 過期 | 自動檢測並重新導向登入 |
| 多次失敗鎖定 | 5 次失敗後延遲 30 秒 |

### 6.3 WebView 安全 | WebView Security

| 項目 | 設定 |
|------|------|
| JavaScript | 啟用（必須） |
| Mixed Content | 禁止（僅 HTTPS） |
| File Access | 限制於下載目錄 |
| Geolocation | 需使用者授權 |

---

## 7. 效能需求 | Performance Requirements

| 指標 | 目標 |
|------|------|
| 冷啟動時間 | < 2 秒 |
| 登入響應時間 | < 3 秒 |
| WebView 載入 | < 5 秒 |
| 記憶體使用 | < 150 MB |
| APK 大小 | < 20 MB |

---

## 8. 專案結構 | Project Structure

```
woow_odoo_app/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/woowtech/odoo/
│   │   │   │   ├── WoowOdooApp.kt
│   │   │   │   ├── di/
│   │   │   │   │   └── AppModule.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── api/
│   │   │   │   │   │   ├── OdooApiService.kt
│   │   │   │   │   │   └── OdooJsonRpcClient.kt
│   │   │   │   │   ├── repository/
│   │   │   │   │   │   ├── AccountRepository.kt
│   │   │   │   │   │   └── UserRepository.kt
│   │   │   │   │   └── local/
│   │   │   │   │       ├── AccountDao.kt
│   │   │   │   │       └── AppDatabase.kt
│   │   │   │   ├── domain/
│   │   │   │   │   ├── model/
│   │   │   │   │   │   ├── OdooAccount.kt
│   │   │   │   │   │   └── UserProfile.kt
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── AuthenticateUseCase.kt
│   │   │   │   │       └── GetProfileUseCase.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── theme/
│   │   │   │   │   │   ├── Color.kt
│   │   │   │   │   │   ├── Theme.kt
│   │   │   │   │   │   └── Type.kt
│   │   │   │   │   ├── navigation/
│   │   │   │   │   │   └── NavGraph.kt
│   │   │   │   │   ├── login/
│   │   │   │   │   │   ├── LoginScreen.kt
│   │   │   │   │   │   └── LoginViewModel.kt
│   │   │   │   │   ├── main/
│   │   │   │   │   │   ├── MainScreen.kt
│   │   │   │   │   │   ├── MainViewModel.kt
│   │   │   │   │   │   └── OdooWebView.kt
│   │   │   │   │   ├── config/
│   │   │   │   │   │   ├── ConfigScreen.kt
│   │   │   │   │   │   ├── ProfileScreen.kt
│   │   │   │   │   │   └── SettingsScreen.kt
│   │   │   │   │   └── components/
│   │   │   │   │       ├── TopBar.kt
│   │   │   │   │       ├── AccountCard.kt
│   │   │   │   │       └── SettingsItem.kt
│   │   │   │   └── util/
│   │   │   │       ├── SecurityUtils.kt
│   │   │   │       └── NetworkUtils.kt
│   │   │   ├── res/
│   │   │   │   ├── drawable/
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   ├── strings-zh-rTW.xml
│   │   │   │   │   └── colors.xml
│   │   │   │   └── xml/
│   │   │   │       └── network_security_config.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── docs/
    └── plans/
        └── 2026-02-04-woow-odoo-mobile-app-prd.md
```

---

## 9. 開發階段 | Development Phases

### Phase 1: 核心功能 (MVP) - 預估 4 週

| 功能 | 優先級 | 狀態 |
|------|--------|------|
| 登入畫面 UI | P0 | Planned |
| JSON-RPC 認證 | P0 | Planned |
| WebView 整合 | P0 | Planned |
| Session 管理 | P0 | Planned |
| 基本 Configuration 頁面 | P0 | Planned |
| 登出功能 | P0 | Planned |

### Phase 2: 帳號管理 - 預估 2 週

| 功能 | 優先級 | 狀態 |
|------|--------|------|
| 多帳號儲存 | P1 | Planned |
| 帳號切換 | P1 | Planned |
| 帳號移除 | P1 | Planned |
| 個人資料編輯 | P1 | Planned |

### Phase 3: 進階功能 - 預估 2 週

| 功能 | 優先級 | 狀態 |
|------|--------|------|
| 生物辨識鎖定 | P2 | Planned |
| 多語言支援 | P2 | Planned |
| 深色模式 | P2 | Planned |
| 快取管理 | P2 | Planned |

### Phase 4: 優化與發佈 - 預估 1 週

| 功能 | 優先級 | 狀態 |
|------|--------|------|
| 效能優化 | P2 | Planned |
| 錯誤處理完善 | P2 | Planned |
| Play Store 準備 | P2 | Planned |
| 文件撰寫 | P2 | Planned |

---

## 10. 測試計劃 | Testing Plan

### 10.1 單元測試 | Unit Tests

- ViewModel 邏輯測試
- Repository 測試
- UseCase 測試
- Utility 函數測試

### 10.2 整合測試 | Integration Tests

- API 認證流程測試
- 帳號管理流程測試
- WebView Cookie 同步測試

### 10.3 UI 測試 | UI Tests

- 登入流程 E2E 測試
- 導航測試
- 無障礙測試

### 10.4 相容性測試 | Compatibility Tests

| 測試項目 | 範圍 |
|---------|------|
| Android 版本 | 7.0 - 14 |
| 螢幕尺寸 | 5" - 10" |
| Odoo 版本 | 16, 17, 18 Community |

---

## 11. 風險評估 | Risk Assessment

| 風險 | 影響 | 機率 | 緩解措施 |
|------|------|------|---------|
| Odoo API 變更 | 高 | 中 | 抽象 API 層，便於更新 |
| WebView 相容性 | 中 | 中 | 使用最新 WebView 版本 |
| Session 管理複雜度 | 中 | 高 | 完善的錯誤處理與重試機制 |
| 安全漏洞 | 高 | 低 | 遵循 Android 安全最佳實踐 |

---

## 12. 附錄 | Appendix

### A. Odoo JSON-RPC API 參考

**獲取用戶資料**:
```json
{
  "jsonrpc": "2.0",
  "method": "call",
  "params": {
    "service": "object",
    "method": "execute_kw",
    "args": [
      "database",
      uid,
      "password",
      "res.users",
      "read",
      [[uid]],
      {"fields": ["name", "login", "phone", "mobile", "image_1920"]}
    ]
  },
  "id": 2
}
```

**更新用戶資料**:
```json
{
  "jsonrpc": "2.0",
  "method": "call",
  "params": {
    "service": "object",
    "method": "execute_kw",
    "args": [
      "database",
      uid,
      "password",
      "res.users",
      "write",
      [[uid], {"phone": "0226519677"}]
    ]
  },
  "id": 3
}
```

### B. 多語言字串 | Localization Strings

| Key | English | 繁體中文 |
|-----|---------|---------|
| app_name | WoowTech Odoo | WoowTech Odoo |
| login_title | Add New Account | 新增帳號 |
| login_subtitle | Enter your credentials to continue | 輸入您的認證資訊以繼續 |
| server_url | Server URL | 伺服器網址 |
| database_name | Database Name | 資料庫名稱 |
| username | Username | 使用者名稱 |
| password | Password | 密碼 |
| login_button | Login | 登入 |
| next_button | Next | 下一步 |
| configuration | Configuration | 設定 |
| settings | Settings | 設定 |
| profile_details | Profile Details | 個人資料 |
| switch_accounts | Switch Accounts | 切換帳號 |
| add_account | Add Account | 新增帳號 |
| logout | Logout | 登出 |
| appearance | Appearance | 外觀 |
| security | Security | 安全性 |
| language_region | Language & Region | 語言與地區 |
| data_storage | Data & Storage | 資料與儲存 |
| help_support | Help & Support | 幫助與支援 |
| about | About | 關於 |

### C. 參考連結 | Reference Links

- [Odoo 18 External API Documentation](https://www.odoo.com/documentation/18.0/developer/reference/external_api.html)
- [Android Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Material Design 3](https://m3.material.io/)
- [Android Security Best Practices](https://developer.android.com/topic/security/best-practices)

---

**文件結束 | End of Document**

© 2026 WoowTech. All rights reserved.
