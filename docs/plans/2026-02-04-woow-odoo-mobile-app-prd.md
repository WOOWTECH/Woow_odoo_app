# WoowTech Odoo Mobile App - 產品需求文件 (PRD)

**Product Requirements Document**

---

## 文件資訊 | Document Information

| 項目 | 內容 |
|------|------|
| 專案名稱 | WoowTech Odoo Mobile App |
| 版本 | 1.1.0 |
| 建立日期 | 2026-02-04 |
| 更新日期 | 2026-02-04 |
| 作者 | WoowTech Development Team |
| 狀態 | **Approved** |

---

## 需求確認摘要 | Requirements Summary

| 項目 | 決定 |
|------|------|
| **發佈方式** | 內部分發 APK |
| **App 名稱** | WoowTech Odoo |
| **Logo** | WoowTech 藍紫色圓形 Logo |
| **目標 Odoo** | 僅 Odoo 18 Community |
| **主題色** | #6183FC（用戶可自訂任意顏色） |
| **語言** | 繁體中文 + 英文 |
| **安全功能** | 生物辨識 + PIN 碼（可選啟用） |
| **網路協定** | 僅 HTTPS |
| **測試伺服器** | https://woowtechaicoder-odootest.woowtech.io/ |
| **離線功能** | 基本快取帳號資訊 |
| **工具欄** | 頂部固定（Logo + 漢堡選單） |
| **公司網站** | https://aiot.woowtech.io |
| **聯絡 Email** | woowtech@designsmart.com.tw |
| **最低 Android** | Android 10+ (API 29) |
| **Package Name** | io.woowtech.odoo |

---

## 1. 執行摘要 | Executive Summary

### 1.1 專案概述 | Project Overview

WoowTech Odoo Mobile App 是一款 Android 原生行動應用程式，旨在讓使用者能夠透過手機便捷地存取其 Odoo ERP 系統。應用程式採用 WebView 混合架構，結合原生 Android UI 元素（頂部固定工具欄），提供流暢的行動體驗。

The WoowTech Odoo Mobile App is an Android native mobile application designed to provide convenient mobile access to Odoo ERP systems. The app uses a hybrid WebView architecture combined with native Android UI elements (fixed top toolbar) for a seamless mobile experience.

### 1.2 目標 | Objectives

- 提供簡單直觀的登入流程（URL + 資料庫 + 帳號密碼）
- 使用 WebView 顯示 Odoo 手機版網頁介面
- 提供原生頂部固定工具欄（Logo + 漢堡選單），方便存取個人設定、帳號管理等功能
- 支援多帳號切換
- 支援 Odoo 18 Community Edition
- 支援自訂主題色彩
- 提供可選的生物辨識/PIN 碼鎖定功能

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
| **Platform** | Android | 目標 Android 10+ (API 29) |
| **Language** | Kotlin | 現代 Android 開發首選 |
| **UI Framework** | Jetpack Compose | 宣告式 UI 框架 |
| **Architecture** | MVVM + Clean Architecture | 分層架構設計 |
| **WebView** | Android WebView | 顯示 Odoo 手機版網頁 |
| **Network** | OkHttp + Retrofit | HTTP 客戶端與 API 呼叫 |
| **Local Storage** | DataStore + Room | 偏好設定與資料快取 |
| **DI** | Hilt | 依賴注入 |
| **Security** | Biometric API + PIN | 生物辨識與 PIN 碼認證 |

### 3.2 專案配置 | Project Configuration

| 項目 | 值 |
|------|---|
| Package Name | `io.woowtech.odoo` |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 (Android 14) |
| Compile SDK | 34 |
| Kotlin Version | 1.9.x |
| Compose BOM | 2024.02.00 |

### 3.3 Odoo API 整合 | Odoo API Integration

#### 3.3.1 認證方式 | Authentication Methods

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

#### 3.3.2 Session 管理 | Session Management

- 認證成功後儲存 session_id 作為 Cookie
- WebView 需載入相同的 session Cookie
- 實作 session 過期檢測與自動重新認證

#### 3.3.3 目標版本 | Target Version

- **支援版本**: 僅 Odoo 18 Community Edition
- **網路協定**: 僅 HTTPS（強制安全連線）
- **測試伺服器**: https://woowtechaicoder-odootest.woowtech.io/

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
│  │ • URL Input │  │ • Top Bar   │  │ • Profile Details   │ │
│  │ • Database  │  │   (Logo +   │  │ • Settings          │ │
│  │ • Username  │  │   Hamburger)│  │ • Switch Accounts   │ │
│  │ • Password  │  │ • Odoo Web  │  │ • Add Account       │ │
│  │             │  │             │  │ • Logout            │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 登入模組 | Login Module

#### 4.2.1 登入畫面 UI 規格

**畫面元素**:
| 元素 | 類型 | 說明 |
|------|------|------|
| App Logo | Image | 頂部顯示 WoowTech Logo（藍紫色圓形） |
| App Name | Text | "WoowTech Odoo" |
| 標題 | Text | "Add New Account" / "新增帳號" |
| 副標題 | Text | "Enter your credentials to continue" / "輸入您的認證資訊以繼續" |
| URL 輸入框 | TextField | "https://" 前綴 + URL 輸入 |
| Database 輸入框 | TextField | 資料庫名稱輸入 |
| Next 按鈕 | Button | 進入下一步（輸入帳號密碼） |

**登入流程**:
```
Step 1: 輸入伺服器資訊
├── Server URL: https://example.odoo.com (僅 HTTPS)
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
| 非 HTTPS | Secure connection required (HTTPS) | 需要安全連線 (HTTPS) |

### 4.3 主畫面模組 | Main Screen Module

#### 4.3.1 頂部固定工具欄 | Fixed Top Toolbar

**位置**: 畫面頂部，永遠固定顯示

**畫面結構**:
```
┌─────────────────────────────────────────────┐
│ [WoowTech Logo]              [≡ 漢堡選單]   │
├─────────────────────────────────────────────┤
│                                             │
│              WebView 區域                    │
│           (Odoo 手機版網頁)                  │
│                                             │
│                                             │
└─────────────────────────────────────────────┘
```

**工具欄元素**:
| 元素 | 位置 | 動作 |
|------|------|------|
| WoowTech Logo | 左側 | 顯示品牌識別 |
| 漢堡選單按鈕 | 右側 | 點擊進入 Configuration 頁面 |

#### 4.3.2 WebView 區域

- 載入 Odoo 手機版網頁 (responsive web view)
- 共享 session Cookie 以維持登入狀態
- 支援 JavaScript
- 處理 file upload/download
- 支援返回鍵導航
- 僅允許 HTTPS 連線

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
│  │   │ [已儲存的帳號列表]           │   │   │
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
| Theme Color | Color Picker | 自訂主題顏色（預設 #6183FC） |
| Reduce Motion | Toggle | 減少動畫效果 |

**Security (安全性)** - 可選功能:
| 設定項目 | 類型 | 說明 |
|---------|------|------|
| App Lock | Toggle | 啟用應用程式鎖定 |
| Biometric Unlock | Toggle | 啟用生物辨識解鎖（指紋/臉部） |
| PIN Code | Button | 設定 PIN 碼備用解鎖 |

**Language & Region (語言與地區)**:
| 設定項目 | 類型 | 選項 |
|---------|------|------|
| Language | Picker | English, 繁體中文 |
| Timezone | Picker | 本地時區（從系統獲取） |

**Data & Storage (資料與儲存)**:
| 設定項目 | 類型 | 說明 |
|---------|------|------|
| Clear Cache | Button | 清除暫存資料 (顯示目前大小) |

**Help & Support (幫助與支援)**:
| 設定項目 | 類型 | 連結 |
|---------|------|------|
| Odoo Help Center | Link | https://www.odoo.com/help |
| Odoo Community Forum | Link | https://www.odoo.com/forum |

**About (關於)**:
| 設定項目 | 類型 | 值 |
|---------|------|---|
| Visit Website | Link | https://aiot.woowtech.io |
| Contact Us | Link | mailto:woowtech@designsmart.com.tw |
| App Version | Text | 1.0.0 |
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

#### 4.5.3 離線功能 | Offline Features

- 快取已登入的帳號資訊（URL、資料庫、用戶名稱）
- 無網路時顯示「無法連線」提示
- 網路恢復後自動重新載入 WebView

---

## 5. 使用者介面設計 | UI/UX Design

### 5.1 設計規範 | Design Guidelines

**色彩系統**:
| 用途 | 顏色 | Hex | 說明 |
|------|------|-----|------|
| Primary | WoowTech Blue | #6183FC | 主題色（可自訂） |
| Primary Dark | Dark Blue | #4A6AE0 | 深色變體 |
| Background | Light Gray | #F5F5F5 | 背景色 |
| Surface | White | #FFFFFF | 卡片/表面色 |
| Text Primary | Dark Gray | #212121 | 主要文字 |
| Text Secondary | Medium Gray | #757575 | 次要文字 |
| Error | Red | #D32F2F | 錯誤提示 |
| Success | Green | #388E3C | 成功提示 |

**動態主題色**:
- 用戶可在設定頁面選擇任意顏色作為主題色
- 使用 Material You 動態主題系統
- 主題色會應用於：工具欄、按鈕、連結、圖示高亮等

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
    ├── App Lock Enabled?
    │   ├── Yes → Biometric/PIN Screen → Continue
    │   └── No → Continue
    │
    ├── Has Active Session?
    │   ├── Yes → Main Screen (WebView)
    │   └── No → Login Screen
    │
Main Screen
    │
    ├── Top Toolbar
    │   ├── Logo (Left)
    │   └── Hamburger Menu (Right) → Configuration Screen
    │
    └── WebView (Odoo Mobile Web)

Configuration Screen
    │
    ├── Profile Card → Profile Details
    ├── Settings → Settings Screen
    │   ├── Appearance (Theme Color Picker)
    │   ├── Security (Biometric/PIN)
    │   ├── Language & Region
    │   ├── Data & Storage
    │   ├── Help & Support
    │   └── About
    ├── Switch Accounts → Account List
    │   └── Add Account → Login Screen
    └── Logout → Login Screen
```

---

## 6. 安全性需求 | Security Requirements

### 6.1 資料安全 | Data Security

| 項目 | 實作方式 |
|------|---------|
| 密碼儲存 | 使用 Android Keystore 加密 |
| Session 儲存 | EncryptedSharedPreferences |
| 網路傳輸 | **僅 HTTPS**（強制安全連線） |
| Certificate Pinning | 可選配置 |

### 6.2 認證安全 | Authentication Security

| 項目 | 實作方式 |
|------|---------|
| 生物辨識 | Android Biometric API（可選啟用） |
| PIN 碼 | 4-6 位數 PIN 碼備用解鎖（可選啟用） |
| Session 過期 | 自動檢測並重新導向登入 |
| 多次失敗鎖定 | 5 次失敗後延遲 30 秒 |

### 6.3 WebView 安全 | WebView Security

| 項目 | 設定 |
|------|------|
| JavaScript | 啟用（必須） |
| Mixed Content | **禁止**（僅 HTTPS） |
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
│   │   │   ├── java/io/woowtech/odoo/
│   │   │   │   ├── WoowOdooApp.kt
│   │   │   │   ├── di/
│   │   │   │   │   └── AppModule.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── api/
│   │   │   │   │   │   ├── OdooApiService.kt
│   │   │   │   │   │   └── OdooJsonRpcClient.kt
│   │   │   │   │   ├── repository/
│   │   │   │   │   │   ├── AccountRepository.kt
│   │   │   │   │   │   ├── SettingsRepository.kt
│   │   │   │   │   │   └── UserRepository.kt
│   │   │   │   │   └── local/
│   │   │   │   │       ├── AccountDao.kt
│   │   │   │   │       ├── AppDatabase.kt
│   │   │   │   │       └── EncryptedPrefs.kt
│   │   │   │   ├── domain/
│   │   │   │   │   ├── model/
│   │   │   │   │   │   ├── OdooAccount.kt
│   │   │   │   │   │   ├── UserProfile.kt
│   │   │   │   │   │   └── AppSettings.kt
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── AuthenticateUseCase.kt
│   │   │   │   │       ├── GetProfileUseCase.kt
│   │   │   │   │       └── BiometricAuthUseCase.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── theme/
│   │   │   │   │   │   ├── Color.kt
│   │   │   │   │   │   ├── Theme.kt
│   │   │   │   │   │   ├── DynamicTheme.kt
│   │   │   │   │   │   └── Type.kt
│   │   │   │   │   ├── navigation/
│   │   │   │   │   │   └── NavGraph.kt
│   │   │   │   │   ├── auth/
│   │   │   │   │   │   ├── BiometricScreen.kt
│   │   │   │   │   │   └── PinScreen.kt
│   │   │   │   │   ├── login/
│   │   │   │   │   │   ├── LoginScreen.kt
│   │   │   │   │   │   └── LoginViewModel.kt
│   │   │   │   │   ├── main/
│   │   │   │   │   │   ├── MainScreen.kt
│   │   │   │   │   │   ├── MainViewModel.kt
│   │   │   │   │   │   ├── TopToolbar.kt
│   │   │   │   │   │   └── OdooWebView.kt
│   │   │   │   │   ├── config/
│   │   │   │   │   │   ├── ConfigScreen.kt
│   │   │   │   │   │   ├── ProfileScreen.kt
│   │   │   │   │   │   ├── SettingsScreen.kt
│   │   │   │   │   │   └── ThemeColorPicker.kt
│   │   │   │   │   └── components/
│   │   │   │   │       ├── AccountCard.kt
│   │   │   │   │       ├── SettingsItem.kt
│   │   │   │   │       └── ColorPickerDialog.kt
│   │   │   │   └── util/
│   │   │   │       ├── SecurityUtils.kt
│   │   │   │       ├── NetworkUtils.kt
│   │   │   │       └── BiometricUtils.kt
│   │   │   ├── res/
│   │   │   │   ├── drawable/
│   │   │   │   │   ├── ic_launcher.xml
│   │   │   │   │   └── woowtech_logo.png
│   │   │   │   ├── mipmap-xxxhdpi/
│   │   │   │   │   └── ic_launcher.png
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   └── colors.xml
│   │   │   │   ├── values-zh-rTW/
│   │   │   │   │   └── strings.xml
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

### Phase 1: 核心功能 (MVP)

| 功能 | 優先級 | 狀態 |
|------|--------|------|
| 登入畫面 UI | P0 | Planned |
| JSON-RPC 認證 | P0 | Planned |
| 頂部固定工具欄 | P0 | Planned |
| WebView 整合 | P0 | Planned |
| Session 管理 | P0 | Planned |
| 基本 Configuration 頁面 | P0 | Planned |
| 登出功能 | P0 | Planned |

### Phase 2: 帳號管理與設定

| 功能 | 優先級 | 狀態 |
|------|--------|------|
| 多帳號儲存 | P1 | Planned |
| 帳號切換 | P1 | Planned |
| 帳號移除 | P1 | Planned |
| 個人資料編輯 | P1 | Planned |
| 主題色自訂（Color Picker）| P1 | Planned |
| 多語言支援（繁中/英文）| P1 | Planned |

### Phase 3: 安全功能

| 功能 | 優先級 | 狀態 |
|------|--------|------|
| 生物辨識鎖定（可選）| P2 | Planned |
| PIN 碼鎖定（可選）| P2 | Planned |
| 快取管理 | P2 | Planned |

### Phase 4: 優化與發佈

| 功能 | 優先級 | 狀態 |
|------|--------|------|
| 效能優化 | P2 | Planned |
| 錯誤處理完善 | P2 | Planned |
| APK 簽章 | P2 | Planned |
| 內部分發準備 | P2 | Planned |

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
- 測試伺服器: https://woowtechaicoder-odootest.woowtech.io/

### 10.3 UI 測試 | UI Tests

- 登入流程 E2E 測試
- 導航測試
- 主題色切換測試
- 多語言切換測試

### 10.4 相容性測試 | Compatibility Tests

| 測試項目 | 範圍 |
|---------|------|
| Android 版本 | 10 - 14 (API 29-34) |
| 螢幕尺寸 | 5" - 10" |
| Odoo 版本 | 18 Community |

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
| theme_color | Theme Color | 主題顏色 |
| security | Security | 安全性 |
| app_lock | App Lock | 應用程式鎖定 |
| biometric_unlock | Biometric Unlock | 生物辨識解鎖 |
| pin_code | PIN Code | PIN 碼 |
| language_region | Language & Region | 語言與地區 |
| data_storage | Data & Storage | 資料與儲存 |
| clear_cache | Clear Cache | 清除快取 |
| help_support | Help & Support | 幫助與支援 |
| about | About | 關於 |
| visit_website | Visit Website | 造訪網站 |
| contact_us | Contact Us | 聯絡我們 |
| error_network | Unable to connect to server | 無法連接到伺服器 |
| error_invalid_url | Invalid server URL | 伺服器網址無效 |
| error_database | Database not found | 找不到資料庫 |
| error_auth | Invalid username or password | 帳號或密碼錯誤 |
| error_session | Session expired, please login again | Session 已過期，請重新登入 |
| error_https | Secure connection required (HTTPS) | 需要安全連線 (HTTPS) |

### C. 品牌資產 | Brand Assets

| 資產 | 說明 |
|------|------|
| Logo | WoowTech 藍紫色圓形 Logo |
| 主題色 | #6183FC（預設，可自訂） |
| 公司網站 | https://aiot.woowtech.io |
| 聯絡 Email | woowtech@designsmart.com.tw |

### D. 參考連結 | Reference Links

- [Odoo 18 External API Documentation](https://www.odoo.com/documentation/18.0/developer/reference/external_api.html)
- [Android Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Material Design 3](https://m3.material.io/)
- [Android Biometric API](https://developer.android.com/training/sign-in/biometric-auth)
- [Android Security Best Practices](https://developer.android.com/topic/security/best-practices)

---

**文件結束 | End of Document**

© 2026 WoowTech. All rights reserved.
