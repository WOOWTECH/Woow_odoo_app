# Implementation_Plan — 階段 2 Android 品牌層

狀態：本機候選，未完整驗證、不可發布或 commit 產品碼。
基線：73ad528；分支 feat/apporo-platform-brand-layer。

## 範圍與順序
1. brand dimension：woowtech/apporo；namespace 保持，Apporo 1.0/1、正式/debug applicationId/scheme 分離。
2. 單一非 Compose AppBrand provider、既有 UI/設定/缺值預設引用；完整三語資源及各 density 白底 mark/logo overlays。WOOW 輸出與保存偏好不變。
3. Firebase 嚴格 variant 選檔；Apporo 禁 root fallback，正式缺 Firebase/獨立簽章 fail-fast。不產生或讀出金鑰、不偽造配置。
4. 成對契約測試、脚本 variant/package/操作授權閘門、CI build/test matrix，移除自動 publish。
5. stdlib 離線驗證、保留未 staged diff，交獨立 reviewer。後續若准許 commit，依配置/provider、UI/assets、測試/工具分責任拆分，遵守 15 檔/1000 LOC cap。

## 已核准補足
現 Theme.kt 沒有衍生演算法，2026-09-24 主代理核准：只對 Apporo 以 RGB deterministic 黑/白混色推導色階；WOOW 所有角色保持原值。primary 白字、container/secondary 有文字配對 WCAG >=4.5。比例/hex/前景記錄於 provider 與契約測試。

## 不做
不改 FCM register/unregister/capabilities、AccountRepository、外部 intent.data 導頁、登入正規化、plugin/central/aiot。
> 2026-09-29 註：其中「外部 intent.data 導頁」已由擁有者 2026-09-29 核准改為支援（`<scheme>://open?url=…`，對齊 iOS、兩品牌共用；見 RELEASE-MASTER-PLAN「擁有者決定（2026-09-29）」）。實作為 `data/push/ExternalLinkIntake.kt`＋MainActivity 核准 seam，品牌契約以 `EXTERNAL_LINK_MAIN_ACTIVITY` 列舉差異、不刪既有斷言；本段其餘「不做」項目不變。APP_BRAND 只作本機品牌識別，Apporo 推播品牌協定尚未實作，不能拿此候選登入真環境宣稱可用。
主代理因磁碟僅約 5.4GiB 暫緩 Gradle 重型建置，並非擁有者明文禁止所有 build；本輪仍不跑 Gradle。禁止裝置/adb/網路、不下載、不部署、不 push、不操作網站商店、不產生金鑰。Firebase client 配置只選既有路徑，無 Apporo 真配置時必須阻擋出包。

## 2026-09-24 P1 修正輪
- 已接受 finding：CI 預設 shallow checkout 無 `73ad528` 歷史物件，品牌契約會在 build 前失敗。
- `.github/workflows/build.yml` 的 checkout 設 `fetch-depth: 0`；基線斷言全部保留，不 skip。
- 增加 3 條 stdlib 契約：checkout 步驟/順序、必需基線物件、本機 file:// 乾淨 shallow/full checkout 正反例。fixture 僅兩筆合成提交，不複製產品或真 Firebase 配置，僅清除自建暫存目錄。
- 主代理核准 Firebase 未變更測試改由 Git 內部比對、僅取 exit status；先確認基線物件及目前 tracked 檔存在，不讀入 Python 或印本文。
- 實際全套 36 PASS / 0 FAIL / 0 SKIP / 0 BLOCKED（原 33 + 新增 3）。遠端 GHA 未執行；不把本機 fixture 當遠端 CI PASS。
- 本輪不 stage/commit/push；既有未提交產品 diff 保留。詳見 `docs/verification-report/apporo-phase2/validation.md` 的 P1 disposition 與命令證據。
