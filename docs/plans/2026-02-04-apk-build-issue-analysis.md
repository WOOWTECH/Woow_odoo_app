# APK 建置問題分析報告

## 問題摘要

GitHub Actions 建置 APK 時在 "Build Debug APK" 步驟失敗。

## 環境資訊

| 項目 | 值 |
|------|-----|
| Repository | WOOWTECH/Woow_odoo_app |
| Branch | main |
| JDK | 17 (temurin) |
| Gradle | 8.6 |
| AGP | 8.3.0 |
| Kotlin | 1.9.22 |
| minSdk | 29 |
| targetSdk | 34 |

## 建置歷史

| Run ID | 結果 | 備註 |
|--------|------|------|
| 21671268098 | ✅ 成功 | APK 建置成功但 artifact 已過期 |
| 21671763215 | ❌ 失敗 | Build Debug APK 失敗 |
| 21672252341 | ✅ 成功 | 但 APK 未上傳到 release |
| 21672642767 | ❌ 失敗 | Build Debug APK 失敗 |

## 可能原因

### 1. Gradle Cache 問題
- GitHub Actions 的 Gradle cache 可能損壞
- 解決方案：清除 cache 或禁用 cache

### 2. 依賴下載失敗
- Maven 或 Google 倉庫暫時不可用
- 網路超時

### 3. 記憶體不足
- GitHub Actions runner 記憶體限制
- Gradle daemon 記憶體不足

### 4. Compose 編譯器版本不相容
- Kotlin 1.9.22 + Compose BOM 2024.02.00 可能有相容性問題

## 替代方案

### 方案 A：本地建置 APK
在本地電腦使用 Android Studio 建置：
1. 開啟 Android Studio
2. File → Open → 選擇專案
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

### 方案 B：使用 GitHub Codespaces
1. 在 GitHub repo 頁面點擊 "Code" → "Codespaces"
2. 建立新的 Codespace
3. 執行 `./gradlew assembleDebug`
4. 下載生成的 APK

### 方案 C：修復 GitHub Actions
1. 禁用 Gradle cache
2. 增加 JVM heap size
3. 分步驟執行建置

## 建議的 Workflow 修復

```yaml
- name: Build Debug APK
  run: |
    ./gradlew clean
    ./gradlew assembleDebug --no-daemon --max-workers=2
  env:
    GRADLE_OPTS: "-Xmx4g -XX:MaxMetaspaceSize=512m"
```

## 下一步行動

1. [ ] 嘗試本地建置確認程式碼無誤
2. [ ] 修改 GitHub Actions workflow
3. [ ] 如果持續失敗，使用 AppCenter 或 Firebase App Distribution
