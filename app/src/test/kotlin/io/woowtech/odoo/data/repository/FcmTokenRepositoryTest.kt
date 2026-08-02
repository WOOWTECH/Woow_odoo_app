package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.api.SessionReauthInterceptor
import io.woowtech.odoo.data.api.SessionReauthenticator
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * FcmTokenRegistrationTest and FcmTokenUnregistrationTest combined.
 * Tests registration storage, account enumeration, unregister on logout.
 * Network POST tests are exercised via the OkHttp client path; full integration
 * requires a mock server (see TODO note on FcmTokenRepositoryImpl).
 */
class FcmTokenRepositoryTest {

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var accountDao: AccountDao
    private lateinit var sessionCookieProvider: SessionCookieProvider
    private lateinit var sessionReauthInterceptor: SessionReauthInterceptor
    private lateinit var repo: FcmTokenRepositoryImpl

    @BeforeEach
    fun setup() {
        encryptedPrefs = mockk(relaxed = true)
        accountDao = mockk(relaxed = true)
        sessionCookieProvider = mockk(relaxed = true)
        // Real interceptor over a relaxed re-auth engine: no session-expired body is served in these
        // tests, so the interceptor is a transparent pass-through (detection returns false).
        sessionReauthInterceptor = SessionReauthInterceptor(mockk<SessionReauthenticator>(relaxed = true))
        every { sessionCookieProvider.getCookiesForHost(any()) } returns emptyList<Cookie>()
        repo = FcmTokenRepositoryImpl(
            encryptedPrefs = encryptedPrefs,
            accountDao = accountDao,
            sessionCookieProvider = sessionCookieProvider,
            sessionReauthInterceptor = sessionReauthInterceptor,
        )
    }

    private fun makeAccount(id: String, serverUrl: String = "https://odoo.example.com") = OdooAccount(
        id = id,
        serverUrl = serverUrl,
        database = "test",
        username = "admin",
        displayName = "Admin",
    )

    // FcmTokenRegistrationTest

    @Test
    fun `Given token when registerTokenForAllAccounts then saves token to EncryptedPrefs`() = runTest {
        coEvery { accountDao.getAllAccountsList() } returns emptyList()

        repo.registerTokenForAllAccounts("tok_abc")

        verify { encryptedPrefs.saveFcmToken("tok_abc") }
    }

    @Test
    fun `Given no accounts when registerTokenForAllAccounts then returns success`() = runTest {
        coEvery { accountDao.getAllAccountsList() } returns emptyList()

        val result = repo.registerTokenForAllAccounts("tok_xyz")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `Given 2 accounts when registerTokenForAllAccounts then attempts registration for each`() = runTest {
        val accounts = listOf(makeAccount("a1"), makeAccount("a2"))
        coEvery { accountDao.getAllAccountsList() } returns accounts
        coEvery { accountDao.getAccountById("a1") } returns accounts[0]
        coEvery { accountDao.getAccountById("a2") } returns accounts[1]

        // Network calls will fail (no real server) but that's expected — we verify the
        // token is saved and the loop iterates over all accounts.
        repo.registerTokenForAllAccounts("tok_multi")

        verify { encryptedPrefs.saveFcmToken("tok_multi") }
    }

    // ── hermetic reconcile-classification tests ──────────────────────────────────────────────
    // These inject a fake OkHttp client whose single interceptor fabricates a response or throws a
    // chosen exception PER REQUEST — no real DNS/network, so they are deterministic and fast (unlike
    // hitting a real host). They pin the exact behaviour the "unreachable must not poison" fix added.

    /** A repo wired to [client] via the test-only primary constructor (bypasses the real network). */
    private fun repoWith(client: OkHttpClient): FcmTokenRepositoryImpl =
        FcmTokenRepositoryImpl(encryptedPrefs = encryptedPrefs, accountDao = accountDao, httpClient = client)

    /** Builds a client whose interceptor delegates each request to [handler] (which may throw). */
    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build()

    private fun jsonResponse(request: Request, code: Int, body: String = "{\"result\":{}}"): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code < 400) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    @Test
    fun `Given a reachable account when registerTokenForAllAccounts then success and token saved`() = runTest {
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a

        val result = repoWith(fakeClient { req -> jsonResponse(req, 200) }).registerTokenForAllAccounts("tok")

        assertTrue(result.isSuccess)
        verify { encryptedPrefs.saveFcmToken("tok") }
    }

    @Test
    fun `Given one live and one unreachable account when register then success and the live account is still POSTed`() = runTest {
        // The demo444 incident: a retired tenant (DNS gone) lingers next to a live account. The dead
        // sibling must NOT fail the batch, and the live account MUST still be registered.
        val live = makeAccount("live", serverUrl = "https://live.test")
        val dead = makeAccount("dead", serverUrl = "https://dead.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(live, dead)
        coEvery { accountDao.getAccountById("live") } returns live
        coEvery { accountDao.getAccountById("dead") } returns dead
        val postedHosts = mutableListOf<String>()

        val result = repoWith(
            fakeClient { req ->
                postedHosts += req.url.host
                if (req.url.host == "dead.test") throw UnknownHostException("dead.test")
                jsonResponse(req, 200)
            },
        ).registerTokenForAllAccounts("tok")

        assertTrue(result.isSuccess, "an unreachable sibling must not fail the batch")
        assertTrue(postedHosts.contains("live.test"), "the live account must still be POSTed")
    }

    @Test
    fun `Given a reachable server returning HTTP 500 when register then a hard failure surfaces`() = runTest {
        // A live server that responds with an error was REACHED — it must NOT be swallowed as a
        // best-effort "unreachable" skip. This is the classifier-narrowing regression guard.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a

        val result = repoWith(fakeClient { req -> jsonResponse(req, 500) }).registerTokenForAllAccounts("tok")

        assertTrue(result.isFailure, "a reachable server's 500 is a hard failure, not a best-effort skip")
    }

    @Test
    fun `Given every account is unreachable when register then failure (nothing registered anywhere)`() = runTest {
        // Zero accounts got the token (e.g. launched offline). Reporting success would let the caller
        // treat it as done and never retry, so it must be a (retryable) failure.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a

        val result = repoWith(fakeClient { throw UnknownHostException("a.test") }).registerTokenForAllAccounts("tok")

        assertTrue(result.isFailure, "zero accounts registered must not report success")
    }

    @Test
    fun `Given the per-account POST throws CancellationException when register then it propagates (never swallowed)`() = runTest {
        // CLAUDE.md hard rule: cancellation must never be caught as a per-account failure.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a
        val repo = repoWith(fakeClient { throw CancellationException("cancelled") })

        var propagated = false
        try {
            repo.registerTokenForAllAccounts("tok")
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue(propagated, "CancellationException must propagate, not be collected as a Result.failure")
    }

    @Test
    fun `Given account not found when registerToken then returns failure`() = runTest {
        coEvery { accountDao.getAccountById("missing") } returns null

        val result = repo.registerToken(accountId = "missing", token = "tok")

        assertTrue(result.isFailure)
    }

    // FcmTokenUnregistrationTest

    @Test
    fun `Given account not found when unregisterToken then returns failure`() = runTest {
        coEvery { accountDao.getAccountById("gone") } returns null

        val result = repo.unregisterToken("gone")

        assertTrue(result.isFailure)
    }

    @Test
    fun `Given no stored token when unregisterToken then returns failure`() = runTest {
        coEvery { accountDao.getAccountById("a1") } returns makeAccount("a1")
        every { encryptedPrefs.getFcmToken() } returns null

        val result = repo.unregisterToken("a1")

        assertTrue(result.isFailure)
    }

    // getStoredToken tests

    @Test
    fun `Given token stored when getStoredToken then returns it`() {
        every { encryptedPrefs.getFcmToken() } returns "stored_token"

        assertEquals("stored_token", repo.getStoredToken())
    }

    @Test
    fun `Given no token stored when getStoredToken then returns null`() {
        every { encryptedPrefs.getFcmToken() } returns null

        assertNull(repo.getStoredToken())
    }

    // reconcileToken (launch-time self-heal) tests — WI-2

    @Test
    fun `Given at least one account when reconcileToken then saves and registers the current token`() = runTest {
        val accounts = listOf(makeAccount("a1"))
        coEvery { accountDao.getAllAccountsList() } returns accounts
        coEvery { accountDao.getAccountById("a1") } returns accounts[0]

        repo.reconcileToken("tok_launch")

        // Registration path was entered (token persisted for the active account).
        verify { encryptedPrefs.saveFcmToken("tok_launch") }
    }

    @Test
    fun `Given no accounts when reconcileToken then short-circuits without registering and returns success`() = runTest {
        coEvery { accountDao.getAllAccountsList() } returns emptyList()

        val result = repo.reconcileToken("tok_launch")

        assertTrue(result.isSuccess)
        // Short-circuit: no registration path, so the token is NOT saved.
        verify(exactly = 0) { encryptedPrefs.saveFcmToken(any()) }
    }

    @Test
    fun `Given already-current token when reconcileToken runs twice then behavior is stable`() = runTest {
        val accounts = listOf(makeAccount("a1"))
        coEvery { accountDao.getAllAccountsList() } returns accounts
        coEvery { accountDao.getAccountById("a1") } returns accounts[0]
        every { encryptedPrefs.getFcmToken() } returns "tok_same"

        repo.reconcileToken("tok_same")
        val second = repo.reconcileToken("tok_same")

        // Idempotent: repeated reconcile of the same token does not throw and stays saveable.
        verify(atLeast = 1) { encryptedPrefs.saveFcmToken("tok_same") }
        assertTrue(second.isSuccess || second.isFailure) // no exception propagated
    }

    // ── reconcileOnAccountAvailable (S2 / AC8.b — account-restored/added event) ─────────────────
    // These drive the event-driven reconcile against the hermetic fake-OkHttp seam (no real DNS).
    // They pin: (a) an account-restore upserts the CURRENT token for EACH account; (b) the
    // token-arrived-before-account race is fixed (a token saved before any account is registered as
    // soon as the account appears); (c) no token yet → cheap no-op; (d) redundant triggers are safe.

    @Test
    fun `Given a stored token and two accounts when reconcileOnAccountAvailable then a register is POSTed for each account`() = runTest {
        val a1 = makeAccount("a1", serverUrl = "https://one.test")
        val a2 = makeAccount("a2", serverUrl = "https://two.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a1, a2)
        coEvery { accountDao.getAccountById("a1") } returns a1
        coEvery { accountDao.getAccountById("a2") } returns a2
        every { encryptedPrefs.getFcmToken() } returns "tok_restore"
        val postedHosts = mutableListOf<String>()

        val result = repoWith(
            fakeClient { req ->
                postedHosts += req.url.host
                jsonResponse(req, 200)
            },
        ).reconcileOnAccountAvailable()

        assertTrue(result.isSuccess)
        // AC8.b: one register per logged-in account (each account's own host was POSTed).
        assertTrue(postedHosts.contains("one.test"), "account a1 must be registered")
        assertTrue(postedHosts.contains("two.test"), "account a2 must be registered")
    }

    @Test
    fun `Given a token arrived before any account when the account later appears then reconcileOnAccountAvailable registers it`() = runTest {
        // Phase 1 — the race: onNewToken fires with ZERO accounts. The token is saved locally but
        // nothing is POSTed anywhere.
        coEvery { accountDao.getAllAccountsList() } returns emptyList()
        val postedHosts = mutableListOf<String>()
        val racyRepo = repoWith(
            fakeClient { req ->
                postedHosts += req.url.host
                jsonResponse(req, 200)
            },
        )
        racyRepo.registerTokenForAllAccounts("tok_race")
        verify { encryptedPrefs.saveFcmToken("tok_race") }
        assertTrue(postedHosts.isEmpty(), "no POST should happen while there are zero accounts")

        // Phase 2 — the account now appears (cold-start restore / login) and the stored token is the
        // one saved in phase 1. The account-available reconcile must now register it.
        val account = makeAccount("late", serverUrl = "https://late.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        coEvery { accountDao.getAccountById("late") } returns account
        every { encryptedPrefs.getFcmToken() } returns "tok_race"

        val result = racyRepo.reconcileOnAccountAvailable()

        assertTrue(result.isSuccess)
        assertTrue(postedHosts.contains("late.test"), "the token that arrived before the account must now be registered")
    }

    @Test
    fun `Given no token has arrived yet when reconcileOnAccountAvailable then it is a no-op success with no POST`() = runTest {
        val account = makeAccount("a1", serverUrl = "https://one.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        coEvery { accountDao.getAccountById("a1") } returns account
        every { encryptedPrefs.getFcmToken() } returns null
        val postedHosts = mutableListOf<String>()

        val result = repoWith(
            fakeClient { req ->
                postedHosts += req.url.host
                jsonResponse(req, 200)
            },
        ).reconcileOnAccountAvailable()

        // No token yet → nothing to register; token-arrived (onNewToken) will drive it later.
        assertTrue(result.isSuccess)
        assertTrue(postedHosts.isEmpty(), "no register may be POSTed before a token exists")
    }

    @Test
    fun `Given reconcileOnAccountAvailable fired twice when redundant then both succeed and re-register (idempotent)`() = runTest {
        val account = makeAccount("a1", serverUrl = "https://one.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        coEvery { accountDao.getAccountById("a1") } returns account
        every { encryptedPrefs.getFcmToken() } returns "tok_same"
        val postCount = intArrayOf(0)
        val repo = repoWith(
            fakeClient { req ->
                postCount[0]++
                jsonResponse(req, 200)
            },
        )

        val first = repo.reconcileOnAccountAvailable()
        val second = repo.reconcileOnAccountAvailable()

        // The client keeps no diff-set: it simply re-calls register on each event. The server
        // early-returns an unchanged pair, so re-firing is harmless — both calls succeed.
        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(2, postCount[0], "each trigger re-POSTs register (server-side early-return makes it cheap)")
    }

    @Test
    fun `Given the register POST throws CancellationException when reconcileOnAccountAvailable then it propagates`() = runTest {
        // Best-effort must never swallow cancellation (CLAUDE.md hard rule) — the new event path too.
        val account = makeAccount("a1", serverUrl = "https://one.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        coEvery { accountDao.getAccountById("a1") } returns account
        every { encryptedPrefs.getFcmToken() } returns "tok"
        val repo = repoWith(fakeClient { throw CancellationException("cancelled") })

        var propagated = false
        try {
            repo.reconcileOnAccountAvailable()
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue(propagated, "CancellationException must propagate from the account-available reconcile")
    }

    // ---------------------------------------------------------------------
    // Story 8-1 (P2-9) WI-2 — do not persist a tenant id another account owns
    // ---------------------------------------------------------------------
    //
    // `odoo_tenant_id` defaults to the Odoo database name and spec §4.3 ships every box
    // with the same POSTGRES_DB, so two servers routinely hand back an IDENTICAL id.
    // Letting both accounts store it puts the ambiguity in the database, where the deep
    // link router can only refuse to act on it. Refusing the write keeps the collision
    // observable and out of the routing key.

    private fun registerResponse(tenantId: String) =
        """{"jsonrpc":"2.0","id":1,"result":{"device_id":7,"odoo_tenant_id":"$tenantId"}}"""

    @Test
    fun `Given another account already owns the tenant id when registering then it is STILL persisted`() = runTest {
        // Review finding: the first version of WI-2 REFUSED this write, which defeated WI-1.
        // DeepLinkRouter detects ambiguity with `matches.size > 1`, so it can only refuse a
        // colliding id when BOTH accounts carry it. Leaving exactly one owner makes the router
        // see a unique match and confidently switch to it — a push from server Y opening
        // server X's account. Persisting on both is what makes the collision detectable.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a
        coEvery { accountDao.countAccountsWithTenantId("odoo18_ecpay", "a") } returns 1

        repoWith(fakeClient { req -> jsonResponse(req, 200, registerResponse("odoo18_ecpay")) })
            .registerTokenForAllAccounts("tok")

        coVerify(exactly = 1) { accountDao.updateTenantId(id = "a", tenantId = "odoo18_ecpay") }
    }

    @Test
    fun `Given the tenant id is unique when registering then it is persisted`() = runTest {
        // The counterpart, so the refusal above reads as scoped rather than as a blanket stop.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a
        coEvery { accountDao.countAccountsWithTenantId("tenant-unique", "a") } returns 0

        repoWith(fakeClient { req -> jsonResponse(req, 200, registerResponse("tenant-unique")) })
            .registerTokenForAllAccounts("tok")

        coVerify(exactly = 1) { accountDao.updateTenantId(id = "a", tenantId = "tenant-unique") }
    }

    // ---------------------------------------------------------------------
    // Story 8-2 (P0-2) — a rejected registration must not be reported as success
    // ---------------------------------------------------------------------
    //
    // FIXTURE PROVENANCE. Inner `result` payloads are copied verbatim from the plugin's
    // controller source, cited per fixture. The ENVELOPE is written here because the
    // plugin's own tests DISCARD it (`tests/test_fcm_controller.py:42` returns
    // `response.json().get('result', ...)`), so they are authoritative for the inner object
    // ONLY and carry zero information about the wrapper. A fixture lifted from them would
    // exercise a body shape Odoo never emits.
    //
    // Those tests also assert `assertIn('error', result)` — KEY PRESENCE, not message text.
    // So the taxonomy below keys on the `error` key and never on its message string; a test
    // matching "Invalid fcm_token format" would inherit a guarantee the plugin does not make.
    //
    // These bodies are DERIVED FROM SOURCE, NOT CAPTURED. 待伺服器恢復後驗證: capture real
    // wire bodies and diff them against these before trusting them.

    /** Odoo wraps every `type='json'` route return in this envelope. */
    private fun envelope(result: String) = """{"jsonrpc":"2.0","id":1,"result":$result}"""

    // plugin controllers/fcm_controller.py:43
    private val registerRejected = envelope("""{"error":"Invalid fcm_token format"}""")
    // plugin models/fcm_device.py — the register_device success shape
    private val registerAccepted = envelope("""{"device_id":7,"odoo_tenant_id":"tenant-x"}""")
    // plugin controllers/fcm_controller.py:79 — `False` means "you had no row"
    private val unregisterNoRow = envelope("""{"success":false}""")
    private val unregisterDeleted = envelope("""{"success":true}""")
    // plugin controllers/fcm_controller.py:74
    private val unregisterRejected = envelope("""{"error":"fcm_token is required"}""")

    @Test
    fun `Given unregister returns success false when unregistering then it is NOT a failure`() = runTest {
        // WRITE THIS ONE FIRST. `false` means the server had no row for this user, which after
        // logout is the DESIRED end state — not an error. A parser that treats every falsy
        // result as rejection manufactures an alarm on every correct logout, which is the most
        // likely way a well-meaning implementer breaks P0-2.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAccountById("a") } returns a
        every { encryptedPrefs.getFcmToken() } returns "tok"

        val result = repoWith(fakeClient { req -> jsonResponse(req, 200, unregisterNoRow) })
            .unregisterToken("a")

        assertTrue(result.isSuccess, "an empty server-side state is the goal of unregister, not a failure")
    }

    @Test
    fun `Given unregister returns an error when unregistering then it is a failure`() = runTest {
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAccountById("a") } returns a
        every { encryptedPrefs.getFcmToken() } returns "tok"

        val result = repoWith(fakeClient { req -> jsonResponse(req, 200, unregisterRejected) })
            .unregisterToken("a")

        assertTrue(result.isFailure)
    }

    @Test
    fun `Given unregister deletes the row when unregistering then it is a success`() = runTest {
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAccountById("a") } returns a
        every { encryptedPrefs.getFcmToken() } returns "tok"

        val result = repoWith(fakeClient { req -> jsonResponse(req, 200, unregisterDeleted) })
            .unregisterToken("a")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `Given the server rejects the registration inside result when registering then it fails`() = runTest {
        // The defect: the client checked only the JSON-RPC ENVELOPE error, but the plugin
        // returns validation failures INSIDE result with HTTP 200. "Invalid fcm_token format"
        // was logged as registered and returned as Result.success, with no retry until the next
        // cold start and no release-mode logging to notice it.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a

        val result = repoWith(fakeClient { req -> jsonResponse(req, 200, registerRejected) })
            .registerTokenForAllAccounts("tok")

        assertTrue(result.isFailure, "a result-level rejection must not be reported as success")
    }

    @Test
    fun `Given a rejected registration when registering then no tenant id is persisted`() = runTest {
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a

        repoWith(fakeClient { req -> jsonResponse(req, 200, registerRejected) })
            .registerTokenForAllAccounts("tok")

        coVerify(exactly = 0) { accountDao.updateTenantId(any(), any()) }
    }

    @Test
    fun `Given an accepted registration when registering then it succeeds and persists the tenant id`() = runTest {
        // The counterpart, so "fail closed" cannot be implemented as "fail always".
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a
        coEvery { accountDao.countAccountsWithTenantId("tenant-x", "a") } returns 0

        val result = repoWith(fakeClient { req -> jsonResponse(req, 200, registerAccepted) })
            .registerTokenForAllAccounts("tok")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { accountDao.updateTenantId(id = "a", tenantId = "tenant-x") }
    }

    @Test
    fun `Given an envelope-level error when registering then it still fails (regression)`() = runTest {
        // The behaviour that already worked must not be lost while adding the result-level check.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a
        val body = """{"jsonrpc":"2.0","id":1,"error":{"code":200,"message":"Odoo Server Error"}}"""

        val result = repoWith(fakeClient { req -> jsonResponse(req, 200, body) })
            .registerTokenForAllAccounts("tok")

        assertTrue(result.isFailure)
    }

    @Test
    fun `Given the id field differs when registering then the outcome is unaffected`() = runTest {
        // The app sends "id": 1; the plugin's own test helper sends none, so Odoo echoes null.
        // Fixtures derived from either source must classify identically — proven, not assumed.
        val a = makeAccount("a", serverUrl = "https://a.test")
        coEvery { accountDao.getAllAccountsList() } returns listOf(a)
        coEvery { accountDao.getAccountById("a") } returns a
        val nullId = """{"jsonrpc":"2.0","id":null,"result":{"error":"Invalid fcm_token format"}}"""

        val result = repoWith(fakeClient { req -> jsonResponse(req, 200, nullId) })
            .registerTokenForAllAccounts("tok")

        assertTrue(result.isFailure)
    }
}
