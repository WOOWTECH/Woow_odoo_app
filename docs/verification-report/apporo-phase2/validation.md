# Android 階段 2 本機候選 — 未完整驗證

基線/目前 HEAD：`73ad528`；分支 `feat/apporo-platform-brand-layer`。未 commit、未 stage。
只修改 Android 隔離工作目錄；未執行 Gradle、裝置/adb、網路、Odoo 登入、部署、商店或 push。未新增真 Firebase 配置或產生簽章金鑰。

## 前輪驗證紀錄（保留；P1 修正輪結果見下節）

|命令/範圍|PASS|FAIL|BLOCKED|
|--|--:|--:|--:|
|`python3 -B -m unittest discover -s scripts/tests -p 'test_brand*.py' -v`|33|0|0|
|Python AST（11 檔，不 import live scripts；亦含在上列契約）|11|0|0|
|`git diff --check`|1|0|0|
|`git diff --cached --quiet`|1|0|0|
|GoogleServicesTask `javap` API 核對|1|0|0|
|缺 Apporo release client：validator CLI 拒絕（預期 exit 1，外層檢查 PASS）|1|0|0|
|四 variant assemble|0|0|4|
|兩品牌 debug JVM 全套 unit|0|0|2|
|六個 live scripts（V / GPS / production / H′ / chaos / reporter）|0|0|6|

上列不同層級有重疊，不相加當測試總數。最後契約測試執行 33 tests / OK，19.047 秒；早期版為 32 tests / OK，後加入 adaptive safe-circle 檢查再跑全套。輸出留於同目錄 `offline-contracts.log`（依既有規則 ignored）。新增 JVM AppBrandTest 5 個測試、FixedBrandThemeTest 新增 2 個、CompliancePageLinksTest 適配 4 個既有測試；全部 JVM **未執行**。

素材以 `python3 -B scripts/generate_apporo_assets.py --source-dir <唯讀核准 assets 路徑>` 生成；27 PNG，白底不透明、hash/尺寸/三語/各 density 檢查全通過。原圖不裁切不改色，adaptive artwork 60/108 方框，非白像素限定 66dp safe circle。manifest：`docs/plans/2026-09-24-apporo-assets.json`。唯讀檢視 legacy launcher PNG 確認白底 mark；此非實機截圖/視覺驗收。

Google Services 4.4.2 本機 cache bytecode 為 `Property<Collection<File>> getGoogleServicesJsonFiles()`，所以 Gradle 使用 `.set(listOf(firebaseConfig))`，不是 `.setFrom`。這只是 API 簽名核對，**不是 Gradle DSL 編譯 PASS**。

## 色彩與行為

Apporo 主色/secondary `#8B6B24`、前景白：WCAG **4.969:1**。
每個 RGB channel 以 `round(primary*(1-f)+target*f)` 混色：
- Light container：白 85% → `#EEE9DE`；前景黑 74% → `#241C09`，**13.932:1**。
- Dark container/dark alias：黑 45% → `#4C3B14`；前景 `#EEE9DE`，**8.927:1**。
- Light alias 同 light container。WOOW 全部原角色值保留。

這是主代理明確核准的 spec 落差補足（原碼沒有衍生演算法）。登入前固定主色和 container/前景成對；登入後 primary 仍尊重使用者已存選色。只改缺值預設，無資料 migration。

## 保護與風險

- FCM register/unregister、AccountRepository、WoowOdooApp、WoowFcmService、通知 router/validator、MainActivity、ServerUrlInput、AppModule 與基線逐檔 byte 比對不變。原 main 資源只核對 hash；P1 修正輪起 Firebase client 只由 Git 內部比較並回傳 exit status，不將本文讀入 Python，不印 API key。
- Apporo 真 Firebase 與 upload key 尚未建立；正式缺任一輸入不能出包。debug 亦要求自己的 client。WOOW 只保留既有 root client 路徑，不複製到 Apporo。
- 簽章隔離目前是配置/路徑拒絕；真 signer 公開指紋、APK/AAB、merged manifests/resource resolution 仍需批准後驗證。不得宣稱已證明簽章或推播可用。
- Apporo 階段 3 品牌推播協定尚未實作，本候選不可投入真環境；live scripts 明確拒絕 Apporo，不能靠 client config 讓舊後端默默註冊 WOOW。
- CI 改為兩 flavor debug build/unit/internal artifact，Apporo secret 未配置時明確 BLOCKED，不自動 release/publish。
- 原 release dependency lock 163 entries 只映射到兩個 flavor releaseRuntimeClasspath，版本不变；Gradle 重新解析/鎖定驗證仍 BLOCKED。
- 重型完整測試、真配置/簽章、網站六頁及信箱流程、stage-3 安全契約仍未完成；首輪獨立 review 已交付 P1，修正後覆核待進行；不可 merge/發布。未決產品選擇：無；後續資源實際 ID 與建立/測試授權須另批。

## P1 修正輪驗證與 disposition（2026-09-24）

### 範圍與授權
- HEAD 仍為 `73ad5288a8a7b24b30c0044394e14beb328b525c`，Android 隔離 worktree／`feat/apporo-platform-brand-layer`。保留既有未提交產品 diff，未 stage/commit/push 產品或計畫，未修改其他 repo/工作線。
- 主代理因預檢磁碟僅約 **5.4GiB 暫緩 Gradle 重型建置**，不是擁有者明文禁止所有 build；本輪仍不執行 Gradle、不下載或清理他人檔案。網路/雲端/商店/装置/adb/Odoo/legacy live scripts 仍禁止。
- 原 33 條內 Firebase 比對曾讀本文計 hash；本輪先向主代理取得窄範圍批准，改為 `cat-file -e`、`ls-files --error-unmatch`、路徑存在性及 `diff --quiet --no-ext-diff --no-textconv` exit status。Git 內部仍讀取以比對，但 Python 不讀入、報告不輸出本文；任何非零狀態都 FAIL，未移除未變更門檻。

### Review finding disposition
|Finding|處置|證據|仍待驗證|
|--|--|--|--|
|P1：GHA shallow checkout 缺 `73ad528`，兩 matrix job 的基線契約先失敗|已修候選：checkout `with: fetch-depth: 0`，未 skip 基線測試|3 條新增 `BrandCheckoutContracts` 全 PASS；既有 33 條全 PASS|read-only reviewer 覆核；遠端 GHA 未跑，不能宣稱 CI 通過|

### 實際命令／結果

```sh
python3 -B -m unittest discover -s scripts/tests -p 'test_brand*.py' -v > docs/verification-report/apporo-phase2/fix-round1-contracts.log 2>&1
git diff --check
git diff --cached --quiet
```

|本輪範圍|PASS|FAIL|SKIP|BLOCKED|
|--|--:|--:|--:|--:|
|stdlib suite（原 33 + 新增 3）|36|0|0|0|
|`git diff --check`（exit 0）|1|0|0|0|
|`git diff --cached --quiet`（exit 0，無 staged diff）|1|0|0|0|
|四 variant assemble（未執行）|0|0|0|4|
|兩品牌 debug JVM suites（未執行）|0|0|0|2|
|六個 live scripts（未執行）|0|0|0|6|
|遠端 GHA 兩 matrix jobs（未執行）|0|0|0|2|

全套 36 tests / OK / **6.805 秒**；原始逐測試輸出：`fix-round1-contracts.log`。命令檢查與 suite 不合併計測試條數；JVM/遠端CI/裝置的 BLOCKED 數字是未執行入口數，非測試 case 數。原 `offline-contracts.log` 保留作前輪紀錄。本輪未重跑前輪 javap、素材生成或 validator CLI，不將前輪結果重算本輪 PASS。

### 本機乾淨 checkout 證據界限
新增 fixture 在 stdlib `TemporaryDirectory` 中 `git init --template=`，只建立 `protected.txt` 與 `candidate.txt` 兩筆合成提交（非產品提交）；以 `GIT_ALLOW_PROTOCOL=file`、隔離 HOME/global config、停用 hooks/signing 保證不接網路、不使用簽章或私人設定。

實际由測試執行：`git clone --depth 1 file://<temp>/source <temp>/shallow` 與 `git clone file://<temp>/source <temp>/full`。兩者 `git status --porcelain` 均空；前者 `--is-shallow-repository=true`，歷史 `git show <fixture-base>:protected.txt` 和 `git ls-tree -r --name-only <fixture-base>` 均非零；後者非 shallow，兩命令均 exit 0 且內容/清單符合明確預期。只使用合成純文字，未複製真 google 配置或產品 repo 歷史；結束僅清除自身暫存目錄。

fixture 證明本機 Git 歷史需求與 shallow 失敗模式；`fetch-depth: 0` 的 GHA 設定另由靜態斷言綁定 checkout 步驟。未直接執行 actions/checkout 或模擬完整 PR merge ref/GHA runner，**遠端 CI 仍未跑**。

### Read-only reviewer 命名檔（不需執行 git）
- `.github/workflows/build.yml`：checkout 完整歷史，其餘 matrix/publish 限制保留。
- `scripts/tests/test_brand_contracts.py`：`BASELINE`、`test_woow_resources_and_firebase_unchanged`、`BrandCheckoutContracts`。
- `CLAUDE.md`、`_bmad-output/project-context.md`：僅階段 2 補充中的 Gradle 暫緩理由修正。
- `docs/plans/2026-09-24-apporo-Implementation_Plan.md`、`docs/plans/2026-09-24-apporo-Test_Plan.md`：範圍/測試/門檻更新。
- 本報告與 `docs/verification-report/apporo-phase2/fix-round1-contracts.log`：處置與實際 36 條證據。

Apporo 真 Firebase/獨立簽章仍缺；Gradle DSL/Kotlin 未編譯、APK 資源/manifest/signer 未驗；P1 本機修正不解除原 merge/發布 BLOCKED。
