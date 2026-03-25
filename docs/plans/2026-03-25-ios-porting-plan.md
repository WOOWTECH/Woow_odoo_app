# iOS Porting Plan: Woow Odoo App

> **Date:** 2026-03-25
> **Author:** Claude Code (automated analysis of Android codebase)
> **Android Source:** `/Users/alanlin/Woow_odoo_app` (35 Kotlin files, 178 unit tests)
> **Target:** Native iOS app with feature parity to Android v2.0
> **Audience:** Senior Swift developer performing the port

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Technology Stack Decision](#2-technology-stack-decision)
3. [Architecture Mapping](#3-architecture-mapping)
4. [Module-by-Module Porting Plan](#4-module-by-module-porting-plan)
5. [What Can Be Shared](#5-what-can-be-shared)
6. [What Must Be Rewritten](#6-what-must-be-rewritten)
7. [iOS-Specific Considerations](#7-ios-specific-considerations)
8. [Repository Structure](#8-repository-structure)
9. [Timeline Estimate](#9-timeline-estimate)
10. [Risk Register](#10-risk-register)

---

## 1. Executive Summary

The Woow Odoo App is an Odoo ERP companion that wraps the Odoo web interface in a native shell with enhanced security, push notifications, multi-account management, and brand theming. The Android version uses Jetpack Compose + Hilt + Room + OkHttp across 35 source files. The iOS port will use SwiftUI + Swift Package architecture + SwiftData + URLSession to achieve feature parity.

### Scope of the Port

| Feature | Android Status | iOS Effort |
|---------|---------------|------------|
| WebView with Odoo session | Verified | Medium -- WKWebView has different cookie/JS APIs |
| JSON-RPC authentication | Verified | Low -- URLSession is straightforward |
| Multi-account (DB + encrypted storage) | Verified | Medium -- SwiftData + Keychain |
| Biometric + PIN lock | Verified | Low -- LAContext is simpler than BiometricPrompt |
| Push notifications (FCM) | Verified | Medium -- APNs + Firebase iOS SDK |
| Deep link handling | Verified | Low -- URL scheme + UserActivity |
| Brand color system | Verified | Low -- SwiftUI Color is more flexible |
| zh-CN + zh-TW + EN localization | Verified | Low -- .strings/.xcstrings copy |
| Cache clearing | Verified | Low -- WKWebsiteDataStore API |
| Security hardening | Verified | Medium -- different threat model |

**Estimated total effort:** 4-5 weeks for a senior Swift developer (see Section 9).

---

## 2. Technology Stack Decision

### Language and Frameworks

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | **Swift 6** | Strict concurrency checking, typed throws, latest tooling |
| UI Framework | **SwiftUI** (iOS 17+) | NavigationStack, Observable macro, feature parity with Compose |
| Min iOS Version | **iOS 17.0** | Required for SwiftData, Observable macro, NavigationStack improvements; covers 95%+ of active devices as of March 2026 |
| Xcode Version | **Xcode 16+** | Swift 6 support, SwiftUI previews, latest iOS SDK |

### Dependency Choices

| Android Dependency | iOS Equivalent | Package Source |
|-------------------|----------------|---------------|
| OkHttp | **URLSession** (built-in) | Apple SDK |
| Gson | **Codable + JSONDecoder** (built-in) | Apple SDK |
| Room | **SwiftData** | Apple SDK |
| EncryptedSharedPreferences | **iOS Keychain** (via KeychainAccess) | SPM: [KeychainAccess](https://github.com/kishikawakatsumi/KeychainAccess) |
| Hilt (DI) | **Swift Dependencies** or manual protocol injection | SPM: [swift-dependencies](https://github.com/pointfreeco/swift-dependencies) or manual |
| Timber | **os.Logger** (built-in) | Apple SDK -- unified logging with OSLog |
| Firebase Messaging | **Firebase iOS SDK** (FirebaseMessaging) | SPM: [firebase-ios-sdk](https://github.com/firebase/firebase-ios-sdk) |
| BiometricPrompt | **LocalAuthentication** (LAContext) (built-in) | Apple SDK |
| Android WebView | **WKWebView** (built-in) | Apple SDK |
| Navigation Compose | **NavigationStack** (built-in) | Apple SDK |
| Jetpack Compose | **SwiftUI** (built-in) | Apple SDK |
| CryptoKit (PBKDF2) | **CryptoKit + CommonCrypto** (built-in) | Apple SDK |
| JUnit 5 + MockK | **Swift Testing** + manual test doubles | Apple SDK |
| Material3 theming | **SwiftUI custom theme** | Custom implementation |

### Dependency Philosophy

Minimize third-party dependencies. The only external packages should be:
1. **Firebase iOS SDK** -- required for FCM push notification compatibility with the existing Odoo backend module
2. **KeychainAccess** -- thin wrapper around Security.framework; can be replaced with raw Keychain API if preferred

Everything else uses Apple's built-in frameworks.

---

## 3. Architecture Mapping

### 3.1 Overall Architecture

```
Android (Current)                    iOS (Target)
-----------------                    -----------
Single Activity + Compose            Single WindowGroup + SwiftUI
Hilt DI (AppModule)                  Protocol injection / @Environment
NavHost (NavGraph.kt)                NavigationStack (Router.swift)
ViewModel + StateFlow                @Observable class + @Published
Room Database                        SwiftData ModelContainer
EncryptedSharedPreferences           iOS Keychain (KeychainAccess)
OkHttp + CookieJar                   URLSession + HTTPCookieStorage
FCM (FirebaseMessaging)              APNs + Firebase iOS SDK
```

### 3.2 Component-Level Mapping

#### Navigation

| Android | iOS |
|---------|-----|
| `NavHost` + `sealed class Screen` | `NavigationStack` + `enum AppRoute: Hashable` |
| `navController.navigate(route)` | `router.push(.destination)` or `router.path.append(.destination)` |
| `popUpTo(route) { inclusive = true }` | `router.path = []` (reset to root) |
| `LifecycleEventEffect(ON_STOP)` | `@Environment(\.scenePhase)` onChange handler |

**iOS Design:**
```
enum AppRoute: Hashable {
    case splash
    case login
    case auth        // Biometric/PIN gate
    case pin
    case main        // WebView
    case config
    case settings
}
```

The `Router` will be an `@Observable` class holding a `NavigationPath` and injected via `@Environment`.

#### Data Layer

| Android | iOS |
|---------|-----|
| `OdooAccount` (@Entity) | `OdooAccount` (@Model, SwiftData) |
| `AccountDao` (@Dao) | SwiftData `ModelContext` queries via `@Query` or `FetchDescriptor` |
| `AccountRepository` | `AccountRepository` (protocol + implementation) |
| `EncryptedPrefs` | `SecureStorage` (wrapping Keychain) |
| `SettingsRepository` | `SettingsRepository` (protocol + implementation) |
| `OdooJsonRpcClient` | `OdooAPIClient` (URLSession + async/await) |
| `CacheRepository` | `CacheService` (WKWebsiteDataStore) |

#### Push Notifications

| Android | iOS |
|---------|-----|
| `WoowFcmService` (FirebaseMessagingService) | `AppDelegate.messaging(_:didReceiveRegistrationToken:)` + `UNUserNotificationCenter` delegate |
| `NotificationHelper` | `NotificationService` (UNNotificationContent builder) |
| `DeepLinkManager` | `DeepLinkManager` (identical pattern using `CurrentValueSubject` or `@Observable`) |
| `DeepLinkValidator` | `DeepLinkValidator` (port directly -- pure logic, no platform deps) |
| `FcmTokenRepository` | `PushTokenRepository` (protocol + implementation) |

#### Authentication and Security

| Android | iOS |
|---------|-----|
| `BiometricPrompt` + `CryptoObject` | `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` |
| PBKDF2WithHmacSHA256 (javax.crypto) | `PBKDF2<SHA256>` via CommonCrypto or CryptoKit |
| `AuthViewModel` | `AuthViewModel` (@Observable) |
| `SystemClock.elapsedRealtime()` | `ProcessInfo.processInfo.systemUptime` or `ContinuousClock` |
| Background detection (ON_STOP) | `@Environment(\.scenePhase)` transitioning to `.background` |

#### WebView

| Android | iOS |
|---------|-----|
| `android.webkit.WebView` | `WKWebView` via `UIViewRepresentable` |
| `WebViewClient.shouldOverrideUrlLoading` | `WKNavigationDelegate.decidePolicyFor navigationAction` |
| `WebViewClient.onPageFinished` | `WKNavigationDelegate.didFinish` |
| `WebChromeClient.onShowFileChooser` | `UIDocumentPickerViewController` + `WKUIDelegate` for file input |
| `CookieManager.setCookie` | `WKWebsiteDataStore.httpCookieStore.setCookie` |
| `WebSettings.userAgentString` | `WKWebViewConfiguration.applicationNameForUserAgent` or custom UA |
| JavaScript injection (evaluateJavascript) | `WKWebView.evaluateJavaScript` |
| `WebChromeClient.onConsoleMessage` | `WKUserContentController` with message handler for console.log |

#### Theming

| Android | iOS |
|---------|-----|
| `ThemeManager` (StateFlow of Color) | `ThemeManager` (@Observable, `Color` values) |
| `MaterialTheme` + `lightColorScheme/darkColorScheme` | Custom `WoowColorScheme` injected via `@Environment` |
| `Color(0xFF6183FC)` | `Color(hex: 0x6183FC)` (custom initializer) |
| `isSystemInDarkTheme()` | `@Environment(\.colorScheme)` |

---

## 4. Module-by-Module Porting Plan

### Phase 1: Project Setup and Core Infrastructure (Week 1)

#### P1.1 -- Xcode Project Creation
- Create new Xcode project with SwiftUI lifecycle
- Configure bundle identifier: `io.woowtech.odoo`
- Set deployment target to iOS 17.0
- Configure Swift 6 strict concurrency mode
- Add Firebase iOS SDK via SPM
- Add KeychainAccess via SPM
- Configure GoogleService-Info.plist
- Set up App Transport Security (ATS) in Info.plist

**Effort:** 0.5 days

#### P1.2 -- Domain Models
Port all domain models from `domain/model/`:

| Android File | iOS File | Notes |
|-------------|----------|-------|
| `OdooAccount.kt` (@Entity) | `OdooAccount.swift` (@Model) | SwiftData model with UUID primary key |
| `AuthResult.kt` (sealed class) | `AuthResult.swift` (enum with associated values) | Direct mapping |
| `AppSettings.kt` (data class) | `AppSettings.swift` (struct) | Codable for Keychain serialization |
| `ThemeMode` (enum) | `ThemeMode.swift` (enum: Codable) | Identical semantics |
| `AppLanguage` (enum) | `AppLanguage.swift` (enum: Codable) | Identical semantics |

**Effort:** 0.5 days

#### P1.3 -- Secure Storage Layer
Port `EncryptedPrefs.kt` to iOS Keychain:

- `SecureStorage.swift` -- wraps KeychainAccess library
- Stores passwords keyed by account ID
- Stores app settings as JSON blob in Keychain
- Stores FCM/APNs token
- PIN hash and lockout state storage
- All Keychain items use `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`

**Effort:** 1 day

#### P1.4 -- Database Layer (SwiftData)
Port Room database to SwiftData:

- `OdooAccount` as `@Model` class
- No DAO interface needed -- SwiftData uses `ModelContext` directly
- Account queries via `FetchDescriptor` with predicates
- Migration strategy: not needed (fresh app)

**Effort:** 0.5 days

#### P1.5 -- Networking Layer (OdooAPIClient)
Port `OdooJsonRpcClient.kt` to URLSession:

- `OdooAPIClient.swift` -- singleton actor for thread safety
- JSON-RPC 2.0 request/response models (Codable)
- `URLSession` with custom `URLSessionConfiguration`:
  - 30-second timeouts
  - `HTTPCookieStorage.shared` for automatic cookie management
  - No third-party cookie acceptance
- HTTPS-only enforcement
- Session ID extraction from cookies
- Cookie clearing per host

**Key difference:** URLSession handles cookies natively via `HTTPCookieStorage`, eliminating the need for a custom `CookieJar` implementation.

**Effort:** 1 day

#### P1.6 -- Logging
Port Timber to os.Logger:

- `AppLogger.swift` -- static wrapper around `os.Logger`
- Subsystem: `io.woowtech.odoo`
- Categories: `auth`, `network`, `push`, `webview`, `settings`
- Debug-only sensitive data logging (compile-time `#if DEBUG`)
- No user data in release builds

**Effort:** 0.5 days

---

### Phase 2: Authentication Flow (Week 1-2)

#### P2.1 -- Login Screen
Port `LoginScreen.kt` + `LoginViewModel.kt`:

- `LoginView.swift` -- SwiftUI form with server URL, database, username, password fields
- `LoginViewModel.swift` -- @Observable class
- HTTPS prefix auto-insertion
- Loading state management
- Error display with localized messages
- Keyboard handling and field focus management

**Effort:** 1 day

#### P2.2 -- Account Repository
Port `AccountRepository.kt`:

- `AccountRepository.swift` -- protocol
- `AccountRepositoryImpl.swift` -- SwiftData + SecureStorage + OdooAPIClient
- Multi-account support: deactivate all, activate selected
- Re-authentication on account switch
- Logout with cookie and password cleanup

**Effort:** 1 day

#### P2.3 -- Biometric Authentication
Port `BiometricScreen.kt`:

- `BiometricView.swift` -- SwiftUI screen
- LAContext integration for Face ID / Touch ID
- Automatic biometric prompt on appear
- "Use PIN" fallback button (no skip button)
- Biometric availability check via `canEvaluatePolicy`

**iOS advantage:** LAContext is significantly simpler than Android's BiometricPrompt. No need for CryptoObject, BiometricManager, or prompt configuration classes.

**Effort:** 0.5 days

#### P2.4 -- PIN Screen and PBKDF2 Hashing
Port `PinScreen.kt` + PIN verification from `SettingsRepository.kt`:

- `PinView.swift` -- custom number pad with dot indicators
- `PinHasher.swift` -- PBKDF2WithHmacSHA256 using CommonCrypto:
  - 600,000 iterations (matching Android)
  - 16-byte random salt
  - 256-bit output hash
  - Storage format: `salt_hex:hash_hex` (matching Android for potential cross-platform migration)
- Exponential lockout: 5 failures = 30s, 10 = 5min, 15 = 30min, 20+ = 1hr
- Lockout timing via `ProcessInfo.processInfo.systemUptime` (not wall clock)

**Effort:** 1 day

#### P2.5 -- Auth ViewModel and Navigation
Port `AuthViewModel.kt` + `NavGraph.kt`:

- `AuthViewModel.swift` -- @Observable class
- `AppRouter.swift` -- @Observable class holding navigation state
- Scene phase monitoring for background detection:
  ```swift
  .onChange(of: scenePhase) { oldPhase, newPhase in
      if newPhase == .background {
          authViewModel.onAppBackgrounded()
      }
  }
  ```
- Start destination logic: splash -> login / auth / main

**Effort:** 1 day

---

### Phase 3: WebView and Main Screen (Week 2-3)

#### P3.1 -- WKWebView Wrapper
Port `MainScreen.kt` (OdooWebView composable):

- `OdooWebView.swift` -- `UIViewRepresentable` wrapping `WKWebView`
- `OdooWebViewCoordinator.swift` -- implements `WKNavigationDelegate` + `WKUIDelegate`

**Configuration (matching Android security hardening):**
```
WKWebViewConfiguration:
  - JavaScript enabled
  - DOM storage enabled (default)
  - No file access from URLs
  - Non-persistent data store option for cache control
  - Custom user agent (mobile Safari)

WKWebsiteDataStore:
  - Default data store (persistent cookies)
  - Third-party cookie blocking via WKWebpagePreferences

Navigation policy:
  - Same-host only (decidePolicyFor navigationAction)
  - Session expiry detection (/web/login redirect)
  - External URLs -> UIApplication.shared.open()
  - Block javascript: and data: schemes
```

**Post-load JavaScript injection:**
Port the OWL framework layout fix JavaScript from Android's `onPageFinished` to WKWebView's `didFinish`:
- Force body/html height to 100%
- Force action_manager min-height
- Dispatch resize events at 0ms, 100ms, 500ms, 1000ms

**File upload handling:**
- `WKUIDelegate.webView(_:runOpenPanelWith:)` is macOS only
- iOS file input: WKWebView handles `<input type="file">` natively via UIDocumentPickerViewController
- Camera integration: override via `UIImagePickerController` if needed

**Cookie synchronization:**
```swift
let cookie = HTTPCookie(properties: [
    .name: "session_id",
    .value: sessionId,
    .domain: host,
    .path: "/",
    .secure: "TRUE"
])!
webView.configuration.websiteDataStore.httpCookieStore.setCookie(cookie)
```

**Effort:** 2 days (WKWebView is the most complex part due to delegation patterns)

#### P3.2 -- Main Screen UI
Port `MainScreen.kt` toolbar and loading:

- `MainView.swift` -- NavigationStack with toolbar
- Loading overlay with ProgressView
- Menu button navigation to config
- Deep link URL consumption on appear

**Effort:** 0.5 days

#### P3.3 -- Main ViewModel
Port `MainViewModel.kt`:

- `MainViewModel.swift` -- @Observable class
- Active account observation via SwiftData `@Query`
- Session ID retrieval
- Deep link consumption
- Credentials loading for WebView

**Effort:** 0.5 days

---

### Phase 4: Push Notifications (Week 3)

#### P4.1 -- Firebase iOS SDK Integration
Port `WoowFcmService.kt`:

- `AppDelegate.swift` -- Firebase configuration + UNUserNotificationCenter delegate + FIRMessagingDelegate
- Token registration: `messaging(_:didReceiveRegistrationToken:)`
- Remote notification handling: `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`
- Background notification support via `content-available: 1`
- Notification permission request at first launch

**Key difference from Android:**
- iOS uses APNs as transport; Firebase acts as intermediary
- Requires APNs certificate or key uploaded to Firebase console
- No background FCM service -- uses delegate methods instead
- Notification categories for action buttons

**Effort:** 1 day

#### P4.2 -- Notification Display
Port `NotificationHelper.kt`:

- `NotificationService.swift` -- UNNotificationContent builder
- Notification content:
  - Title, body, action URL in userInfo
  - Sound: `.default`
  - Badge increment
  - Thread identifier for grouping (maps to Android's notification groups)
- Privacy: content hidden on lock screen via `.hiddenPreviewsBodyPlaceholder`

**Effort:** 0.5 days

#### P4.3 -- Deep Link Handling
Port `DeepLinkManager.kt` + `DeepLinkValidator.kt`:

- `DeepLinkManager.swift` -- @Observable class with optional pending URL
- `DeepLinkValidator.swift` -- static methods, pure logic port
  - Reject `javascript:`, `data:` schemes
  - Allow relative `/web` paths
  - Verify same-host for absolute URLs
- Notification tap handling: extract `odoo_action_url` from `UNNotificationResponse.notification.request.content.userInfo`

**Effort:** 0.5 days

#### P4.4 -- Push Token Repository
Port `FcmTokenRepository.kt` + `FcmTokenRepositoryImpl.kt`:

- `PushTokenRepository.swift` -- protocol
- `PushTokenRepositoryImpl.swift` -- registers token with all active Odoo accounts via POST to `/woow_fcm_push/register`
- Token stored in Keychain
- Platform field: `"ios"` (the Odoo module already supports `platform` field with `ios` value)

**Effort:** 0.5 days

---

### Phase 5: Configuration and Settings (Week 3-4)

#### P5.1 -- Config Screen
Port `ConfigScreen.kt` + `ConfigViewModel.kt`:

- `ConfigView.swift` -- SwiftUI List with sections
- Profile card with avatar initial
- Settings navigation
- Account switcher (expandable list)
- Add account button
- Logout with confirmation alert

**Effort:** 1 day

#### P5.2 -- Settings Screen
Port `SettingsScreen.kt` + `SettingsViewModel.kt`:

**Sections to port:**
1. **Appearance** -- Theme color picker, dark mode toggle
2. **Security** -- App lock toggle, biometric toggle, PIN setup
3. **Language** -- System/English/zh-TW/zh-CN picker
4. **Data & Storage** -- Cache size display, clear cache button
5. **About** -- App version, build info

**Effort:** 1.5 days

#### P5.3 -- Brand Color System
Port `Color.kt` + `Theme.kt` + `Type.kt`:

- `WoowColors.swift` -- all brand colors as static `Color` properties
- `WoowTheme.swift` -- `@Observable` theme manager with:
  - 5 brand colors (Primary Blue, White, Light Gray, Gray, Deep Gray)
  - 10 accent colors
  - Light/dark color scheme generation from primary color
  - HEX input parsing
- Color picker with `LazyVGrid` equivalent (`LazyVGrid` in SwiftUI)
- Custom HEX text field with validation

**Effort:** 1 day

#### P5.4 -- Settings Repository
Port `SettingsRepository.kt`:

- `SettingsRepository.swift` -- protocol
- `SettingsRepositoryImpl.swift` -- backed by SecureStorage (Keychain)
- Theme color persistence
- App lock state
- PIN hash storage and verification
- Language preference
- Failed attempts tracking and lockout

**Effort:** 1 day

---

### Phase 6: Localization (Week 4)

#### P6.1 -- String Resources
Port all string resources:

| Android | iOS |
|---------|-----|
| `res/values/strings.xml` (English) | `en.lproj/Localizable.strings` or `Localizable.xcstrings` |
| `res/values-zh-rTW/strings.xml` | `zh-Hant.lproj/Localizable.strings` |
| `res/values-zh-rCN/strings.xml` | `zh-Hans.lproj/Localizable.strings` |

- Use String Catalogs (`.xcstrings`) for modern Xcode 16 workflow
- In-app language override via `Bundle.setLanguage()` or custom `LocalizedStringKey` resolution
- All displayed strings via `String(localized:)` -- never hardcoded

**Effort:** 1 day

---

### Phase 7: Cache Management (Week 4)

#### P7.1 -- Cache Service
Port `CacheRepository.kt`:

- `CacheService.swift` -- actor for thread safety
- Clear WKWebView data: `WKWebsiteDataStore.default().removeData(ofTypes:modifiedSince:)`
- Clear app cache: `FileManager.default.removeItem(at: cachesDirectory)`
- Calculate cache size: walk `cachesDirectory` with `FileManager`
- Preserve login: only clear cache data types, not cookies

**iOS advantage:** `WKWebsiteDataStore.removeData` is more granular than Android's `WebStorage.deleteAllData` -- can selectively clear disk cache, memory cache, offline storage, etc. while preserving cookies.

**Effort:** 0.5 days

---

### Phase 8: Testing (Week 4-5)

#### P8.1 -- Unit Tests
Port all 178 Android unit tests to Swift Testing:

| Android Test File | iOS Test File | Test Count |
|------------------|---------------|------------|
| `AuthViewModelTest.kt` | `AuthViewModelTests.swift` | 8 |
| `SettingsRepositoryPinTest.kt` | `PinHasherTests.swift` + `SettingsRepositoryTests.swift` | 14 |
| `DeepLinkValidatorTest.kt` | `DeepLinkValidatorTests.swift` | 13 |
| `DeepLinkManagerTest.kt` | `DeepLinkManagerTests.swift` | 5 |
| `CacheRepositoryTest.kt` | `CacheServiceTests.swift` | 3 |
| `FcmTokenRepositoryTest.kt` | `PushTokenRepositoryTests.swift` | ~10 |
| `LoginViewModelTest.kt` | `LoginViewModelTests.swift` | ~15 |
| `SettingsViewModelTest.kt` | `SettingsViewModelTests.swift` | ~10 |
| `OdooJsonRpcClientTest.kt` | `OdooAPIClientTests.swift` | ~20 |
| `AccountRepositoryTest.kt` | `AccountRepositoryTests.swift` | ~15 |
| `WoowFcmServiceTest.kt` | `PushNotificationTests.swift` | ~10 |
| `NotificationHelperTest.kt` | `NotificationServiceTests.swift` | ~5 |

**Testing framework:** Swift Testing (`@Test`, `#expect`, `@Suite`) for all new tests.

**Mocking strategy:** Protocol-based dependency injection with manual test doubles (no mocking library needed in Swift). All repositories are protocols -- tests inject mock implementations.

**Effort:** 2 days

#### P8.2 -- UI Tests (XCUITest)
- Login flow end-to-end
- Biometric screen presence (no skip button verification)
- PIN entry and lockout behavior
- Navigation flow (config, settings, back)
- Deep link handling

**Effort:** 1 day

#### P8.3 -- Snapshot Tests
- Login screen (light/dark)
- PIN screen
- Config screen
- Settings screen
- Color picker dialog

**Effort:** 0.5 days

---

## 5. What Can Be Shared

### 5.1 Odoo Backend Module (100% shared)
The `woow_fcm_push` Odoo module already supports iOS:
- `fcm_device.py` has a `platform` field with `android`/`ios` selection
- FCM HTTP v1 API sends to both Android and iOS tokens identically
- The `test_ios_platform` test verifies iOS device registration
- No changes needed to the Odoo module

### 5.2 API Contracts (100% shared)
- JSON-RPC 2.0 request/response format is identical
- Authentication endpoint: `POST /web/session/authenticate`
- FCM registration endpoint: `POST /woow_fcm_push/register`
- FCM payload structure: `title`, `body`, `odoo_action_url`, `event_type`

### 5.3 Deep Link URL Scheme (100% shared)
- URL format: `woowodoo://` scheme
- Notification action URL format: `/web#id=42&model=sale.order&view_type=form`
- Validation rules: same logic, can be ported line-by-line

### 5.4 Brand Colors (100% shared)
- All hex values are platform-independent
- Color names and ratios are identical
- 5 brand + 10 accent colors

### 5.5 Localization Strings (90% shared)
- String content is identical (only format specifiers differ: `%s` vs `%@`)
- 141 zh-CN strings, 138+ zh-TW strings, 140+ English strings
- Minor format conversion needed

### 5.6 PIN Hash Format (100% shared)
- PBKDF2WithHmacSHA256 is available on both platforms
- Salt format: `salt_hex:hash_hex` -- identical storage format
- 600,000 iterations, 16-byte salt, 256-bit hash
- Cross-platform PIN verification is possible if accounts sync

### 5.7 Firebase Project (100% shared)
- Same Firebase project (`woow-odoo-de2cb`) can serve both platforms
- Add iOS app in Firebase Console
- Upload APNs key (`.p8`) or certificate to Firebase

---

## 6. What Must Be Rewritten

### 6.1 WebView Implementation (complete rewrite)
WKWebView and Android WebView have fundamentally different APIs:

| Concern | Android | iOS |
|---------|---------|-----|
| Delegation | `WebViewClient` + `WebChromeClient` inner classes | `WKNavigationDelegate` + `WKUIDelegate` protocols |
| Cookie sync | `CookieManager.setCookie(url, cookieString)` | `WKHTTPCookieStore.setCookie(HTTPCookie)` |
| URL interception | `shouldOverrideUrlLoading` returns boolean | `decidePolicyFor` calls completion handler |
| JS injection | `evaluateJavascript(script, callback)` | `evaluateJavaScript(script) async throws` |
| File upload | `onShowFileChooser` with `ValueCallback` | Native `<input type="file">` support in WKWebView |
| Console logging | `onConsoleMessage` | `WKUserContentController` message handler |
| User agent | `settings.userAgentString = "..."` | `customUserAgent` property |
| SwiftUI hosting | N/A (AndroidView) | `UIViewRepresentable` with Coordinator |

**This is the highest-risk component.** The OWL framework layout workarounds may behave differently in WKWebView. Dedicated testing time should be allocated.

### 6.2 Encrypted Storage (complete rewrite)
Android's `EncryptedSharedPreferences` and iOS Keychain are completely different:

| Concern | Android | iOS |
|---------|---------|-----|
| API | `SharedPreferences` interface | `SecItemAdd/Update/Delete/CopyMatching` |
| Encryption | AES256-GCM (AndroidX Security) | Hardware-backed Secure Enclave |
| Key management | MasterKey with KeyStore | Keychain Access Groups |
| Data types | String, Boolean, Int, Long | Data (serialize anything) |
| Access control | App sandbox | Keychain Access Groups + ACL |

### 6.3 Database Layer (complete rewrite)
Room and SwiftData are different ORMs:

| Concern | Android | iOS |
|---------|---------|-----|
| Definition | `@Entity` + `@Dao` | `@Model` class |
| Queries | `@Query` SQL strings | `FetchDescriptor` + `#Predicate` |
| Observation | `Flow<List<T>>` | `@Query` property wrapper in SwiftUI |
| Migration | Room migration objects | SwiftData lightweight migration |
| Thread safety | Coroutine dispatchers | Actor isolation |

### 6.4 Push Notification Infrastructure (complete rewrite)
FCM on Android vs APNs+FCM on iOS:

| Concern | Android | iOS |
|---------|---------|-----|
| Service | `FirebaseMessagingService` subclass | `AppDelegate` + `UNUserNotificationCenterDelegate` |
| Token | `onNewToken(token)` override | `messaging(_:didReceiveRegistrationToken:)` delegate |
| Foreground | `onMessageReceived` | `userNotificationCenter(_:willPresent:)` |
| Background | `onMessageReceived` (auto-wake) | `application(_:didReceiveRemoteNotification:)` with `content-available` |
| Permission | POST_NOTIFICATIONS runtime permission (API 33+) | `UNUserNotificationCenter.requestAuthorization` |
| Channel | `NotificationChannel` (required API 26+) | `UNNotificationCategory` (optional) |

### 6.5 Biometric Authentication (complete rewrite)
Although the concept is identical, the API is completely different:

| Concern | Android | iOS |
|---------|---------|-----|
| API | `BiometricPrompt` + `BiometricManager` | `LAContext.evaluatePolicy` |
| Prompt UI | System-provided dialog | System-provided dialog |
| Types | Fingerprint, Face, Iris | Touch ID, Face ID |
| Availability | `BiometricManager.canAuthenticate()` | `canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` |
| Error handling | `AuthenticationCallback` | `LAError` codes |
| Privacy | N/A | Must declare `NSFaceIDUsageDescription` in Info.plist |

### 6.6 Lifecycle Management (partial rewrite)
| Concern | Android | iOS |
|---------|---------|-----|
| Background detection | `LifecycleEventEffect(ON_STOP)` | `@Environment(\.scenePhase)` |
| App launch | `Application.onCreate` | `@main App.init()` or `AppDelegate.didFinishLaunching` |
| Deep link intent | `Intent` extras | `onOpenURL` modifier or `NSUserActivity` |
| New intent | `onNewIntent(intent)` | `onOpenURL` or `userNotificationCenter(_:didReceive:)` |

---

## 7. iOS-Specific Considerations

### 7.1 App Store Review Compliance

| Requirement | Implementation |
|-------------|---------------|
| **4.2 Minimum Functionality** | The app adds native value beyond the WebView: biometric lock, push notifications, multi-account, offline account management. Clearly document these in the review notes. |
| **2.5.6 WebView Apps** | The app must provide significant native functionality. Push notifications + biometric auth + multi-account should satisfy this. Include "App Review Notes" explaining native features. |
| **5.1.1 Data Collection** | Declare push notification token collection in Privacy Nutrition Label. No third-party analytics. |
| **5.1.2 Data Use and Sharing** | Only user-provided credentials are stored (locally in Keychain). No data sharing. |
| **2.1 App Completeness** | All features must work end-to-end. Test with production Odoo server before submission. |
| **IPv6 Network** | URLSession handles IPv6 automatically. No IPv4-specific code. |

**Risk:** Apple has historically rejected pure WebView wrapper apps (guideline 4.2). The native authentication, biometric lock, push notifications, multi-account management, and brand theming provide sufficient differentiation. Include detailed review notes.

### 7.2 APNs Certificate Setup

Required steps before push notifications work:
1. Create an Apple Developer account (if not already done)
2. Enable "Push Notifications" capability in Xcode
3. Create an APNs Authentication Key (`.p8` file) in Apple Developer portal
4. Upload the `.p8` key to Firebase Console (Project Settings > Cloud Messaging > iOS)
5. Add `GoogleService-Info.plist` to the Xcode project
6. Configure `FirebaseApp.configure()` in AppDelegate

**Note:** APNs Authentication Key (`.p8`) is preferred over APNs Certificate (`.p12`) because keys do not expire annually.

### 7.3 Keychain Best Practices

- Use `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` for passwords and PIN hashes
- Use `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` for push tokens (needed for background delivery)
- Set `kSecAttrSynchronizable: false` -- credentials should not sync via iCloud Keychain
- Use unique Keychain service identifier: `io.woowtech.odoo.keychain`
- Handle Keychain errors gracefully (locked device, migration from backup, etc.)

### 7.4 App Transport Security (ATS)

The app requires HTTPS connections to Odoo servers (matching Android behavior). ATS configuration:

```xml
<!-- Info.plist -- Default ATS is already HTTPS-only, no exceptions needed -->
<key>NSAppTransportSecurity</key>
<dict>
    <!-- No exceptions: all connections must use HTTPS -->
    <!-- This matches Android's HTTPS-only enforcement in OdooJsonRpcClient -->
</dict>
```

No ATS exceptions should be added. The Android app already rejects non-HTTPS URLs.

### 7.5 Info.plist Privacy Keys

```xml
<key>NSFaceIDUsageDescription</key>
<string>Use Face ID to unlock the Woow Odoo app</string>

<key>NSCameraUsageDescription</key>
<string>Take photos for Odoo attachments</string>

<key>NSPhotoLibraryUsageDescription</key>
<string>Select photos for Odoo attachments</string>
```

### 7.6 Background App Refresh

For push notification reliability:
- Enable "Background Modes" capability with "Remote notifications" checked
- This allows the app to process FCM data messages in the background
- No background fetch or location updates needed

### 7.7 Safe Area and Device Adaptation

Unlike Android's single-activity approach, iOS must handle:
- Dynamic Island / notch (safe area insets)
- iPhone SE / mini small screens
- iPad (if supporting universal app)
- Landscape orientation (recommend: lock to portrait initially, matching typical ERP usage)

### 7.8 WKWebView Differences from Android WebView

Critical differences that affect Odoo rendering:

1. **Cookie persistence:** WKWebView cookies are not shared with URLSession by default. Must manually sync via `WKHTTPCookieStore`.
2. **Process isolation:** WKWebView runs in a separate process. Crashes do not take down the app.
3. **JavaScript execution:** `evaluateJavaScript` is async. Cannot block on result.
4. **File input:** WKWebView on iOS natively handles `<input type="file">` without custom delegate code. Camera may require `UIImagePickerController` integration.
5. **User Agent:** WKWebView's default UA identifies as Safari. Odoo may serve a different frontend layout. Test with and without custom UA override.
6. **Content size:** WKWebView does not have `loadWithOverviewMode` or `useWideViewPort`. The OWL framework may behave differently. This is the primary area of investigation.

---

## 8. Repository Structure

```
WoowOdooiOS/
|-- WoowOdoo/
|   |-- App/
|   |   |-- WoowOdooApp.swift              # @main App entry point
|   |   |-- AppDelegate.swift              # Firebase, APNs, notification delegate
|   |   |-- AppRouter.swift                # NavigationStack state management
|   |-- Data/
|   |   |-- API/
|   |   |   |-- OdooAPIClient.swift        # URLSession JSON-RPC client
|   |   |   |-- JsonRpcModels.swift        # Request/Response Codable structs
|   |   |-- Local/
|   |   |   |-- SecureStorage.swift        # Keychain wrapper (EncryptedPrefs equivalent)
|   |   |-- Push/
|   |   |   |-- DeepLinkManager.swift      # Pending URL state holder
|   |   |   |-- DeepLinkValidator.swift    # URL safety validation
|   |   |   |-- NotificationService.swift  # UNNotification builder
|   |   |-- Repository/
|   |   |   |-- AccountRepository.swift    # Protocol + implementation
|   |   |   |-- SettingsRepository.swift   # Protocol + implementation
|   |   |   |-- PushTokenRepository.swift  # Protocol + implementation
|   |   |   |-- CacheService.swift         # WKWebsiteDataStore cache ops
|   |-- Domain/
|   |   |-- Model/
|   |   |   |-- OdooAccount.swift          # @Model (SwiftData)
|   |   |   |-- AuthResult.swift           # Enum with associated values
|   |   |   |-- AppSettings.swift          # Struct (Codable)
|   |-- UI/
|   |   |-- Auth/
|   |   |   |-- AuthViewModel.swift        # Auth state management
|   |   |   |-- BiometricView.swift        # Face ID / Touch ID screen
|   |   |   |-- PinView.swift              # PIN entry screen
|   |   |-- Login/
|   |   |   |-- LoginView.swift            # Server + credentials form
|   |   |   |-- LoginViewModel.swift       # Login flow management
|   |   |-- Main/
|   |   |   |-- MainView.swift             # Toolbar + WebView container
|   |   |   |-- MainViewModel.swift        # Active account + deep links
|   |   |   |-- OdooWebView.swift          # UIViewRepresentable WKWebView
|   |   |   |-- OdooWebViewCoordinator.swift # WKNavigationDelegate + WKUIDelegate
|   |   |-- Config/
|   |   |   |-- ConfigView.swift           # Account management screen
|   |   |   |-- ConfigViewModel.swift      # Account switching logic
|   |   |   |-- SettingsView.swift         # App settings screen
|   |   |   |-- SettingsViewModel.swift    # Settings management
|   |   |-- Theme/
|   |   |   |-- WoowColors.swift           # Brand color definitions
|   |   |   |-- WoowTheme.swift            # Theme manager (@Observable)
|   |   |   |-- ColorPickerView.swift      # Brand color picker component
|   |   |-- Components/
|   |   |   |-- LoadingOverlay.swift        # Shared loading indicator
|   |   |   |-- AvatarInitial.swift         # User avatar with initial letter
|   |-- Security/
|   |   |-- PinHasher.swift                # PBKDF2 hashing (CommonCrypto)
|   |-- Util/
|   |   |-- AppLogger.swift                # os.Logger wrapper
|   |   |-- Color+Hex.swift                # Color extension for hex parsing
|   |   |-- String+Localized.swift         # Localization helpers
|   |-- Resources/
|   |   |-- Localizable.xcstrings          # String catalog (en, zh-Hant, zh-Hans)
|   |   |-- Assets.xcassets/               # App icons, colors, images
|   |   |-- GoogleService-Info.plist       # Firebase configuration
|   |   |-- Info.plist                     # Privacy keys, ATS, URL schemes
|-- WoowOdooTests/
|   |-- Data/
|   |   |-- API/
|   |   |   |-- OdooAPIClientTests.swift
|   |   |-- Push/
|   |   |   |-- DeepLinkValidatorTests.swift
|   |   |   |-- DeepLinkManagerTests.swift
|   |   |-- Repository/
|   |   |   |-- AccountRepositoryTests.swift
|   |   |   |-- SettingsRepositoryTests.swift
|   |   |   |-- PushTokenRepositoryTests.swift
|   |   |   |-- CacheServiceTests.swift
|   |-- UI/
|   |   |-- Auth/
|   |   |   |-- AuthViewModelTests.swift
|   |   |-- Login/
|   |   |   |-- LoginViewModelTests.swift
|   |   |-- Config/
|   |   |   |-- SettingsViewModelTests.swift
|   |-- Security/
|   |   |-- PinHasherTests.swift
|   |-- Mocks/
|   |   |-- MockAccountRepository.swift
|   |   |-- MockSecureStorage.swift
|   |   |-- MockOdooAPIClient.swift
|   |   |-- MockSettingsRepository.swift
|-- WoowOdooUITests/
|   |-- LoginFlowUITests.swift
|   |-- AuthFlowUITests.swift
|   |-- NavigationUITests.swift
|   |-- SettingsUITests.swift
|-- WoowOdoo.xcodeproj
```

**Total estimated files:** ~50 Swift source files + ~15 test files + resources

---

## 9. Timeline Estimate

### For One Senior Swift Developer

| Phase | Description | Duration | Dependencies |
|-------|-------------|----------|-------------|
| **P1** | Project setup + core infrastructure | 3.5 days | None |
| **P2** | Authentication flow | 4.5 days | P1 |
| **P3** | WebView and main screen | 3 days | P1, P2 |
| **P4** | Push notifications | 2.5 days | P1, P3 |
| **P5** | Configuration and settings | 4.5 days | P1, P2 |
| **P6** | Localization | 1 day | P5 |
| **P7** | Cache management | 0.5 days | P3 |
| **P8** | Testing | 3.5 days | P1-P7 |
| **Buffer** | Integration, bugs, WebView quirks | 2 days | All |

### Gantt Chart

```
Week 1          Week 2          Week 3          Week 4          Week 5
Mon-Fri         Mon-Fri         Mon-Fri         Mon-Fri         Mon-Wed
----------      ----------      ----------      ----------      --------
P1 ██████░░                                                     Setup + Core
P2 ░░░░░░██    ████████░░                                       Auth Flow
P3              ░░░░░░██████                                     WebView
P4                      ░░░░██████                               Push
P5              ██░░░░░░░░░░████████░░                           Config/Settings
P6                                      ████░░                   Localization
P7                              ░░██                             Cache
P8                                      ░░████████████           Testing
BUF                                                 ░░████████  Buffer

░ = blocked    █ = active
```

### Total Estimate

| Scenario | Duration | Notes |
|----------|----------|-------|
| **Optimistic** | 3.5 weeks | No WKWebView Odoo rendering issues |
| **Expected** | 4.5 weeks | Some WebView quirks, 1-2 days debugging |
| **Pessimistic** | 6 weeks | Major Odoo OWL layout issues in WKWebView, App Store rejection and resubmission |

### Milestones

| Milestone | Target | Exit Criteria |
|-----------|--------|---------------|
| M1: Can login and see Odoo | End of Week 2 | Auth flow + WebView working on device |
| M2: Push notifications working | End of Week 3 | FCM token registered, notification appears |
| M3: Feature complete | End of Week 4 | All features ported, settings working |
| M4: Test complete + App Store ready | End of Week 5 | All tests passing, TestFlight build |

---

## 10. Risk Register

### High Risk

| # | Risk | Impact | Probability | Mitigation |
|---|------|--------|-------------|------------|
| R1 | **Odoo OWL framework renders incorrectly in WKWebView** | WebView is the core feature; broken rendering = broken app | Medium | Allocate 2 days buffer specifically for WebView debugging. Test with multiple Odoo versions (16, 17, 18). The Android app already required extensive layout workarounds (v1.0.12, v1.0.13, v1.0.14). |
| R2 | **App Store rejects app as "WebView wrapper" (guideline 4.2)** | Cannot distribute on App Store | Medium | Document all native features in review notes: biometric auth, push notifications, multi-account, brand theming, PIN lock, cache management. Include screenshots of native screens. |
| R3 | **WKWebView cookie synchronization fails** | Users cannot authenticate in WebView after native login | Medium | Test cookie sync thoroughly. WKHTTPCookieStore has known timing issues -- must wait for completion handler before loading URL. |

### Medium Risk

| # | Risk | Impact | Probability | Mitigation |
|---|------|--------|-------------|------------|
| R4 | **APNs certificate/key setup delays** | Push notifications non-functional | Low | Requires Apple Developer account and Firebase Console access. Start APNs key creation on Day 1. |
| R5 | **SwiftData migration issues** | Data model changes require migration logic | Low | Start with a simple model. SwiftData supports lightweight migration by default. |
| R6 | **File upload in WKWebView behaves differently** | Users cannot attach files in Odoo | Medium | WKWebView handles `<input type="file">` natively on iOS, but camera integration may need custom handling. Test early. |
| R7 | **PBKDF2 hash compatibility** | PIN hashes generated on one platform cannot be verified on the other | Low | Use identical parameters (algorithm, iterations, salt length, hash length, format). Write cross-platform verification test. |
| R8 | **In-app language switching** | iOS does not natively support per-app language override without settings.app | Medium | Implement custom `Bundle` override for language switching. Or use iOS 16+ per-app language setting in system Settings. |

### Low Risk

| # | Risk | Impact | Probability | Mitigation |
|---|------|--------|-------------|------------|
| R9 | **Firebase iOS SDK version conflicts** | Build failures | Low | Pin Firebase version in SPM. Currently firebase-ios-sdk 11.x is stable. |
| R10 | **Keychain data loss on app reinstall** | Users lose credentials after reinstall | Low | Set `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` -- Keychain data persists across reinstalls by default. Add migration logic for first launch. |
| R11 | **iPad layout issues** | Poor UI on larger screens | Low | Initially target iPhone only. Add iPad support in v2.0 if needed. Set "Devices: iPhone" in Xcode. |
| R12 | **Background notification delivery** | Notifications delayed or not delivered when app is in background/killed | Medium | Ensure FCM sends with `content-available: 1` and `mutable-content: 1`. Use Notification Service Extension if rich notifications needed. |

---

## Appendix A: Android-to-iOS File Mapping (Complete)

| Android Source File | iOS Target File | Effort |
|--------------------|----------------|--------|
| `WoowOdooApp.kt` | `WoowOdooApp.swift` + `AppDelegate.swift` | Low |
| `MainActivity.kt` | `WoowOdooApp.swift` (single entry point) | Low |
| `NavGraph.kt` | `AppRouter.swift` + root ContentView | Low |
| `OdooJsonRpcClient.kt` | `OdooAPIClient.swift` + `JsonRpcModels.swift` | Medium |
| `OdooAccount.kt` | `OdooAccount.swift` (@Model) | Low |
| `AuthResult.kt` | `AuthResult.swift` | Low |
| `AppSettings.kt` | `AppSettings.swift` | Low |
| `AccountDao.kt` | (SwiftData ModelContext -- no separate file) | Low |
| `AppDatabase.kt` | (SwiftData ModelContainer in App -- no separate file) | Low |
| `EncryptedPrefs.kt` | `SecureStorage.swift` | Medium |
| `AccountRepository.kt` | `AccountRepository.swift` | Medium |
| `SettingsRepository.kt` | `SettingsRepository.swift` | Medium |
| `CacheRepository.kt` | `CacheService.swift` | Low |
| `FcmTokenRepository.kt` | `PushTokenRepository.swift` | Low |
| `FcmTokenRepositoryImpl.kt` | (inside `PushTokenRepository.swift`) | Low |
| `WoowFcmService.kt` | `AppDelegate.swift` (delegate methods) | Medium |
| `NotificationHelper.kt` | `NotificationService.swift` | Low |
| `DeepLinkManager.kt` | `DeepLinkManager.swift` | Low |
| `DeepLinkValidator.kt` | `DeepLinkValidator.swift` | Low |
| `MainScreen.kt` | `MainView.swift` + `OdooWebView.swift` + `OdooWebViewCoordinator.swift` | High |
| `MainViewModel.kt` | `MainViewModel.swift` | Low |
| `LoginScreen.kt` | `LoginView.swift` | Low |
| `LoginViewModel.kt` | `LoginViewModel.swift` | Low |
| `AuthViewModel.kt` | `AuthViewModel.swift` | Low |
| `BiometricScreen.kt` | `BiometricView.swift` | Low |
| `PinScreen.kt` | `PinView.swift` | Medium |
| `ConfigScreen.kt` | `ConfigView.swift` | Low |
| `ConfigViewModel.kt` | `ConfigViewModel.swift` | Low |
| `SettingsScreen.kt` | `SettingsView.swift` | Medium |
| `SettingsViewModel.kt` | `SettingsViewModel.swift` | Low |
| `Color.kt` | `WoowColors.swift` | Low |
| `Theme.kt` | `WoowTheme.swift` | Medium |
| `Type.kt` | (SwiftUI .font modifiers -- no separate file) | Low |
| `AppModule.kt` (Hilt DI) | (Protocol injection, no DI framework) | Low |
| `EncryptionHelper.kt` | `PinHasher.swift` | Medium |

---

## Appendix B: Dependency Versions (Recommended)

```
// Swift Package Manager dependencies
// Package.swift or Xcode SPM settings

firebase-ios-sdk: 11.x (latest stable)
KeychainAccess: 4.x (latest stable)

// Apple SDK frameworks (no version pinning needed)
SwiftUI
SwiftData
LocalAuthentication
WebKit
CryptoKit
UserNotifications
os (unified logging)
```

---

## Appendix C: Firebase Console Setup Checklist

1. [ ] Open Firebase Console for project `woow-odoo-de2cb`
2. [ ] Add iOS app: bundle ID `io.woowtech.odoo`
3. [ ] Download `GoogleService-Info.plist`
4. [ ] Go to Apple Developer > Certificates, Identifiers & Profiles > Keys
5. [ ] Create APNs Authentication Key (check "Apple Push Notifications service")
6. [ ] Download `.p8` file, note Key ID and Team ID
7. [ ] Upload `.p8` to Firebase Console > Project Settings > Cloud Messaging > Apple app configuration
8. [ ] Enable "Push Notifications" capability in Xcode
9. [ ] Enable "Background Modes > Remote notifications" in Xcode
10. [ ] Verify: send test push from Firebase Console > Cloud Messaging > Compose notification

---

## Appendix D: Commit Plan (iOS)

Following the Android project's commit convention:

| Commit # | Phase | Description |
|----------|-------|-------------|
| IC01 | P1 | `chore(P1): Xcode project setup with SwiftUI, SPM dependencies, Firebase config` |
| IC02 | P1 | `feat(P1): domain models -- OdooAccount, AuthResult, AppSettings` |
| IC03 | P1 | `feat(P1): SecureStorage (Keychain), OdooAPIClient (URLSession), AppLogger` |
| IC04 | P2 | `feat(P2): login flow -- LoginView, LoginViewModel, AccountRepository` |
| IC05 | P2 | `feat(P2): biometric + PIN auth -- BiometricView, PinView, PinHasher, PBKDF2` |
| IC06 | P2 | `feat(P2): auth navigation -- AuthViewModel, AppRouter, scene phase monitoring` |
| IC07 | P3 | `feat(P3): WKWebView integration -- OdooWebView, Coordinator, cookie sync, OWL fixes` |
| IC08 | P3 | `feat(P3): MainView with toolbar, deep link consumption, loading overlay` |
| IC09 | P4 | `feat(P4): push notifications -- AppDelegate FCM, NotificationService, APNs` |
| IC10 | P4 | `feat(P4): deep link handling -- DeepLinkManager, DeepLinkValidator, PushTokenRepository` |
| IC11 | P5 | `feat(P5): config screen -- ConfigView, account switching, logout` |
| IC12 | P5 | `feat(P5): settings screen -- SettingsView, theme picker, security toggles` |
| IC13 | P5 | `feat(P5): brand color system -- WoowColors, WoowTheme, ColorPickerView` |
| IC14 | P6 | `feat(P6): localization -- en, zh-Hant, zh-Hans string catalogs` |
| IC15 | P7 | `feat(P7): cache management -- CacheService, WKWebsiteDataStore clearing` |
| IC16 | P8 | `test(P8): unit tests -- all ViewModels, repositories, validators, hashers` |
| IC17 | P8 | `test(P8): UI tests -- login flow, auth flow, navigation, settings` |
| IC18 | -- | `chore: TestFlight build configuration, App Store metadata prep` |

---

## Appendix E: Feature Parity Checklist — 100% vs Cannot-Be-100%

### Push Notification Service: FCM (Confirmed)

iOS will use **Firebase Cloud Messaging (FCM)** — NOT raw APNs. FCM wraps APNs on iOS, so:
- Same Firebase project (`woow-odoo-de2cb`) serves both platforms
- Same Odoo module (`woow_fcm_push`) sends to both — `platform` field already supports `ios`
- Same data payload format — FCM bridges it to APNs automatically
- Requires APNs Authentication Key (.p8) uploaded to Firebase Console

### Features That CAN Be Ported 100%

| # | Feature | Android | iOS Equivalent | Parity |
|---|---------|---------|----------------|--------|
| 1 | JSON-RPC authentication | OkHttp | URLSession | **100%** — same API, same payloads |
| 2 | Multi-account storage | Room | SwiftData | **100%** — same data model |
| 3 | Encrypted password storage | EncryptedSharedPreferences | iOS Keychain | **100%** — Keychain is more secure |
| 4 | PBKDF2 PIN hash | javax.crypto | CryptoKit/CommonCrypto | **100%** — same algorithm, cross-platform hashes compatible |
| 5 | Exponential PIN lockout | SystemClock.elapsedRealtime | ProcessInfo.processInfo.systemUptime | **100%** — same logic |
| 6 | FCM push notifications | Firebase Messaging SDK | Firebase Messaging iOS SDK | **100%** — same FCM, bridges to APNs |
| 7 | Notification channel | NotificationChannel | UNNotificationCategory | **100%** — different API, same result |
| 8 | Deep link URL scheme | `woowodoo://open` intent filter | URL scheme in Info.plist | **100%** — same scheme |
| 9 | Deep link URL validation | DeepLinkValidator (java.net.URI) | DeepLinkValidator (Foundation.URL) | **100%** — same logic |
| 10 | Deep link state persistence | DeepLinkManager (StateFlow) | DeepLinkManager (@Published) | **100%** — same pattern |
| 11 | Brand color palette | Color.kt (15 colors) | WoowColors.swift | **100%** — same hex values |
| 12 | Color picker with HEX input | LazyVerticalGrid + OutlinedTextField | LazyVGrid + TextField | **100%** — SwiftUI equivalent |
| 13 | zh-CN + zh-TW + EN strings | values-zh-rCN/strings.xml | zh-Hans.lproj/Localizable.strings | **100%** — copy translations |
| 14 | Cache clearing (WebView) | WebStorage.deleteAllData() | WKWebsiteDataStore.removeData() | **100%** — dedicated API |
| 15 | Same-host WebView restriction | shouldOverrideUrlLoading | decidePolicyFor navigationAction | **100%** — same logic |
| 16 | VISIBILITY_PRIVATE | NotificationCompat.VISIBILITY_PRIVATE | .hiddenPreviewsDeclaration | **100%** |
| 17 | Odoo module shared | woow_fcm_push (Python) | Same module, no change | **100%** |

### Features That CANNOT Be 100% Identical

| # | Feature | Android | iOS Limitation | Parity | Workaround |
|---|---------|---------|----------------|--------|------------|
| **F1** | **In-app language switch** | `AppCompatDelegate.setDefaultLocale` works instantly | iOS has no API to switch app language without restart (pre-iOS 16) or system Settings (iOS 16+) | **80%** | Use custom `Bundle` override — works but requires view refresh. Or require iOS 16+ and use system per-app language setting. |
| **F2** | **Notification grouping** | `setGroup()` with summary notification | iOS groups automatically by `threadIdentifier`, but no programmatic summary notification | **90%** | Set `threadIdentifier` to event_type. iOS auto-groups. No manual summary control. |
| **F3** | **Third-party cookie control** | `setAcceptThirdPartyCookies(false)` | WKWebView blocks 3rd-party cookies by default (ITP). No equivalent toggle. | **100%** (better by default) | No action needed — iOS is more restrictive by default. |
| **F4** | **Background auth invalidation** | `LifecycleEventEffect(ON_STOP)` | `@Environment(\.scenePhase)` `.background` fires later than Android's `onStop` | **95%** | Use `.onChange(of: scenePhase)` — works but timing differs slightly. |
| **F5** | **File upload in WebView** | Custom `onShowFileChooser` with camera+gallery | WKWebView handles `<input type="file">` natively on iOS, but camera access needs `NSCameraUsageDescription` | **95%** | Add `NSCameraUsageDescription` to Info.plist. Camera picker works automatically. |
| **F6** | **Material3 dynamic theming** | `MaterialTheme` with dynamic `primaryColor` | SwiftUI `Color.accentColor` doesn't support runtime dynamic changes easily | **85%** | Use `@AppStorage` + custom `EnvironmentKey` for dynamic primary color. Slightly different from Material3's approach. |
| **F7** | **OkHttp BODY logging guard** | `if (BuildConfig.DEBUG) Level.BODY` | URLSession logging is different — no built-in body interceptor | **90%** | Use `URLProtocol` subclass for debug logging, or `os.Logger` with privacy flags. |
| **F8** | **Biometric prompt UI** | System BiometricPrompt dialog | System LAContext dialog — cannot customize UI | **100%** (same constraint) | Both use system dialog. |

### Decisions Required From You

Please review and answer these before iOS development starts:

| # | Question | Options | Impact |
|---|----------|---------|--------|
| **Q1** | **Minimum iOS version?** | (A) iOS 16 — wider device support, no SwiftData, no per-app language, need Core Data<br>(B) **iOS 17** — SwiftData, per-app language in Settings, newer devices only<br>(C) iOS 18 — latest features, very limited device support | Determines if we use SwiftData vs Core Data, affects ~15% of the code |
| **Q2** | **Apple Developer account — do we have one?** | (A) Yes, existing account<br>(B) No, need to enroll ($99/year) | **Blocks all iOS work** — cannot build for device, cannot create APNs key, cannot submit to App Store |
| **Q3** | **Bundle ID for iOS?** | (A) `io.woowtech.odoo` (same as Android)<br>(B) `io.woowtech.odoo.ios`<br>(C) Other | Affects Firebase config, App Store listing, Keychain access group |
| **Q4** | **In-app language switching approach?** | (A) Custom Bundle override (works on all iOS versions, needs view refresh)<br>(B) System Settings per-app language (iOS 16+, no custom code but user must go to Settings app)<br>(C) Both — custom override + system setting | F1 above — determines UX |
| **Q5** | **iPad support?** | (A) iPhone only for v1.0<br>(B) Universal (iPhone + iPad) from day 1 | iPad adds layout complexity — recommend (A) first |
| **Q6** | **App Store distribution?** | (A) App Store (public)<br>(B) TestFlight only (internal testing)<br>(C) Enterprise distribution (company devices only) | Affects signing, review process, timeline |
| **Q7** | **Do we have brand fonts (.ttf/.otf)?** | (A) Yes, will provide Gira Sans + Outfit<br>(B) No, use system fonts (San Francisco) | Same question as Android — still pending |
| **Q8** | **Separate git repo or monorepo?** | (A) New repo: `Woow_odoo_ios`<br>(B) Subfolder in existing repo: `Woow_odoo_app/ios/`<br>(C) Monorepo with shared docs | Affects CI/CD, code review process |

> **Answer format:** Fill in your choice next to each question. Example: `Q1: B`
Q1: iOS 16
Q2: Yes we have existing account.
Q3: Bundle ID could be the same as android. But I want both system app working without problem , suggest me the best choice.
Q4: B follow system setting lang.
Q5: universal.
Q6: Publish to App store as target. So our architecture should fit their strict policy.
Q7: no.
Q8: separated would be cleaner.
---

## Appendix F: Pre-Development Checklist

Before writing any code, ensure these are ready:

- [ ] Apple Developer Program membership active ($99/year)
- [ ] Bundle ID `io.woowtech.odoo` registered in Apple Developer portal
- [ ] APNs Authentication Key created and uploaded to Firebase
- [ ] `GoogleService-Info.plist` downloaded for iOS app
- [ ] Test Odoo server accessible over HTTPS from development Mac
- [ ] iOS development device available for WKWebView testing (simulators have WKWebView but cookie behavior may differ)
- [ ] Design assets: app icon (1024x1024), launch screen, screenshots for App Store
- [ ] Brand fonts (Gira Sans + Outfit) obtained in `.ttf` or `.otf` format (or confirm system font fallback)
