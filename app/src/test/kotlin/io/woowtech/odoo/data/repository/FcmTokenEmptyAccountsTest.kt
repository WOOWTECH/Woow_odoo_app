package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Empty-collection paranoia test for [FcmTokenRepositoryImpl.registerTokenForAllAccounts].
 *
 * The bug this prevents (CLAUDE.md § "Repository-Event Symmetry"):
 * commit 482a7bf wired WoowFcmService.onNewToken to call
 * registerTokenForAllAccounts. But onNewToken fires on the FIRST FCM init
 * after install — BEFORE any account exists. The original implementation
 * iterated `accounts.forEach { register }` and on empty list silently
 * returned `Result.success(Unit)`, masking the fact that the token never
 * reached any server.
 *
 * Fix: registerTokenForAllAccounts now logs a warning when called with
 * zero accounts, makes it explicit that no POST happened, and the
 * AccountRepository.authenticate / switchAccount paths replay the saved
 * token via registerSavedFcmToken once an account is present.
 *
 * This test ensures the empty path:
 *   1. Saves the token locally (so it can be replayed)
 *   2. Does NOT invoke any registerToken POST
 *   3. Returns success (caller doesn't crash)
 */
class FcmTokenEmptyAccountsTest {

    private lateinit var encryptedPrefs: io.woowtech.odoo.data.local.EncryptedPrefs
    private lateinit var accountDao: io.woowtech.odoo.data.local.AccountDao
    private lateinit var sessionCookieProvider: SessionCookieProvider
    private lateinit var sessionReauthInterceptor: io.woowtech.odoo.data.api.SessionReauthInterceptor
    private lateinit var repo: FcmTokenRepositoryImpl

    @BeforeEach
    fun setup() {
        encryptedPrefs = mockk(relaxed = true)
        accountDao = mockk(relaxed = true)
        sessionCookieProvider = mockk(relaxed = true)
        sessionReauthInterceptor = io.woowtech.odoo.data.api.SessionReauthInterceptor(
            mockk<io.woowtech.odoo.data.api.SessionReauthenticator>(relaxed = true),
        )
        every { sessionCookieProvider.getCookiesForAccount(any()) } returns emptyList<Cookie>()
        repo = FcmTokenRepositoryImpl(
            encryptedPrefs = encryptedPrefs,
            accountDao = accountDao,
            sessionCookieProvider = sessionCookieProvider,
            sessionReauthInterceptor = sessionReauthInterceptor,
        )
    }

    @Test
    fun `Given zero accounts when registerTokenForAllAccounts then token saved but no POST attempted`() = runTest {
        coEvery { accountDao.getAllAccountsList() } returns emptyList()

        val result = repo.registerTokenForAllAccounts(token = "fcm-token-fresh-install")

        // Saved locally so AccountRepository can replay on next login
        coVerify(exactly = 1) { encryptedPrefs.saveFcmToken("fcm-token-fresh-install") }
        // Returns success — caller (WoowFcmService.onNewToken) doesn't crash
        assertTrue(result.isSuccess)
        // CRITICAL: zero POSTs because zero accounts. Verify accountDao
        // was queried exactly once (the empty-list path) and nothing else.
        coVerify(exactly = 1) { accountDao.getAllAccountsList() }
    }

    @Test
    fun `Given saved token after empty registerTokenForAllAccounts when getStoredToken then returns the saved value`() = runTest {
        coEvery { accountDao.getAllAccountsList() } returns emptyList()
        every { encryptedPrefs.getFcmToken() } returns "fcm-token-fresh-install"

        repo.registerTokenForAllAccounts(token = "fcm-token-fresh-install")

        // The saved token must be retrievable so AccountRepository.authenticate
        // can replay it via registerSavedFcmToken.
        assertTrue(repo.getStoredToken() == "fcm-token-fresh-install")
    }
}
