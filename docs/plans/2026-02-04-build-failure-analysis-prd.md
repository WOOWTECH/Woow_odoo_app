# WoowTech Odoo App - 建置失敗分析 PRD

## 問題摘要

GitHub Actions 建置 APK 時在 "Build Debug APK" 步驟持續失敗。

## 分析結果

### 檢查範圍
- 30 個 Kotlin 源碼檔案
- 6 個 Gradle 配置檔案
- XML 資源檔案（strings, themes, colors, manifest）
- 依賴版本相容性

### 主要發現

#### 🔴 高優先級問題

**1. Kotlin Compose Compiler Plugin 配置問題**

| 項目 | 值 |
|------|-----|
| 檔案 | `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts` |
| 問題 | 使用 `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.0+ 專用) 配合 Kotlin 1.9.22 |
| 影響 | **這是導致建置失敗的根本原因** |

**根本原因：**
`org.jetbrains.kotlin.plugin.compose` 插件**只適用於 Kotlin 2.0+**，不支援 Kotlin 1.9.x。

參考資料：[Kotlin Compose Compiler Migration Guide](https://kotlinlang.org/docs/compose-compiler-migration-guide.html)

**最終解決方案：**
升級到 Kotlin 2.0.21，因為：
1. `kotlin.compose` plugin 是為 Kotlin 2.0+ 設計的
2. Compose compiler 版本會自動匹配 Kotlin 版本
3. 不需要手動配置 `kotlinCompilerExtensionVersion`

```toml
# gradle/libs.versions.toml
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
```

#### 🟡 中優先級問題

**2. Alpha 版本依賴**

| 依賴 | 目前版本 | 狀態 |
|------|----------|------|
| security-crypto | 1.1.0-alpha06 | 保留 (穩定版缺少必要功能) |
| biometric | 1.2.0-alpha05 | 保留 (穩定版缺少必要功能) |

**3. 無用的權限宣告**
- `WRITE_EXTERNAL_STORAGE` 權限設定 `maxSdkVersion="28"`
- 但 `minSdk = 29`，此權限永遠不會被使用

#### 🟢 低優先級問題

**4. 未使用的 Import**
- `BiometricScreen.kt`: `Image`, `painterResource`
- `SettingsScreen.kt`: `Slider`, 多個未使用的 Icons
- `AuthViewModel.kt`: `launch`

**5. 缺少 PIN 設定對話框**
- `SettingsScreen.kt` 中 `showPinSetup` 狀態被設定但沒有對應的 UI 對話框

### 已驗證正確的項目 ✅

- 所有 R.string 資源引用（71 個）
- 所有 R.drawable 資源引用
- 所有 XML 資源配置
- Room/Hilt/Compose 註解
- Gradle 配置結構

## 修復計畫

### 步驟 1：升級到 Kotlin 2.0.21
```toml
# gradle/libs.versions.toml
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
```

### 步驟 2：保留 kotlin.compose plugin
由於使用 Kotlin 2.0+，`org.jetbrains.kotlin.plugin.compose` 現在是正確的配置方式。

### 步驟 3：移除 composeOptions（如果存在）
Kotlin 2.0+ 不需要手動指定 `kotlinCompilerExtensionVersion`。

## 預期結果

升級到 Kotlin 2.0.21 後，GitHub Actions 建置應該能夠成功完成。

## 版本相容性對照表

| Kotlin | Compose Compiler | KSP | Compose BOM |
|--------|------------------|-----|-------------|
| 1.9.22 | 1.5.10 | 1.9.22-1.0.17 | 2024.02.00 |
| 1.9.23 | 1.5.11 | 1.9.23-1.0.20 | 2024.03.00 |
| 2.0.0 | 內建 (2.0.0) | 2.0.0-1.0.21 | 2024.04.00+ |
| 2.0.21 | 內建 (2.0.21) | 2.0.21-1.0.28 | 2024.02.00+ |

## 參考資料

- [Compose to Kotlin Compatibility Map](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)
- [Kotlin Compose Compiler Migration Guide](https://kotlinlang.org/docs/compose-compiler-migration-guide.html)
- [Jetpack Compose compiler moving to Kotlin repository](https://android-developers.googleblog.com/2024/04/jetpack-compose-compiler-moving-to-kotlin-repository.html)
