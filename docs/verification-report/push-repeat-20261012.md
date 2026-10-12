# 同一對話重複推播點擊修正候選（2026-10-12）

## 問題與修正

主協調者在 Pixel 的 Play vc6 實機重現：第一次點 Discuss 私訊通知可進入對話；返回 Inbox 後，同一對話的新通知送達，但點擊仍停在 Inbox。後端確認兩次訊息各送出一次。

`OdooWebView` 的 `appliedDeepLinkUrl` 在同帳號 WebView 存續期间保留，將後續相同 URL 的新通知也當成重複事件。移除這個 URL 去重，改以既有 account-bound queue 的單次 consume 回傳值作為導頁條件。page-finished 與 warm update 都只有取得有效 URL 才載入；已消耗／過期／帳號不符回傳 null 時不導頁。保留 target generation、host/load gate、tenant routing、URL validator 與 cookie isolation。

改動範圍：`MainScreen.kt`、兩個既有 Robolectric 測試 fixture。本候選未變更版本、簽章、推播 transport 或後端。

## TDD 與驗證

新增 `OdooWebViewSameHostAccountSwitchTest` 回歸：同一 WebView 接收 channel 10 URL → 返回 Inbox → 再接收完全相同 URL，應再次載入 channel 並消耗通知。它執行真正的 Compose `OdooWebView` 配合 Robolectric ShadowWebView，不只是測試 URL 字串 helper。

| 驗證 | 結果 |
|---|---|
| 修正前 Apporo 該 suite | 11 tests、1 failure，新增測試 expected channel URL / actual Inbox URL |
| 修正後 Apporo 導頁／隔離相關 suite | 14 suites、181 tests、0 failures/errors/skips |
| 修正後 WOOW 同範圍 suite | 14 suites、181 tests、0 failures/errors/skips |
| `test_brand*.py` | 53 tests PASS |
| `git diff --check` | PASS |

Gradle 透過 `heavy.sh run apporo-push-repeat`，offline、no-daemon、單 worker、Kotlin in-process、1536 MB heap，build root 使用 `.tooling/build-internal`。綠測 5m37s，2026-10-12 14:53:47（Asia/Taipei）完成並釋放 heavy lock。

綠測任務（兩個 task 各使用相同 filters）：

```text
testApporoDebugUnitTest / testWoowtechDebugUnitTest
--tests '*OdooWebView*Test'
--tests '*DeepLink*Test'
--tests '*PushTap*Test'
--tests '*ExternalLinkIntakeTest'
```

本機紀錄：`/tmp/apporo-push-repeat-red.log`、`/tmp/apporo-push-repeat-green.log`、`/tmp/apporo-push-repeat-brand.log`；XML 位於 `.tooling/build-internal/WoowTechOdoo/app/test-results/` 對應 task。

主協調者於本輪回報獨立審查無阻擋發現。本收據只證明上述候選測試，未宣稱完整 suite／候選實機／正式上架驗收完成。沒有建置 release、安裝、上傳或發布。

## 候選實機驗證與簽章

下一次取得建置授權後，仍須經 heavy.sh 包裝 `bundleApporoRelease`／`assembleApporoRelease`；Play 上傳需遞增 Apporo versionCode，且另取擁有者同意。重複通知驗證為既有推播點擊 E2E 的延伸：同一對話第一次通知 → Inbox → 同一對話第二次通知，兩次皆須顯示最新訊息。

vc6 收據中的本機 APK 使用 upload key，不能假設與 Play 安裝版本的 app-signing key 相同。先比對簽章，再決定是否能原地安裝；簽章不同不能用卸載／清除資料繞過。`.debug` 包可並存，但其驗證不能代替正式簽章候選驗收。
