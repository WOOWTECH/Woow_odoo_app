# PRD: WebView File Upload Support (v1.0.15)

## Problem Statement

在 WoowTech Odoo App 中，使用者無法透過 WebView 上傳檔案。當點擊 Odoo 的附件按鈕時，會顯示選單（Camera、Photo/Video Library、Files），但選擇後無法正常運作。

**原因**：目前的 `WebChromeClient` 沒有實作 `onShowFileChooser()` 方法。

## Requirements

### Functional Requirements

1. **支援所有檔案來源**：
   - 相機拍照
   - 相簿選擇圖片/影片
   - 檔案管理器選擇文件

2. **支援多選**：允許一次選擇多個檔案

3. **MIME 類型支援**：根據網頁請求的 `accept` 屬性過濾檔案類型

### Technical Requirements

1. **實作 `WebChromeClient.onShowFileChooser()`**
2. **使用 `ActivityResultLauncher` 處理結果**（取代已棄用的 `onActivityResult`）
3. **處理 Android 權限**：
   - `CAMERA` - 拍照功能
   - `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES` - 讀取相簿
4. **正確處理取消操作**：必須呼叫 `filePathCallback.onReceiveValue(null)`

## Design

### Architecture

```
MainScreen.kt
├── fileChooserLauncher (ActivityResultLauncher)
├── cameraLauncher (ActivityResultLauncher)
├── filePathCallback (ValueCallback<Array<Uri>>?)
├── cameraPhotoUri (Uri?)
└── WebChromeClient
    └── onShowFileChooser() → 顯示選擇器 Intent
```

### Implementation Flow

1. 網頁觸發檔案選擇 → `onShowFileChooser()` 被呼叫
2. 建立 chooser Intent（包含相機和檔案選項）
3. 啟動 `fileChooserLauncher`
4. 使用者選擇檔案或拍照
5. `ActivityResult` 回傳 → 呼叫 `filePathCallback.onReceiveValue(uris)`
6. WebView 收到檔案 URI，上傳到伺服器

### Key Code Components

```kotlin
// 1. State for file callback
var filePathCallback: ValueCallback<Array<Uri>>? = null
var cameraPhotoUri: Uri? = null

// 2. Activity Result Launcher
val fileChooserLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
) { result ->
    val uris = parseFileChooserResult(result)
    filePathCallback?.onReceiveValue(uris)
    filePathCallback = null
}

// 3. WebChromeClient override
override fun onShowFileChooser(
    webView: WebView?,
    filePathCallback: ValueCallback<Array<Uri>>?,
    fileChooserParams: FileChooserParams?
): Boolean {
    this@MainScreen.filePathCallback?.onReceiveValue(null)
    this@MainScreen.filePathCallback = filePathCallback

    val intent = createFileChooserIntent(fileChooserParams)
    fileChooserLauncher.launch(intent)
    return true
}
```

## Testing

1. **圖片上傳**：在 Discuss 中發送圖片附件
2. **相機拍照**：使用相機拍照後上傳
3. **文件上傳**：上傳 PDF 或其他文件
4. **多選測試**：一次選擇多個檔案
5. **取消測試**：選擇後按返回，確保不會卡住

## Version

- **Version**: 1.0.15
- **Priority**: High
- **Estimated Effort**: 2-3 hours
