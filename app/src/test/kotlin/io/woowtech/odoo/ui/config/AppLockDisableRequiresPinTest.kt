package io.woowtech.odoo.ui.config

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.CacheRepository
import io.woowtech.odoo.data.repository.FcmTokenRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.ui.auth.PinEntryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * LIVE-0927 Android r2（證據 49／50）：設定頁關閉 App Lock 一點就關（SettingsScreen.kt:234-241 直接
 * `updateAppLock(false)`），拿到解鎖後手機的人不必知道 PIN 就能把鎖拿掉。對齊 iOS 32462a3（移除
 * PIN 前須驗證目前 PIN）：關閉前要輸入目前 PIN，走與解鎖相同的 [SettingsRepository.verifyPin]
 * （同一個失敗計數與鎖定），輸錯或鎖定中都不能關。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockDisableRequiresPinTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(dispatcher)
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.settings } returns
            MutableStateFlow(AppSettings(appLockEnabled = true, pinEnabled = true, pinHash = "salt:hash"))
        val cacheRepository = mockk<CacheRepository>(relaxed = true)
        coEvery { cacheRepository.calculateCacheSize() } returns 0L
        viewModel = SettingsViewModel(
            settingsRepository,
            cacheRepository,
            mockk<AccountRepository>(relaxed = true).also { every { it.activeAccount } returns MutableStateFlow(null) },
            mockk<FcmTokenRepository>(relaxed = true).also { every { it.registrationStatuses } returns MutableStateFlow(emptyMap()) },
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun enterSixDigits(pin: String): PinEntryResult {
        var current = ""
        var result: PinEntryResult = PinEntryResult.NeedMoreDigits
        for (digit in pin) {
            val (next, r) = viewModel.enterPinToDisableAppLock(digit.toString(), current)
            current = next
            result = r
        }
        return result
    }

    @Test
    fun `Given App Lock on with a PIN when switched off without a PIN then it stays on`() {
        viewModel.updateAppLock(false)

        verify(exactly = 0) { settingsRepository.updateAppLock(false) }
    }

    @Test
    fun `Given the correct current PIN when turning App Lock off then it is turned off`() = runTest {
        coEvery { settingsRepository.verifyPin("123456") } returns true

        val result = enterSixDigits("123456")

        assertEquals(PinEntryResult.Success, result)
        verify(exactly = 1) { settingsRepository.updateAppLock(false) }
    }

    @Test
    fun `Given a wrong PIN when turning App Lock off then it stays on and the shared failure counter is used`() = runTest {
        coEvery { settingsRepository.verifyPin("000000") } returns false
        every { settingsRepository.isLockedOut() } returns false
        every { settingsRepository.getRemainingAttempts() } returns 4

        val result = enterSixDigits("000000")

        assertEquals(PinEntryResult.WrongPin(remainingAttempts = 4), result)
        coVerify(exactly = 1) { settingsRepository.verifyPin("000000") }
        verify(exactly = 0) { settingsRepository.updateAppLock(false) }
    }

    @Test
    fun `Given the PIN is locked out when turning App Lock off then it stays on`() = runTest {
        // verifyPin refuses every PIN (even the right one) while locked out.
        coEvery { settingsRepository.verifyPin(any()) } returns false
        every { settingsRepository.isLockedOut() } returns true

        val result = enterSixDigits("123456")

        assertEquals(PinEntryResult.LockedOut, result)
        verify(exactly = 0) { settingsRepository.updateAppLock(false) }
    }

    @Test
    fun `Given fewer than six digits when turning App Lock off then nothing is verified yet`() = runTest {
        val (pin, result) = viewModel.enterPinToDisableAppLock("1", "12")

        assertEquals("121", pin)
        assertEquals(PinEntryResult.NeedMoreDigits, result)
        coVerify(exactly = 0) { settingsRepository.verifyPin(any()) }
    }

    @Test
    fun `Given App Lock on without a PIN (legacy state) when switched off then it can be turned off`() {
        every { settingsRepository.settings } returns MutableStateFlow(AppSettings(appLockEnabled = true, pinEnabled = false))
        val legacy = SettingsViewModel(
            settingsRepository,
            mockk(relaxed = true),
            mockk<AccountRepository>(relaxed = true).also { every { it.activeAccount } returns MutableStateFlow(null) },
            mockk<FcmTokenRepository>(relaxed = true).also { every { it.registrationStatuses } returns MutableStateFlow(emptyMap()) },
        )

        legacy.updateAppLock(false)

        verify(exactly = 1) { settingsRepository.updateAppLock(false) }
    }
}
