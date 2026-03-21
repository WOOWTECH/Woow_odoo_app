# WoowTech Odoo Mobile App - Architecture Overview

> **Scanned commit:** `d65cdd9bbfe3eaaee5bc1b6ff74e33a38b209b71`
> **Version:** 1.0.20 (versionCode: 20)
> **Date:** 2026-03-22
> **Package:** `io.woowtech.odoo`

---

## Table of Contents

1. [Project Summary](#1-project-summary)
2. [Technology Stack](#2-technology-stack)
3. [High-Level Architecture (Block Diagram)](#3-high-level-architecture)
4. [Module & Package Structure](#4-module--package-structure)
5. [Clean Architecture Layers](#5-clean-architecture-layers)
6. [Navigation Flow](#6-navigation-flow)
7. [Authentication System](#7-authentication-system)
8. [Data Layer & Odoo API](#8-data-layer--odoo-api)
9. [WebView Integration](#9-webview-integration)
10. [Security Architecture](#10-security-architecture)
11. [Theme & Localization](#11-theme--localization)
12. [Dependency Injection](#12-dependency-injection)
13. [Key Data Flows](#13-key-data-flows)
14. [Database Schema](#14-database-schema)
15. [CI/CD Pipeline](#15-cicd-pipeline)
16. [Project Statistics](#16-project-statistics)
17. [Directory Structure Reference](#17-directory-structure-reference)

---

## 1. Project Summary

**WoowTech Odoo** is a native Android app that provides a mobile interface to Odoo ERP servers. The app wraps the Odoo web interface in a WebView while adding native Android features:

- Multi-account management (connect to multiple Odoo servers)
- Biometric & PIN authentication
- Session management with encrypted credential storage
- Customizable theme (colors, dark mode)
- Bilingual support (English + Traditional Chinese)

```mermaid
graph LR
    subgraph "Android Device"
        APP["WoowTech Odoo App<br/>(Kotlin + Compose)"]
    end

    subgraph "Odoo Server"
        ODOO["Odoo ERP<br/>(Web + JSON-RPC API)"]
    end

    APP -- "JSON-RPC 2.0<br/>(/web/session/authenticate)" --> ODOO
    APP -- "WebView<br/>(HTTPS + Cookies)" --> ODOO

    style APP fill:#6183FC,color:#fff
    style ODOO fill:#714B67,color:#fff
```

---

## 2. Technology Stack

| Category | Technology | Version |
|----------|-----------|---------|
| **Language** | Kotlin | 2.0.21 |
| **UI Framework** | Jetpack Compose + Material 3 | BOM 2024.02.00 |
| **Architecture** | MVVM + Clean Architecture | - |
| **DI** | Hilt | 2.50 |
| **Database** | Room | 2.6.1 |
| **Network** | OkHttp + Gson | 4.12.0 / 2.10.1 |
| **Security** | EncryptedSharedPreferences, Android KeyStore | AES-256-GCM |
| **Auth** | BiometricPrompt | 1.2.0-alpha05 |
| **Navigation** | Navigation Compose | Latest |
| **Build** | AGP + Gradle | 8.3.0 / 8.6 |
| **CI/CD** | GitHub Actions | - |
| **Min SDK** | Android 10 (API 29) | - |
| **Target SDK** | Android 14 (API 34) | - |
| **Java** | 17 | - |

---

## 3. High-Level Architecture

### System Block Diagram

```mermaid
graph TB
    subgraph "WoowTech Odoo Android App"
        direction TB

        subgraph "Presentation Layer (UI)"
            MA["MainActivity<br/>(Single Activity)"]
            NAV["NavGraph<br/>(Navigation Compose)"]

            subgraph "Screens"
                LOGIN["LoginScreen<br/>(2-step form)"]
                BIO["BiometricScreen"]
                PIN["PinScreen"]
                MAIN["MainScreen<br/>(OdooWebView)"]
                CONFIG["ConfigScreen<br/>(Account Mgmt)"]
                SETTINGS["SettingsScreen<br/>(Preferences)"]
            end

            subgraph "ViewModels"
                LVM["LoginViewModel"]
                AVM["AuthViewModel"]
                MVM["MainViewModel"]
                CVM["ConfigViewModel"]
                SVM["SettingsViewModel"]
            end

            THEME["ThemeManager<br/>(Dynamic Colors)"]
        end

        subgraph "Domain Layer"
            ACC_MODEL["OdooAccount"]
            AUTH_RESULT["AuthResult<br/>(Sealed Class)"]
            APP_SETTINGS["AppSettings"]
        end

        subgraph "Data Layer"
            ACC_REPO["AccountRepository"]
            SET_REPO["SettingsRepository"]

            subgraph "Remote"
                RPC["OdooJsonRpcClient<br/>(OkHttp + JSON-RPC)"]
            end

            subgraph "Local Storage"
                ROOM["Room Database<br/>(AppDatabase v1)"]
                EPREFS["EncryptedPrefs<br/>(AES-256-GCM)"]
                ENC["EncryptionHelper<br/>(Android KeyStore)"]
            end
        end

        subgraph "DI"
            HILT["Hilt AppModule<br/>(Singleton Scope)"]
        end
    end

    subgraph "Odoo Server"
        RPC_API["JSON-RPC API<br/>(/web/session/authenticate)"]
        WEB_UI["Odoo Web UI<br/>(PWA / OWL Framework)"]
    end

    MA --> NAV
    NAV --> LOGIN & BIO & PIN & MAIN & CONFIG & SETTINGS

    LOGIN --> LVM
    BIO --> AVM
    PIN --> AVM
    MAIN --> MVM
    CONFIG --> CVM
    SETTINGS --> SVM

    LVM --> ACC_REPO
    AVM --> ACC_REPO & SET_REPO
    MVM --> ACC_REPO
    CVM --> ACC_REPO
    SVM --> SET_REPO

    ACC_REPO --> RPC & ROOM & EPREFS
    SET_REPO --> EPREFS

    RPC --> RPC_API
    MAIN -. "WebView + Cookies" .-> WEB_UI

    HILT -. "provides" .-> ACC_REPO & SET_REPO & RPC & ROOM & EPREFS

    style MA fill:#6183FC,color:#fff
    style MAIN fill:#6183FC,color:#fff
    style RPC fill:#4A6BD9,color:#fff
    style ROOM fill:#4A6BD9,color:#fff
```

---

## 4. Module & Package Structure

This is a **single-module** Android project (`:app`).

```mermaid
graph TD
    subgraph ":app module"
        ROOT["io.woowtech.odoo"]

        ROOT --> DATA["data/"]
        ROOT --> DOMAIN["domain/"]
        ROOT --> UI["ui/"]
        ROOT --> DI["di/"]

        DATA --> API["api/<br/>OdooJsonRpcClient"]
        DATA --> LOCAL["local/<br/>AppDatabase, AccountDao,<br/>EncryptedPrefs"]
        DATA --> REPO["repository/<br/>AccountRepository,<br/>SettingsRepository"]
        DATA --> SEC["security/<br/>EncryptionHelper"]

        DOMAIN --> MODEL["model/<br/>OdooAccount, AuthResult,<br/>AppSettings, ThemeMode,<br/>AppLanguage"]

        UI --> AUTH_UI["auth/<br/>AuthViewModel,<br/>BiometricScreen, PinScreen"]
        UI --> LOGIN_UI["login/<br/>LoginViewModel,<br/>LoginScreen"]
        UI --> MAIN_UI["main/<br/>MainViewModel,<br/>MainScreen (WebView)"]
        UI --> CONFIG_UI["config/<br/>ConfigViewModel, ConfigScreen,<br/>SettingsViewModel, SettingsScreen"]
        UI --> NAV_UI["navigation/<br/>NavGraph"]
        UI --> THEME_UI["theme/<br/>Color, Theme, ThemeManager, Type"]

        DI --> APPMOD["AppModule (Hilt)"]
    end

    style ROOT fill:#6183FC,color:#fff
```

---

## 5. Clean Architecture Layers

### Layer Diagram

```mermaid
graph TB
    subgraph "Presentation Layer"
        direction LR
        SCREENS["Compose Screens<br/>(LoginScreen, MainScreen,<br/>ConfigScreen, SettingsScreen,<br/>BiometricScreen, PinScreen)"]
        VMS["ViewModels<br/>(LoginVM, AuthVM, MainVM,<br/>ConfigVM, SettingsVM)"]
        SCREENS --> VMS
    end

    subgraph "Domain Layer"
        direction LR
        MODELS["Models<br/>(OdooAccount, AuthResult,<br/>AppSettings, ThemeMode,<br/>AppLanguage)"]
    end

    subgraph "Data Layer"
        direction LR
        REPOS["Repositories<br/>(AccountRepository,<br/>SettingsRepository)"]
        REMOTE["Remote<br/>(OdooJsonRpcClient)"]
        DB["Local<br/>(Room DB, EncryptedPrefs,<br/>EncryptionHelper)"]
        REPOS --> REMOTE & DB
    end

    VMS --> MODELS
    VMS --> REPOS

    style SCREENS fill:#81C784,color:#000
    style VMS fill:#6183FC,color:#fff
    style MODELS fill:#FFB74D,color:#000
    style REPOS fill:#4A6BD9,color:#fff
    style REMOTE fill:#EF5350,color:#fff
    style DB fill:#AB47BC,color:#fff
```

### MVVM Pattern

```mermaid
classDiagram
    class Screen {
        <<Composable>>
        observes StateFlow
        calls ViewModel methods
    }

    class ViewModel {
        -_uiState: MutableStateFlow
        +uiState: StateFlow
        +onAction()
        viewModelScope.launch
    }

    class Repository {
        <<Singleton>>
        suspend functions
        Flow emissions
    }

    class DataSource {
        <<Interface>>
        Room DAO / OkHttp / Prefs
    }

    Screen --> ViewModel : collectAsState
    ViewModel --> Repository : suspend calls
    Repository --> DataSource : IO operations
```

---

## 6. Navigation Flow

### Screen Navigation Graph

```mermaid
stateDiagram-v2
    [*] --> Splash : App Launch

    Splash --> Login : No active account
    Splash --> Auth : Has account + App Lock ON
    Splash --> Main : Has account + App Lock OFF

    state Auth {
        [*] --> BiometricCheck
        BiometricCheck --> BiometricScreen : Biometric available
        BiometricCheck --> PinScreen : No biometric

        BiometricScreen --> Main : Success
        BiometricScreen --> PinScreen : Failed / Cancel

        PinScreen --> Main : Correct PIN
        PinScreen --> PinScreen : Wrong PIN (max 5 attempts)
        PinScreen --> PinLockout : 5 failures
        PinLockout --> PinScreen : 30s elapsed
    }

    Login --> Main : Login Success
    Main --> Config : Menu button
    Config --> Settings : Settings item
    Config --> Login : Add Account
    Config --> Main : Switch Account
    Config --> Login : Logout (last account)
    Settings --> Config : Back

    state Main {
        WebView : Odoo WebView
        WebView --> WebView : Navigate within Odoo
        WebView --> Login : Session expired
    }
```

### Navigation Routes

```mermaid
graph LR
    SPLASH["Splash<br/>(splash)"] --> LOGIN["Login<br/>(login)"]
    SPLASH --> AUTH["Auth<br/>(auth)"]
    SPLASH --> MAIN["Main<br/>(main)"]

    AUTH --> PIN["Pin<br/>(pin)"]
    LOGIN --> MAIN
    AUTH --> MAIN
    PIN --> MAIN

    MAIN --> CONFIG["Config<br/>(config)"]
    CONFIG --> SETTINGS["Settings<br/>(settings)"]
    CONFIG --> LOGIN
```

---

## 7. Authentication System

### Authentication Flow (UML Sequence)

```mermaid
sequenceDiagram
    participant User
    participant LoginScreen
    participant LoginViewModel
    participant AccountRepo as AccountRepository
    participant RPC as OdooJsonRpcClient
    participant Odoo as Odoo Server
    participant Room as Room DB
    participant Prefs as EncryptedPrefs

    User->>LoginScreen: Enter Server URL + Database
    LoginScreen->>LoginViewModel: goToNextStep()
    LoginViewModel->>LoginViewModel: Validate URL format

    User->>LoginScreen: Enter Username + Password
    LoginScreen->>LoginViewModel: login()
    LoginViewModel->>AccountRepo: authenticate(url, db, user, pass)

    AccountRepo->>RPC: authenticate(url, db, user, pass)
    RPC->>RPC: Validate HTTPS
    RPC->>Odoo: POST /web/session/authenticate<br/>(JSON-RPC 2.0)

    alt Success
        Odoo-->>RPC: {uid, session_id, name}
        RPC-->>AccountRepo: AuthResult.Success
        AccountRepo->>Room: deactivateAllAccounts()
        AccountRepo->>Prefs: savePassword(accountId, encrypted)
        AccountRepo->>Room: insertAccount(account, isActive=true)
        AccountRepo-->>LoginViewModel: Success
        LoginViewModel-->>LoginScreen: Navigate to Main
    else Error
        Odoo-->>RPC: {error: ...}
        RPC-->>AccountRepo: AuthResult.Error(type)
        AccountRepo-->>LoginViewModel: Error
        LoginViewModel-->>LoginScreen: Show error message
    end
```

### App Lock Authentication

```mermaid
sequenceDiagram
    participant User
    participant NavGraph
    participant AuthVM as AuthViewModel
    participant BiometricScreen
    participant PinScreen
    participant SettingsRepo

    NavGraph->>AuthVM: Check requiresAuth
    AuthVM-->>NavGraph: appLockEnabled = true

    alt Biometric Available
        NavGraph->>BiometricScreen: Navigate
        BiometricScreen->>BiometricScreen: BiometricPrompt.authenticate()
        alt Success
            BiometricScreen->>AuthVM: setAuthenticated(true)
            AuthVM-->>NavGraph: Navigate to Main
        else Failed
            BiometricScreen->>NavGraph: Navigate to PinScreen
        end
    end

    alt PIN Required
        NavGraph->>PinScreen: Navigate
        User->>PinScreen: Enter 4-6 digit PIN
        PinScreen->>SettingsRepo: verifyPin(pin)
        SettingsRepo->>SettingsRepo: SHA-256 hash comparison

        alt Correct
            SettingsRepo-->>PinScreen: true
            PinScreen->>AuthVM: setAuthenticated(true)
        else Wrong (< 5 attempts)
            SettingsRepo-->>PinScreen: false
            PinScreen->>PinScreen: Shake animation + show remaining
        else Wrong (5th attempt)
            SettingsRepo->>SettingsRepo: Set lockout 30s
            PinScreen->>PinScreen: Show lockout timer
        end
    end
```

### AuthResult Type Hierarchy

```mermaid
classDiagram
    class AuthResult {
        <<sealed class>>
    }

    class Success {
        +userId: Int
        +sessionId: String
        +username: String
        +displayName: String
    }

    class Error {
        +message: String
        +type: ErrorType
    }

    class ErrorType {
        <<enum>>
        NETWORK_ERROR
        INVALID_URL
        DATABASE_NOT_FOUND
        INVALID_CREDENTIALS
        SESSION_EXPIRED
        HTTPS_REQUIRED
        SERVER_ERROR
        UNKNOWN
    }

    AuthResult <|-- Success
    AuthResult <|-- Error
    Error --> ErrorType
```

---

## 8. Data Layer & Odoo API

### JSON-RPC Communication

```mermaid
sequenceDiagram
    participant App as OdooJsonRpcClient
    participant OkHttp as OkHttp Client
    participant Odoo as Odoo Server

    App->>App: Build JsonRpcRequest<br/>{jsonrpc: "2.0", method: "call",<br/>params: {db, login, password}}

    App->>OkHttp: POST request<br/>Content-Type: application/json

    Note over OkHttp: Configured with:<br/>- 30s timeouts<br/>- CookieJar (session)<br/>- HTTP Logging (debug)

    OkHttp->>Odoo: HTTPS POST /web/session/authenticate

    Odoo-->>OkHttp: JsonRpcResponse<br/>{result: {uid, session_id, ...}}

    OkHttp-->>App: Response + Set-Cookie: session_id

    App->>App: Store cookies per host<br/>Extract session_id
```

### OdooJsonRpcClient Class

```mermaid
classDiagram
    class OdooJsonRpcClient {
        -client: OkHttpClient
        -cookieStore: Map~String, List~Cookie~~
        +authenticate(url, db, user, pass): AuthResult
        +getSessionCookies(host): List~Cookie~
        +getSessionId(host): String?
        +clearCookies(host)
    }

    class JsonRpcRequest {
        +jsonrpc: String = "2.0"
        +method: String = "call"
        +params: Map
        +id: Int
    }

    class JsonRpcResponse {
        +result: JsonObject?
        +error: JsonRpcError?
    }

    class JsonRpcError {
        +code: Int
        +message: String
        +data: JsonRpcErrorData?
    }

    OdooJsonRpcClient --> JsonRpcRequest : creates
    OdooJsonRpcClient --> JsonRpcResponse : parses
    JsonRpcResponse --> JsonRpcError : may contain
```

### Repository Layer

```mermaid
classDiagram
    class AccountRepository {
        <<Singleton>>
        -accountDao: AccountDao
        -encryptedPrefs: EncryptedPrefs
        -rpcClient: OdooJsonRpcClient
        +allAccounts: Flow~List~OdooAccount~~
        +activeAccount: Flow~OdooAccount?~
        +authenticate(url, db, user, pass): AuthResult
        +switchAccount(accountId): Boolean
        +logout(accountId?)
        +removeAccount(accountId)
        +getSessionId(url): String?
        +getSessionCookies(url): List~Cookie~
        +getAccountCount(): Int
    }

    class SettingsRepository {
        <<Singleton>>
        -encryptedPrefs: EncryptedPrefs
        +settings: StateFlow~AppSettings~
        +updateThemeColor(hex)
        +updateThemeMode(mode)
        +setPin(pin): Boolean
        +removePin()
        +verifyPin(pin): Boolean
        +updateAppLock(enabled)
        +updateBiometric(enabled)
        +updateLanguage(language)
        +getRemainingAttempts(): Int
        +isLockedOut(): Boolean
        +getLockoutRemainingMs(): Long
    }

    AccountRepository --> AccountDao
    AccountRepository --> EncryptedPrefs
    AccountRepository --> OdooJsonRpcClient
    SettingsRepository --> EncryptedPrefs
    SettingsRepository --> ThemeManager
```

---

## 9. WebView Integration

### WebView Architecture

```mermaid
graph TB
    subgraph "MainScreen Composable"
        TOPBAR["TopAppBar<br/>(Menu Icon)"]
        WV["OdooWebView<br/>(AndroidView wrapping WebView)"]
        LOADER["Loading Indicator"]
    end

    subgraph "WebView Configuration"
        JS["JavaScript: Enabled"]
        DOM["DOM Storage: Enabled"]
        COOKIES["CookieManager<br/>(Session Sync)"]
        UA["User-Agent:<br/>Chrome Mobile Standard"]
        ZOOM["Zoom: Enabled"]
        MIXED["Mixed Content: NEVER"]
    end

    subgraph "WebView Handlers"
        CLIENT["WebViewClient"]
        CHROME["WebChromeClient"]
    end

    subgraph "Features"
        UPLOAD["File Upload<br/>(Camera + Gallery)"]
        SESSION["Session Expiry<br/>Detection"]
        OWL_FIX["OWL Framework<br/>JS Injection"]
        POPUP["Popup Window<br/>Handling"]
    end

    WV --> JS & DOM & COOKIES & UA & ZOOM & MIXED
    WV --> CLIENT & CHROME

    CLIENT --> SESSION
    CLIENT --> OWL_FIX
    CHROME --> UPLOAD
    CHROME --> POPUP

    style WV fill:#6183FC,color:#fff
```

### WebView Session Sync Flow

```mermaid
sequenceDiagram
    participant MVM as MainViewModel
    participant AccRepo as AccountRepository
    participant RPC as OdooJsonRpcClient
    participant WV as WebView
    participant CM as CookieManager

    MVM->>AccRepo: getActiveAccountOnce()
    AccRepo-->>MVM: OdooAccount (url, db, user)

    MVM->>AccRepo: getSessionId(serverUrl)
    AccRepo->>RPC: getSessionId(host)
    RPC-->>AccRepo: session_id cookie
    AccRepo-->>MVM: sessionId

    MVM-->>WV: Load URL: {serverUrl}/web

    Note over WV,CM: Before WebView loads:
    WV->>CM: setCookie(url, "session_id={id}")
    CM->>CM: Flush cookies

    WV->>WV: loadUrl(serverUrl + "/web")

    Note over WV: Post-load JS injection:
    WV->>WV: evaluateJavascript(<br/>"document.body.style.height='100vh';<br/>window.dispatchEvent(new Event('resize'))")

    alt Session Expired
        WV->>WV: Redirect detected → /web/login
        WV-->>MVM: Trigger re-auth flow
    end
```

---

## 10. Security Architecture

### Security Layers

```mermaid
graph TB
    subgraph "Transport Security"
        HTTPS["HTTPS Enforced<br/>(network_security_config)"]
        MIXED_BLOCK["Mixed Content: NEVER_ALLOW"]
    end

    subgraph "App-Level Security"
        BIO["Biometric Auth<br/>(Face/Fingerprint)"]
        PIN_AUTH["PIN Auth<br/>(SHA-256 Hash)"]
        LOCKOUT["PIN Lockout<br/>(5 attempts → 30s)"]
    end

    subgraph "Data Encryption"
        ESP["EncryptedSharedPreferences<br/>Keys: AES-256-SIV<br/>Values: AES-256-GCM"]
        KEYSTORE["Android KeyStore<br/>(AES-256-GCM<br/>12-byte IV)"]
    end

    subgraph "Storage"
        PWD["Passwords<br/>(AES encrypted)"]
        TOKENS["Session Cookies<br/>(In-memory CookieJar)"]
        SETTINGS["Security Settings<br/>(Encrypted prefs)"]
        ROOM_DB["Room Database<br/>(Account metadata)"]
    end

    HTTPS --> BIO
    BIO --> PIN_AUTH
    PIN_AUTH --> LOCKOUT
    ESP --> PWD & SETTINGS
    KEYSTORE --> ESP
    TOKENS -. "volatile" .-> ROOM_DB

    style HTTPS fill:#4CAF50,color:#fff
    style ESP fill:#FF9800,color:#fff
    style KEYSTORE fill:#F44336,color:#fff
```

### What Gets Encrypted

| Data | Storage | Encryption |
|------|---------|------------|
| Passwords | EncryptedPrefs (`pwd_{id}`) | AES-256-GCM via EncryptionHelper |
| PIN Hash | EncryptedPrefs (`pin_hash`) | SHA-256 (one-way) |
| Security settings | EncryptedPrefs | AES-256-GCM (EncryptedSharedPreferences) |
| Theme preferences | EncryptedPrefs | AES-256-GCM (EncryptedSharedPreferences) |
| Account metadata | Room DB | Not encrypted (non-sensitive) |
| Session cookies | In-memory Map | Volatile (lost on process death) |

---

## 11. Theme & Localization

### Theme System

```mermaid
graph LR
    subgraph "ThemeManager (Singleton)"
        PC["primaryColor<br/>StateFlow&lt;Color&gt;"]
        TM["themeMode<br/>StateFlow&lt;ThemeMode&gt;"]
    end

    subgraph "WoowTechOdooTheme"
        LIGHT["createLightColorScheme()"]
        DARK["createDarkColorScheme()"]
        DETECT["System Dark Mode<br/>Detection"]
    end

    subgraph "Storage"
        PREFS["EncryptedPrefs<br/>theme_color, theme_mode"]
    end

    subgraph "UI"
        M3["Material 3<br/>Color Scheme"]
        STATUS["Status Bar<br/>Color Sync"]
    end

    PC --> LIGHT & DARK
    TM --> DETECT
    DETECT --> LIGHT
    DETECT --> DARK
    LIGHT --> M3
    DARK --> M3
    M3 --> STATUS
    PREFS <--> PC & TM
```

### Available Theme Colors

| Color | Hex | Name |
|-------|-----|------|
| WoowTech Blue | `#6183FC` | Default |
| Red | `#E53935` | - |
| Pink | `#D81B60` | - |
| Purple | `#8E24AA` | - |
| Deep Purple | `#5E35B1` | - |
| Indigo | `#3949AB` | - |
| Teal | `#00897B` | - |
| Green | `#43A047` | - |
| Orange | `#FB8C00` | - |
| Brown | `#6D4C41` | - |

### Localization

| Language | Code | Resource Directory |
|----------|------|-------------------|
| English | `en` | `values/strings.xml` (171+ strings) |
| Traditional Chinese | `zh-TW` | `values-zh-rTW/strings.xml` |
| System Default | - | Follows device locale |

---

## 12. Dependency Injection

### Hilt Module

```mermaid
graph TB
    subgraph "AppModule (@Module @InstallIn SingletonComponent)"
        direction TB
        DB_PROV["@Provides @Singleton<br/>AppDatabase"]
        DAO_PROV["@Provides<br/>AccountDao"]
        PREFS_PROV["@Provides @Singleton<br/>EncryptedPrefs"]
        RPC_PROV["@Provides @Singleton<br/>OdooJsonRpcClient"]
        AREPO_PROV["@Provides @Singleton<br/>AccountRepository"]
        SREPO_PROV["@Provides @Singleton<br/>SettingsRepository"]
    end

    subgraph "Injection Targets"
        LVM["LoginViewModel"]
        AVM["AuthViewModel"]
        MVM["MainViewModel"]
        CVM["ConfigViewModel"]
        SVM["SettingsViewModel"]
    end

    DB_PROV --> DAO_PROV
    DAO_PROV --> AREPO_PROV
    PREFS_PROV --> AREPO_PROV & SREPO_PROV
    RPC_PROV --> AREPO_PROV

    AREPO_PROV --> LVM & AVM & MVM & CVM
    SREPO_PROV --> AVM & SVM

    style DB_PROV fill:#6183FC,color:#fff
    style AREPO_PROV fill:#4A6BD9,color:#fff
```

### Dependency Graph

```mermaid
classDiagram
    class WoowOdooApp {
        <<@HiltAndroidApp>>
    }

    class MainActivity {
        <<@AndroidEntryPoint>>
    }

    class AppModule {
        <<@Module>>
        +provideDatabase(): AppDatabase
        +provideAccountDao(): AccountDao
        +provideEncryptedPrefs(): EncryptedPrefs
        +provideOdooJsonRpcClient(): OdooJsonRpcClient
        +provideAccountRepository(): AccountRepository
        +provideSettingsRepository(): SettingsRepository
    }

    class LoginViewModel {
        <<@HiltViewModel>>
        -accountRepository: AccountRepository
    }

    class AuthViewModel {
        <<@HiltViewModel>>
        -accountRepository: AccountRepository
        -settingsRepository: SettingsRepository
    }

    class MainViewModel {
        <<@HiltViewModel>>
        -accountRepository: AccountRepository
        -encryptedPrefs: EncryptedPrefs
    }

    WoowOdooApp ..> AppModule : configures
    MainActivity ..> LoginViewModel : injects
    MainActivity ..> AuthViewModel : injects
    AppModule --> AccountRepository : provides
    AppModule --> SettingsRepository : provides
```

---

## 13. Key Data Flows

### Login Flow

```mermaid
flowchart TD
    A["User opens app"] --> B{"Active account<br/>exists?"}
    B -- No --> C["LoginScreen"]
    B -- Yes --> D{"App Lock<br/>enabled?"}

    C --> E["Step 1: Server URL + Database"]
    E --> F["Step 2: Username + Password"]
    F --> G["LoginViewModel.login()"]
    G --> H["AccountRepository.authenticate()"]
    H --> I["OdooJsonRpcClient → JSON-RPC"]
    I --> J{"Success?"}

    J -- Yes --> K["Save account to Room DB"]
    K --> L["Save encrypted password"]
    L --> M["Navigate to MainScreen"]

    J -- No --> N["Show error message"]
    N --> F

    D -- Yes --> O["BiometricScreen / PinScreen"]
    O -- Authenticated --> M
    D -- No --> M

    M --> P["WebView loads Odoo<br/>with session cookie"]

    style M fill:#4CAF50,color:#fff
    style N fill:#F44336,color:#fff
```

### Account Switch Flow

```mermaid
sequenceDiagram
    participant User
    participant ConfigScreen
    participant ConfigVM as ConfigViewModel
    participant AccRepo as AccountRepository
    participant RPC as OdooJsonRpcClient
    participant Prefs as EncryptedPrefs
    participant Room as Room DB

    User->>ConfigScreen: Tap different account
    ConfigScreen->>ConfigVM: switchAccount(accountId)
    ConfigVM->>AccRepo: switchAccount(accountId)

    AccRepo->>Room: getAccountById(accountId)
    Room-->>AccRepo: OdooAccount

    AccRepo->>Prefs: getPassword(accountId)
    Prefs-->>AccRepo: decrypted password

    AccRepo->>RPC: authenticate(url, db, user, pass)
    RPC-->>AccRepo: AuthResult.Success

    AccRepo->>Room: deactivateAllAccounts()
    AccRepo->>Room: activateAccount(accountId)
    AccRepo->>Room: updateLastLogin(accountId)

    AccRepo-->>ConfigVM: true
    ConfigVM-->>ConfigScreen: Account switched

    Note over ConfigScreen: NavGraph detects<br/>activeAccount change<br/>→ MainScreen reloads
```

### Theme Change Flow

```mermaid
graph LR
    USER["User picks color<br/>(SettingsScreen)"] --> SVM["SettingsViewModel<br/>.updateThemeColor(hex)"]
    SVM --> SREPO["SettingsRepository<br/>.updateThemeColor()"]
    SREPO --> PREFS["EncryptedPrefs<br/>.updateThemeColor()"]
    SREPO --> TM["ThemeManager<br/>.setPrimaryColorFromHex()"]
    TM --> FLOW["primaryColor<br/>StateFlow emits"]
    FLOW --> THEME["WoowTechOdooTheme<br/>recomposes"]
    THEME --> UI["All UI updates<br/>with new color"]

    style USER fill:#81C784,color:#000
    style UI fill:#6183FC,color:#fff
```

---

## 14. Database Schema

### Entity Relationship Diagram

```mermaid
erDiagram
    ODOO_ACCOUNT {
        string id PK "UUID"
        string serverUrl "Odoo server URL"
        string database "Database name"
        string username "Login username"
        string displayName "User display name"
        string encryptedPassword "NOT stored in Room"
        string avatarBase64 "Base64 avatar"
        int userId "Odoo user ID"
        long lastLogin "Timestamp"
        boolean isActive "Active account flag"
    }

    ENCRYPTED_PREFS {
        string pwd_accountId "AES encrypted password"
        string theme_color "Hex color string"
        string theme_mode "SYSTEM|LIGHT|DARK"
        boolean app_lock_enabled "App lock flag"
        boolean biometric_enabled "Biometric flag"
        boolean pin_enabled "PIN flag"
        string pin_hash "SHA-256 hash"
        int failed_pin_attempts "Attempt counter"
        long pin_lockout_until "Lockout timestamp"
        string language "Language code"
        boolean reduce_motion "Motion preference"
    }

    COOKIE_STORE {
        string host "Server hostname"
        list cookies "Session cookies (in-memory)"
    }

    ODOO_ACCOUNT ||--|| ENCRYPTED_PREFS : "password stored in"
    ODOO_ACCOUNT ||--o| COOKIE_STORE : "session for"
```

### Room Database

- **Name:** `woowtech_odoo_db`
- **Version:** 1
- **Entities:** `OdooAccount`
- **DAO:** `AccountDao`

### AccountDao Operations

| Operation | Method | Type |
|-----------|--------|------|
| Get all accounts | `getAllAccounts()` | `Flow<List<OdooAccount>>` |
| Get active account | `getActiveAccount()` | `Flow<OdooAccount?>` |
| Find duplicate | `findAccount(url, db, user)` | `OdooAccount?` |
| Insert/Replace | `insertAccount(account)` | suspend |
| Delete account | `deleteAccountById(id)` | suspend |
| Activate account | `activateAccount(id)` | suspend |
| Deactivate all | `deactivateAllAccounts()` | suspend |
| Update last login | `updateLastLogin(id, time)` | suspend |
| Count accounts | `getAccountCount()` | `Int` |

---

## 15. CI/CD Pipeline

### GitHub Actions Workflow

```mermaid
graph LR
    subgraph "Triggers"
        PUSH["Push to main"]
        PR["Pull Request to main"]
        MANUAL["Manual Dispatch"]
    end

    subgraph "Build Job (ubuntu-latest)"
        CHECKOUT["Checkout Code<br/>(actions/checkout@v4)"]
        JDK["Setup JDK 17<br/>(temurin)"]
        GRADLE["Setup Gradle"]
        BUILD["./gradlew assembleDebug"]
        UPLOAD["Upload APK Artifact<br/>(90-day retention)"]
        RELEASE["Create GitHub Release<br/>(with APK)"]
    end

    PUSH --> CHECKOUT
    PR --> CHECKOUT
    MANUAL --> CHECKOUT

    CHECKOUT --> JDK --> GRADLE --> BUILD --> UPLOAD --> RELEASE
```

### Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (with ProGuard)
./gradlew assembleRelease
```

---

## 16. Project Statistics

| Metric | Value |
|--------|-------|
| **Kotlin Files** | 23 |
| **Lines of Kotlin** | ~4,491 |
| **Screens** | 6 (Login, Biometric, PIN, Main, Config, Settings) |
| **ViewModels** | 5 |
| **Repositories** | 2 |
| **Data Models** | 5 |
| **Gradle Modules** | 1 (`:app`) |
| **Locales** | 2 (English, Traditional Chinese) |
| **Min SDK** | 29 (Android 10) |
| **Target SDK** | 34 (Android 14) |
| **App Version** | 1.0.20 |
| **Unit Tests** | 0 (framework available, not yet written) |
| **APK Size** | ~63 MB (debug) |

---

## 17. Directory Structure Reference

```
Woow_odoo_app/
├── .github/
│   └── workflows/
│       └── build.yml                     # CI/CD pipeline
├── app/
│   ├── build.gradle.kts                  # App build config
│   ├── proguard-rules.pro                # ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/io/woowtech/odoo/
│       │   ├── WoowOdooApp.kt            # Hilt Application
│       │   ├── data/
│       │   │   ├── api/
│       │   │   │   └── OdooJsonRpcClient.kt    # Odoo JSON-RPC
│       │   │   ├── local/
│       │   │   │   ├── AccountDao.kt           # Room DAO
│       │   │   │   ├── AppDatabase.kt          # Room Database
│       │   │   │   └── EncryptedPrefs.kt       # Encrypted storage
│       │   │   ├── repository/
│       │   │   │   ├── AccountRepository.kt    # Account ops
│       │   │   │   └── SettingsRepository.kt   # Settings ops
│       │   │   └── security/
│       │   │       └── EncryptionHelper.kt     # AES encryption
│       │   ├── di/
│       │   │   └── AppModule.kt                # Hilt DI config
│       │   ├── domain/model/
│       │   │   ├── AppSettings.kt              # Settings model
│       │   │   ├── AuthResult.kt               # Auth sealed class
│       │   │   └── OdooAccount.kt              # Room entity
│       │   └── ui/
│       │       ├── MainActivity.kt             # Single Activity
│       │       ├── auth/
│       │       │   ├── AuthViewModel.kt
│       │       │   ├── BiometricScreen.kt
│       │       │   └── PinScreen.kt
│       │       ├── config/
│       │       │   ├── ConfigScreen.kt
│       │       │   ├── ConfigViewModel.kt
│       │       │   ├── SettingsScreen.kt
│       │       │   └── SettingsViewModel.kt
│       │       ├── login/
│       │       │   ├── LoginScreen.kt
│       │       │   └── LoginViewModel.kt
│       │       ├── main/
│       │       │   ├── MainScreen.kt           # Odoo WebView
│       │       │   └── MainViewModel.kt
│       │       ├── navigation/
│       │       │   └── NavGraph.kt
│       │       └── theme/
│       │           ├── Color.kt
│       │           ├── Theme.kt
│       │           └── Type.kt
│       └── res/
│           ├── values/
│           │   ├── strings.xml                 # English (171+ strings)
│           │   ├── colors.xml
│           │   └── themes.xml
│           ├── values-zh-rTW/
│           │   └── strings.xml                 # Traditional Chinese
│           ├── xml/
│           │   ├── network_security_config.xml
│           │   ├── file_paths.xml
│           │   ├── backup_rules.xml
│           │   └── data_extraction_rules.xml
│           └── drawable/
│               ├── logo_woowtech.xml           # WoowTech logo
│               └── ic_launcher_*.xml           # Launcher icons
├── docs/
│   ├── architecture-overview.md          # THIS FILE
│   └── plans/                            # Implementation PRDs (13 files)
├── releases/                             # Published APKs
├── build.gradle.kts                      # Root build config
├── settings.gradle.kts                   # Module definitions
├── gradle/
│   └── libs.versions.toml                # Dependency catalog
├── gradle.properties
└── README.md
```

---

*This document was auto-generated by analyzing commit `d65cdd9` of the WoowTech Odoo project.*
