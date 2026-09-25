# Android 階段 3 review 修正驗證（2026-09-25）

**兩個 P1 已修候選；獨立 review / 安全事件處理仍是門檻，不宣稱合併、部署、出包或送達就緒。** 保留所有原 dirty 候選，未 stage / commit / push。

## 本輪具體修改

- `OdooJsonRpcClient.kt`：明確 `authenticateApporoIsolated` 供 AccountRepository manual/switch；既有 shared authenticate 在有效 SID 成功時更新 jar。isolated / push 不自動發布。保留 WOOW 預設 builder；核准可選 shared test client，所有測試衍生 auth 路徑可封閉。
- `AccountRepository.kt`：selection fence 內先 ensureActive；僅 DAO / credential / cookie 短提交 NonCancellable，失敗還原原 target row、active、credential；網路 / cap / push locks 全在取消保護之外。提交後再次檢查取消。
- 新增12個 JVM 方法：`ApporoManualSessionTest` +2（真 MainViewModel→SessionReauthenticator→API→WebView getter、新 SID/缺 SID，以及非法 URL），`ApporoAccountSelectionTest` +6（可控取消、兩類本地失敗還原、push wait 可取消），`AuthTransportIsolationTest` +4（shared/isolated 未匹配 request 拒絕、兩品牌 UI trim→canonical→mock 登入成功）。late login/switch、缺 SID 零 local mutation、captured cleanup、一次 heal 後重 cap、WOOW 原斷言保留。
- 其餘測試 fixture：`OdooJsonRpcClientTest`、`SessionReauthInterceptorTest`、`ApporoPushTransportTest`、`FcmTokenRepositoryTest`、`FcmTokenEmptyAccountsTest`、`testutil/HermeticHttp.kt`；封閉 fake / 自有 loopback，禁止 proxy，DNS/socket 第二層拒絕。詳見 `review-network-audit.md`。
- `scripts/tests/test_brand_contracts.py` 只擴精確核准的第5 seam test builder、shared/isolated 分支、local commit imports/檢查；其餘 legacy byte / 字串斷言不放寬。

## 安全事件（不能被後續綠燈掩蓋）

第一次未封閉完整 Apporo JVM，既有 `OdooJsonRpcClientTest` 直接把真 demo222 host＋尾空白送底層 API，XML 實際回 INVALID_CREDENTIALS（預期 INVALID_URL）。視為真 host authenticate 嘗試及可解析回應，**不宣稱無連線或無遠端影響**。未查遠端、未作補償性修改。原始 XML/log 與事件摘要只讀保留，SHA 見 `review-network-incident.md` / `review-evidence.sha256`。

父代理核准後才恢復封閉 focused，再批准完整 JVM。為舊測試新增的 raw 尾空白拒絕已撤回，UI trim 邏輯完全未改；原 INVALID_URL/message 斷言改用真正語法非法 `https://bad host/` 並強制 fake transport。

## 實跑結果（本輪 XML，不沿用438）

| 執行 | XML總數 | PASS | FAIL/ERROR | SKIP |
|---|---:|---:|---:|---:|
| 初次相關 focused（舊 mock 入口未更新） | 99 | 97 | 2 | 0 |
| fixture 入口更新後 focused | 99 | 99 | 0 | 0 |
| 初次完整 Apporo（安全事件） | 497 | 493 | 1 | 3 |
| 封閉 API focused（sandbox 前） | 27 | 27 | 0 | 0 |
| sandbox IPv4 focused（MockK attach 被阻） | 27 | 24 | 3 | 0 |
| sandbox＋預載既有 agent focused | 27 | 27 | 0 | 0 |
| 相同防線擴充 focused | 143 | 143 | 0 | 0 |
| **相同防線完整 Apporo** | **502** | **499** | **0** | **3** |
| **相同防線完整 WOOW** | **502** | **499** | **0** | **3** |

兩 flavor 各含 production Kotlin/Hilt 與 unit-test compile。三個 SKIP 都是既有 `BiometricCryptoManagerTest` 真 Keystore encrypt/decrypt/delete 測試；沒有新增 skip。兩 flavor 是相同測試各跑一次，不是1004個獨立場景。

- XML逐份留在 `review-*-xml/`；計數檔 `review-test-results.json`；原始/最終log皆保留。
- `review-compile-initial.log`：首次 Apporo compile 成功。`review-hermetic-focused.log`：新 fixture Dns lambda 編譯失敗，改為 Dns object 後編譯通過，未改行為斷言。
- `review-apporo-final.log`：早期重跑遭120秒tool timeout，無完整結果，不能當 PASS。
- `review-sandbox-focused.log` / `review-sandbox-focused-final.log`：daemon 連線環境失敗，零測試；保留。owned JVM socket顯示IPv6-mapped連線受阻，以IPv4Stack修正；沒有移除sandbox。MockK以既有agent預載，沒有開Unix IPC/下載。
- `review-fixes-offline-final.log`：37 stdlib source checks，0 FAIL / 0 SKIP；只證明 source 契約，不是 runtime。
- `review-diff-check.log`：exit0；`review-staged-files.log`：0 staged。

## 可重跑的完整命令與防線

```sh
python3 -B docs/verification-report/apporo-phase3/review-run-gradle.py :app:testApporoDebugUnitTest
python3 -B docs/verification-report/apporo-phase3/review-run-gradle.py :app:testWoowtechDebugUnitTest
python3 -B -m unittest discover -s scripts/tests -p 'test_brand*.py' -v
git diff --check
git diff --cached --name-only
```

runner 每次以 `sandbox-exec -f review-sandbox.sb` 包住 bash / Gradle；source指定toolchain、清六種proxy env、IPv4Stack；固定 `--offline --no-daemon --max-workers=1 -Dorg.gradle.parallel=false -Porg.gradle.java.installations.auto-download=false` 與 `-I review-fixtures.init.gradle`。既有ByteBuddy預載及synthetic Firebase只供此單元驗證，**這批生成資源不可用於普通安裝**，後續須由父另批真配置重建。未讀真 Firebase/key/password。

socket自測：`sandbox-exec -f review-sandbox.sb python3 -B review-sandbox-selftest.py`：127.0.0.1 / ::1 正向成功、127.0.0.2負向 EPERM。最終 profile 只允許TCP localhost；不是全系統封網、也不宣稱隔絕OS DNS broker。檔案與全部命令參數見 runner、log；SHA 見 `review-evidence.sha256`。

磁碟：初次起跑5.0GiB；擴充focused結束曾降至2.9GiB，依門檻停等父確認，未清cache。恢復後完整job起跑均>3GiB，runner每2秒檢查，Apporo最低4.715GiB、WOOW最低4.705GiB；低於2GiB會終止本job。

## 剩餘門檻

- 兩 P1 修法及測試隔離改動須獨立 review；安全事件由父/擁有者裁定，不以綠燈抹除。
- 未 assemble / install / adb / 簽章 / live scripts / 真推播 / 部署。裝置、真TLS/Odoo/FCM送達、release配置與實機Keystore仍 NOT RUN；原 live gate 不解除。
- local rollback 覆蓋可控DAO/credential失敗與取消；不是跨Room/EncryptedPrefs持久化交易或斷電原子性證明，持續儲存損毀仍可能令rollback失敗。

---

## 前輪候選紀錄（以下「未編譯」只指前輪，不是本輪結果）

基線：`73ad528`；branch：`feat/apporo-platform-brand-layer`。保留階段 2 dirty diff；未 stage／commit／push。這是可供獨立 review 的候選，**尚未編譯，不是可合併／部署／發布或送達保證**。

## 完成範圍

- Apporo account UUID＋完整 HTTPS base URL（port/path）＋DB/username/userId 綁定獨立 session；不讀／寫共用 host jar 的 push session。
- 每次 register/unregister（含 rotation/reconcile）先 cap；version >=2 且含 apporo 才寫；固定品牌、register 驗 echo/version；無 fallback。cap/write 整個操作最多 heal 一次，換 SID 後重查 cap。
- manual auth 原本於 executeRequest 後向 shared jar 反查 SID，已按核准第 5 seam 改 Apporo 單次 isolated response Set-Cookie。缺／空／過期／foreign-domain SID＝session 建立失敗，零帳號／active／credential／jar 覆寫，不借 B cookie、不額外 auth。重用現有三語 error_session，非密碼錯誤。
- 有效 SID 直接交 push；cap 不支援仍登入成功，只記 NOT_CONFIGURED。manual active 提交／UI cookie 發布受同一 selection attempt mutex 保護；push heal 不發布 UI jar。
- register 遇刪除／身分變更停止；captured unregister 可在 local row 移除後用原 SID 清理，原 SID 過期則停止、不得替代帳號認證。本地 logout/remove 仍 best effort、維持原 await 時序。
- Settings 按 active account UUID 選三語本地診斷；ACK 文案明示不等於通知送達。狀態只存記憶體，不含 token／password／server body。
- WOOW legacy auth/switch/unregister 保留；獨立的新 JVM 測試在兩 flavor 均明選 WOOW fixture，不依執行 flavor 偶然變更。

## 本輪實際執行

| 命令 | 結果 |
|---|---|
| `git branch --show-current; git rev-parse --short HEAD` | PASS：指定 branch、73ad528 |
| `python3 -B -m unittest discover -s scripts/tests -p 'test_brand*.py' -v`（首次） | FAIL：37 tests，7 subtest failures／2 test methods；其餘35方法通過。只有 stage2 freeze 與本輪核准改動衝突 |
| 同命令（核准 policy 更正後） | PASS：37 tests，0 fail／0 skip |
| 同命令（逾時恢復、§2.4 最終收斂後） | PASS：37 tests，0 fail／0 skip；19.454s；`offline-final.log` |
| `git diff --check` | PASS，exit 0；`diff-check.log` |
| `git diff --cached --name-only` | PASS：0 staged；`staged-files.log` 空檔 |
| 新 JVM source 的 `@Test` 計數 | 49 新方法（見下）；**只是盤點，不是執行** |

## 新增／更新測試

根目錄 `app/src/test/kotlin/io/woowtech/odoo/`：

| 檔案 | 新方法 | 執行狀態 |
|---|---:|---|
| `data/api/ApporoManualSessionTest.kt` | 5 | NOT RUN |
| `data/repository/ApporoAccountSelectionTest.kt` | 5 | NOT RUN |
| `data/repository/ApporoPushContractTest.kt` | 5 | NOT RUN |
| `data/repository/ApporoPushTransportTest.kt` | 31 | NOT RUN |
| `ui/config/PushRegistrationSettingsTest.kt` | 2 | NOT RUN |
| `ui/login/LoginViewModelTest.kt` | 1 | NOT RUN |

涵蓋：cap 錯／缺零 write、echo 錯不回退、同 host 不同 account/DB/port/path、顯式 cookie 不被 jar 蓋寫、cap/write healing 精確序列／上限、manual reset 只清該帳號、無 SID 零本地變更與 valid SID/cap 缺成對 fixture、late login/switch completion、rotation／reconcile／零帳號 replay／login／logout／remove、captured cleanup、WOOW 無 cap/brand、tenant 回寫、三語 UI account-keyed 狀態。

原有 repository/ViewModel 測試只更新 brand／constructor fixture；`FcmTokenRepositoryTest` 的未隔離網路 setup 改成 fake HTTP，不移除既有斷言。MockWebServer 測試以測試 interceptor 將 fixture HTTPS URL 改送 loopback；不是 TLS integration 證據，且本輪未執行這些 JVM 測試。

## Offline 政策更正（事前核准）

保留 37 方法，無 skip／刪測試。兩個 stage2 凍結方法改為：

1. 僅准 `FcmTokenRepository.kt`、`FcmTokenRepositoryImpl.kt`、`AccountRepository.kt`、`di/AppModule.kt`、`OdooJsonRpcClient.kt` 五個明列 push/session seams。其餘原安全檔繼續 byte-identical。WOOW AccountRepository／auth 原文在剝除精確 Apporo 分支及接線後逐字對照，adapter 靜態單一路徑與新 JVM 行為測試相互對應。
2. 三語全部原 key/value／影響語意的屬性與 placeholder 不變，只允許明列 10 個新 push keys；集合一致、中文文字本地化。不以任意 prefix／任意 extras 放行。

初次 FAIL 保留供 review。歷史 **37 PASS、438 PASS／3 SKIP** 證據未覆寫；不當作本輪 JVM 綠燈。live-script Apporo 拒絕閘門未解除。

## 未跑門檻／殘餘風險

- NOT RUN：`./gradlew assembleWoowtechDebug testWoowtechDebugUnitTest`、`./gradlew assembleApporoDebug testApporoDebugUnitTest`；父代理後續序列建置。新增 Hilt／Kotlin／Compose 接線、49 JVM 方法均尚未編譯驗證。
- NOT RUN／BLOCKED：`scripts/verify-on-device.py`、`scripts/e2e_15_clockin_full.py`、`scripts/e2e-production-test.py`、`scripts/e2e-verification-report.py`；裝置／真 Odoo／真推播／部署未授權，live gate 繼續拒絕。
- 缺真 Odoo 端到端與實機證據；cap/ACK 不代表 central／FCM／通知權限或送達健康；server cap/write 間不得降版。
- session 狀態只在記憶體，冷啟動需要 account-bound 認證；無能力永久 cache。舊 WOOW 的 host-only 模型不在本輪重構。
- review 必須特別核對 selection commit、同 host session 隔離、缺 SID fail-closed、cleanup 例外、重試上限及保留 WOOW 行為。未完成獨立 review，不宣稱無回歸。
- 未讀／輸出真 Firebase ignored 內容。既有 offline Firebase gate 只讓 git 靜默做存在性／差異判斷，Python 不讀內容。

## 日誌 SHA256

- `offline-initial.log`：`19b1fa71dcdd66d765a9de5f828186eafadc2bb533c3f3e7946d201bb8a99f26`
- `offline-policy-rerun.log`：`b3d4ea032b90151c24074714c6599a31c9c75c54d5187f23fb5d3cccf82bb591`
- `offline-final.log`：`c9ec01e4f0fedee09df2017c645ddc8097dae5237e6869440105720f0e64a909`
- `diff-check.log`：`8c83a0dcfebc4bf532bca09474cc581378ddfd5f8589b4f2abf8dcc91ec9e6e7`
- `staged-files.log`：`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
