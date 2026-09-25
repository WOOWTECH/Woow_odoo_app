# Test_Plan — 階段 2 Android

所有產品碼為未完整驗證候選。不得以靜態測試等同 build/單元/裝置驗收。

## 本輪允許
- Python stdlib unittest：B01 身分/variant/Firebase 拒絕缺檔錯品牌錯 package；B02 三語 overlay、各 density 及 PNG hash/尺寸；B03 provider 呼叫與禁止硬編碼成對契約；WCAG >=4.5、WOOW 原色精確保持。
- B04 保護 seam 與基線 git 比對（FCM/通知路由/MainActivity/登入正規化）；腳本 AST、XML parse、git diff --check、no staged files。
- 新增 JVM AppBrandTest；適配 FixedBrandThemeTest/CompliancePageLinksTest；本輪只稽核來源，執行 BLOCKED。

## P1 checkout 回歸（2026-09-24 已執行）
- `BrandCheckoutContracts.test_ci_checkout_fetches_full_history_before_contracts`：只在 checkout 步驟內斷言 `fetch-depth: 0`，且 checkout 先於契約測試。
- `BrandCheckoutContracts.test_required_baseline_commit_and_source_objects_exist`：`git cat-file -e` 確認 `73ad528` commit、資源 tree、lockfile、偏好來源物件存在；缺失即 FAIL，不 skip。
- `BrandCheckoutContracts.test_local_clean_checkout_full_history_restores_baseline_show_and_tree`：兩筆合成提交的 file:// 來源，depth=1 時歷史 `git show`/`ls-tree` 必須失敗；完整乾淨 checkout 必須成功。限制 Git protocol 為 file，無網路/真 Firebase/產品提交。
- 既有 Firebase 未變更測試改為先檢查基線物件、目前 tracked 檔及路徑存在，再用 `git --no-optional-locks diff --quiet --no-ext-diff --no-textconv 73ad528 -- app/google-services.json`；只接受 exit 0，不讀入 Python 或輸出配置內容。此必要秘密最小存取調整經主代理核准。
- 命令 `python3 -B -m unittest discover -s scripts/tests -p 'test_brand*.py' -v`：原 33 + 新增 3 = **36 PASS / 0 FAIL / 0 SKIP / 0 BLOCKED**。遠端 GitHub Actions 未跑，仍 BLOCKED；fixture 不替代 GHA/Gradle 驗證。

## 後續必跑（本輪全部 BLOCKED，未執行不計 PASS）
|套件/門檻|原因/追蹤|
|--|--|
|四 variant assemble + 兩品牌 debug unit|主代理因磁碟約 5.4GiB 暫緩 Gradle 重型建置，本輪仍不執行；非擁有者全面禁 build；B01/B03/B04/B05|
|scripts/verify-on-device.py|禁止裝置/網路/寫入；V01–V26，尤其通知/links/theme/語系|
|scripts/e2e_15_clockin_full.py|禁止定位/裝置/Odoo 寫入；E2E-15|
|scripts/e2e-production-test.py|禁止 FCM/裝置/Odoo 寫入；既有 E2E IDs|
|scripts/e2e_hprime_android.py + chaos|禁止網路/故障注入；既有 H′ IDs|
|scripts/e2e-verification-report.py|此 reporter 本身操作裝置/網路，非純離線；前置證據未產生|
|Apporo 真 Firebase 與簽章、APK 資源/manifest/signer 檢查|尚未建立/批准；B01/B05|
|三語 light/dark、白底 adaptive/round 各裝置 launcher 視覺|B02 @OnDevice：Launcher mask/OEM splash 真呈現需實機；本輪 PNG/XML 離線只覆蓋形狀|

Apporo register/unregister 品牌契約屬階段 3 未做；禁止用品牌層測試替代 P01–P07 推播驗收。六個法遵頁 HTTP/信箱流程屬 W01 待另批，不呼叫網站。
