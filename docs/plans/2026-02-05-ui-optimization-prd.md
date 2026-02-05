# WoowTech Odoo App - UI/UX 優化 PRD

## 問題摘要

用戶回報以下問題：
1. **指紋辨識死迴圈** - 開啟指紋辨識後會卡在認證畫面無法退出
2. **文字可讀性差** - 前端欄位跟文字的呈現使用上看不清楚
3. **整體 UI/UX 需要優化** - 視覺設計需要更精緻

## 詳細分析

### 🔴 Critical Issue: 指紋辨識死迴圈

**問題位置：** `BiometricScreen.kt`

**根本原因：**
```kotlin
LaunchedEffect(Unit) {
    if (settings.biometricEnabled && canUseBiometric) {
        showBiometricPrompt()  // 自動觸發指紋辨識
    }
}
```

1. 畫面載入時自動觸發指紋辨識
2. 如果用戶取消或失敗，只顯示錯誤訊息
3. 沒有退出機制 - 用戶點擊 "Unlock" 又觸發指紋辨識
4. 如果沒有設定 PIN，用戶完全無法退出

**解決方案：**
- 添加「跳過」按鈕，允許用戶暫時跳過指紋辨識
- 失敗次數限制後自動導向 PIN 輸入
- 提供明確的退出/取消選項

---

### 🟠 High Priority: 文字與欄位可讀性

**問題 1: PIN 輸入畫面**
- 空心圓點使用 `outlineVariant` 顏色，在深色模式下幾乎看不見
- 數字鍵盤邊框太淡
- 退格鍵使用 Unicode 字元「⌫」而非圖標

**問題 2: 登入畫面**
- Logo "W" 字母在自訂主題色時可能對比度不足
- 文字顏色使用 `onPrimary`，若主題色太淺會看不清

**問題 3: 設定畫面**
- 停用狀態的項目只有 38% 透明度，非常難以辨識
- 顏色選擇器方塊太小 (24dp)

---

### 🟡 Medium Priority: 視覺設計優化

**當前問題：**
- 使用預設 Material Design 3 字體，缺乏品牌特色
- 動畫效果簡單，缺乏細緻的互動回饋
- 整體配色較為保守，缺乏視覺衝擊力

**設計方向：**
採用 **「科技專業 + 現代簡潔」** 美學風格：
- 主色調：WoowTech Blue (#6183FC) 搭配深色系背景
- 字體：引入更有特色的字體家族
- 動效：添加優雅的頁面轉場和按鈕反饋
- 對比度：確保所有文字符合 WCAG AA 標準 (4.5:1)

---

## 修復計畫

### Phase 1: 緊急修復 (指紋死迴圈)

**檔案：** `BiometricScreen.kt`

修改內容：
1. 添加失敗計數器，3次失敗後自動切換到 PIN
2. 添加「稍後再說」按鈕
3. 取消時直接導向 PIN 輸入或主畫面

### Phase 2: 可讀性優化

**檔案：** `Color.kt`, `PinScreen.kt`, `LoginScreen.kt`

修改內容：
1. 增強 PIN 圓點對比度
2. 數字鍵盤使用更明顯的邊框
3. 退格鍵改用 Material Icon
4. 停用狀態透明度從 38% 提升到 60%

### Phase 3: 視覺設計升級

**檔案：** `Theme.kt`, `Type.kt`, `Color.kt`, 各 Screen 檔案

修改內容：
1. 優化色彩對比度
2. 添加漸層背景效果
3. 改進按鈕樣式和互動效果
4. 統一視覺語言

---

## 具體修改清單

### BiometricScreen.kt
- [ ] 添加 `failureCount` 狀態變數
- [ ] 3次失敗後自動跳轉 PIN
- [ ] 添加「跳過」按鈕
- [ ] 改進錯誤訊息顯示

### PinScreen.kt
- [ ] PIN 圓點改用更高對比度的顏色
- [ ] 數字鍵盤添加更明顯的邊框和背景
- [ ] 退格鍵改用 `Icons.AutoMirrored.Filled.Backspace`
- [ ] 添加按鍵按下效果

### Color.kt
- [ ] 添加高對比度變體顏色
- [ ] PIN 專用顏色定義
- [ ] 確保深色模式可讀性

### LoginScreen.kt
- [ ] Logo 區域添加對比度保護
- [ ] 文字添加陰影或背景

### SettingsScreen.kt
- [ ] 停用狀態透明度優化
- [ ] 顏色選擇器放大

---

## 預期成果

1. **指紋辨識** - 用戶可以正常使用或跳過，不會卡住
2. **文字可讀性** - 所有文字在任何主題下都清晰可見
3. **整體體驗** - 更現代、專業的視覺設計

---

## 技術參考

- Material Design 3 Color System
- WCAG 2.1 AA Contrast Requirements (4.5:1)
- Android Biometric Authentication Best Practices
