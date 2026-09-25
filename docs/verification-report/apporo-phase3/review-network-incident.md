# 本機測試安全事件（不可視為未連線）

- 失敗測試：`OdooJsonRpcClientTest.Given host with trailing space when authenticate then returns INVALID_URL without raw exception text()`。
- 直接呼叫底層 API，輸入真 demo222 HTTPS host 加尾端空白、demo DB、測試用帳密；不是 UI 正規化流程。Apporo HttpUrl parser trim 後進入未 mock 的 authenticate 網路路徑。
- 初次 XML：預期 INVALID_URL，實際 INVALID_CREDENTIALS，1.658 秒。結合真 client 路徑，視為對真 host 的 authenticate 嘗試及可解析回應；無 request capture，不宣稱沒連線、沒遠端影響或已確認遠端狀態。
- 未再查遠端、未作補償寫入。已向父代理回報。全 suite 暫停，封閉 fixture 核對後另待父確認。
- 首次命令：`source ~/.local/share/woow-android-toolchain/env.sh; ./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.parallel=false -Porg.gradle.java.installations.auto-download=false -I docs/verification-report/apporo-phase3/review-fixtures.init.gradle :app:testApporoDebugUnitTest`。Gradle offline 不會封鎖測試自身 HTTP。
- 首次 497 tests：493 PASS / 1 FAIL / 3 SKIP，不是驗證通過。
- 後續未隔離修正版全 suite 命令在 120 秒 tool timeout 終止，無完整 XML/BUILD 結論；父 steering 後 ps 查無 Gradle/Test Executor。不得算 PASS。
- 父核准處理：撤回為測試新增的 raw 尾空白拒絕；既有 INVALID_URL 斷言使用明確語法非法 fixture；第5 seam 可選 shared client 注入；mock dispatcher + DNS/connect 第二層阻擋；兩品牌 UI 正規化成功測試；先 focused 後等批准。

## 原始證據（保留，不覆寫）
- `review-apporo-full.log`：mtime UTC `2026-09-25T03:21:56.872371+00:00`，SHA256 `969102a1e735d79117e18493314afc6f77b1e0d72753f28a801195b50b13fefa`。
- `TEST-io.woowtech.odoo.data.api.OdooJsonRpcClientTest.xml`：mtime UTC `2026-09-25T03:22:44.558897+00:00`，SHA256 `f8ced186b54e01becd412ca08d334fcbffa1a788d389c6bb50c65e0590d6242b`。
