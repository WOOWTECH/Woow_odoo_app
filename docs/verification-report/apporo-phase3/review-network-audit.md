# Review 修復後的測試 transport 核對

此核對不撤銷 `review-network-incident.md` 的真 host authenticate 嘗試；不宣稱遠端無影響。

## 實體 client 接線

| 測試 | 主防線 | 第二層 |
|---|---|---|
| OdooJsonRpcClientTest | 所有 brand 都將同一 MockOnlyHttpFixture 注入 isolated/shared；只有明列 unreachable.fixture.test 路徑拋 mock UnknownHostException，其餘 request AssertionError | 每測試斷言 DNS/connect = 0；NO_PROXY |
| AuthTransportIsolationTest | shared WOOW/Apporo 及 Apporo isolated 未匹配 request 在 application interceptor 拒絕；兩品牌 LoginViewModel → AccountRepository → 真 API parser → synthetic response 成功 | 明確斷言每操作 1 mock request、0 DNS、0 connect；NO_PROXY |
| ApporoManualSessionTest | fixture.test 重寫至該測試持有 MockWebServer；shared/isolated 皆注入同一 client | onlyLoopback 限 host/port/http；DNS 不查外部；socket endpoint 核對；NO_PROXY |
| ApporoPushTransportTest | 同上，專用 push client 與兩個真 manual API fixture 皆封閉 | 同上 |
| SessionReauthInterceptorTest | auth API 是 MockK；HTTP request 只能去自有 MockWebServer | onlyLoopback；NO_PROXY |
| FcmTokenRepositoryTest / FcmTokenEmptyAccountsTest | synthetic response/exception handler 或預設 AssertionError，從不 chain.proceed | MockOnly DNS/socket blocker；NO_PROXY |
| 其餘 repository / ViewModel tests | MockK OdooJsonRpcClient 或 AccountRepository | 不建實體 HTTP client |

核對命令：`rg -n 'OdooJsonRpcClient\(|OkHttpClient\.Builder|newCall|\.execute\(|HttpURLConnection|URL\(' app/src/test -g '*.kt'`。network-capable fixtures 不再帶真 demo host；純 UI 正規化舊測試的 host 字串由 MockK repository 消耗，不進 HTTP。

## API 與 UI 回歸

- 已撤回為舊測試新增的 raw 尾空白拒絕。LoginViewModel / ServerUrlInput 原 trim 政策未修改。
- 舊 INVALID_URL/message 斷言保留，輸入改成明確語法非法 `https://bad host/`，不是依賴 DNS 失敗。
- OdooJsonRpcClient 第5 seam 只加可選 `sharedAuthClient`；production 預設 null，WOOW 原 builder/流程保留。測試 supplied client 的 blocker 會被 newBuilder 繼承。
- 新兩品牌 UI 測試實走正規化、repository 與 API response parser，確定尾空白 → canonical fixture URL → mock 登入成功。

## 執行層補強與環境失敗

- `remote ip "localhost:*"` profile 下 Gradle daemon 不能連接；literal IPv4/IPv6 profile 語法被 macOS 拒絕，沒有放寬 LAN。
- 最終 `review-sandbox.sb` 保留 deny network-outbound，只放 `remote tcp "localhost:*"`。owned Python socket：127.0.0.1 / ::1 成功；非 allowlist 127.0.0.2 得 EPERM=1。該位址無法 bind，因此負向只要求 sandbox connect 拒絕，不把 timeout/refused 當成功。
- owned JVM socket 原本 EPERM；固定 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true` 後成功，未增加任何 network allow。
- MockK dynamic attach 被阻（27 中3失敗）；改成從既有 test classpath 預載 ByteBuddy agent，沒有放行 Unix IPC、下載、skip 或改斷言。
- 每個後續 Gradle 在相同 sandbox，清除六種 proxy env，client NO_PROXY；synthetic Firebase init fixture 不讀真配置、不作出包/安裝證據。
- 這不是全系統封網，也不宣稱隔絕 OS DNS broker；主要防線仍是測試 client 在 DNS 前拒絕及 endpoint 限制。

## 實際結果位置

- `review-sandbox-agent-focused-xml/`：27 PASS / 0 FAIL / 0 SKIP。
- `review-guarded-related-xml/`：143 PASS / 0 FAIL / 0 SKIP。
- 兩flavor完整結果見 `review-test-results.json` 與 validation.md，不能引用前輪438計數。
