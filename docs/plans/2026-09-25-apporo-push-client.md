# 階段 3 Android 本機推播候選

基線 `73ad528`、`feat/apporo-platform-brand-layer`；保留階段 2 dirty diff，不 stage/commit/push。準據為 plugin/docs/apporo-push-contract.md。

## 核准實作

- Apporo 每次 register/unregister 前查 capability，固定 brand，register 驗 echo/version；WOOW 保留 host-cookie/legacy 路徑及 switch unregister。
- 2026-09-25 supervisor 核准 Apporo 專用 account/session transport：以保存的 ID/origin（含 port）/DB/username/userId 綁定獨立 cookie，禁止共用 host jar。保留 HTTPS、有限重試、帳號 circuit/single-flight、登入成功 reset。
- 單一操作 cap/write 若過期，最多 heal 一次，換 session 必重查 cap；帳號刪除/身分變動即中止。能力不快取。
- account-keyed 記憶體狀態，Settings 按 active account ID 選取、三語固定診斷，不顯示 server body/secret。ACK 不代表送達。
- login/reconcile/rotation/empty replay/remove/logout 對稱覆蓋；本地 logout/remove 仍 best effort。

## 驗證邊界

新增 JVM 純契約與 fixture HTTP/session 時序測試，這輪不跑 Gradle（父代理後續序列執行）；只跑 Python stdlib 離線與靜態檢查。37 條 offline、438 PASS/3 SKIP 僅為歷史證據，不能當本輪 JVM 通過。live gate 不解除。不讀 Firebase ignored 配置、不連真網路/裝置、不操作 Odoo/容器/雲端。

## 測試政策更正（supervisor 核准）

首次本輪 offline 37 條有 7 個 subtest failures（2 個方法）：階段 2 的 4 個 stage3 source byte freeze、三語 strings 全檔 hash，與核准的階段 3 修改直接衝突。不是 JVM 失敗，也不刪測試或加 skip。

既有 37 條內更新兩方法：其他安全檔仍 byte-identical；AccountRepository/AppModule 只允許精確的登入 reset/移除清理接線；interface 保留原方法契約；adapter 唯一路徑＋WOOW 無 cap/brand 的靜態斷言對應新增 JVM 行為測試。三語全部 legacy key/value/屬性/placeholder 保留，只准精確 10 個新增 push keys、集合一致且中文本地化。靜態通過不代替 JVM 執行。歷史 37 PASS、438 PASS/3 SKIP 紀錄不改。

cleanup 補充批准：register 在 row 刪除/identity 變動即停止；明確 unregister 可在本地刪除後用動作開始時 captured identity/SID 完成 cap+cleanup；SID 過期且 row 已刪則不再認證、不找替代帳號。保持 await cleanup 後刪除的原時序。


## 最終 session 邊界與恢復收斂

- 已查明舊 `OdooJsonRpcClient.authenticate` 由共享 host jar 反查 SID。supervisor 核准第 5 個明確 session seam：Apporo 單次 isolated authenticate 讀同一次 Set-Cookie、零 shared jar 讀寫。WOOW 原 auth 分支逐字保留。
- Apporo manual login/switch 以同一 selectionMutex/attempt 保護 active 提交與 UI cookie 發布；晚到舊 completion 不得覆蓋新選擇。其 SID 直接交 push，正常登入不額外 auth；冷啟動無記憶體 SID／過期 heal 仍按保存的 account 身分認證，不查 host 找人。
- 依 PHASE3-CONTRACT §2.4 最終釐清：uid 成功但缺／空／過期／foreign-domain SID 一律 session 建立失敗，零 account/credential/active/jar 覆寫、不借舊 cookie、不額外 auth。重用既有三語 `error_session`，LoginUiState 僅新增 errorType 讓 Apporo 畫面選本地化文字，WOOW 原顯示不变。
- 有效 SID 但 cap 不支援：登入成功、push NOT_CONFIGURED。成對 loopback fixture 已加入；此輪未執行 JVM。
- 完整保存 base URL（含 port/path）與 DB/username/userId/account UUID 的 binding；captured cleanup 刪除例外與一次 bounded heal 維持。
- 本地無 Gradle/Xcode/裝置/真 Odoo/部署操作；只交未 staged diff。此候選跨多個責任區域，後續若批准提交須按 repository mega-commit cap 分拆，不把整體 dirty diff 一次提交。
