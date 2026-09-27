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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * LIVE-0927 Android r3（證據 26→28→38）：設定頁「變更 PIN」直接開 PinSetupScreen（SettingsScreen.kt:284
 * `showPinSetup = true` → SettingsViewModel.setPin），不必知道目前 PIN 就能換成新 PIN，再用新 PIN 通過
 * f9a0207 的「關閉 App Lock 須輸入 PIN」— 等於繞過。已有 PIN 時，變更前必須先以目前 PIN 通過與解鎖相同的
 * [SettingsRepository.verifyPin]（同一失敗計數與鎖定）；ViewModel 層在未驗證時拒絕 setPin。首次設定不需驗證。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangePinRequiresCurrentPinTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(dispatcher)
        settingsRepository = mockk(relaxed = true)
        coEvery { settingsRepository.setPin(any()) } returns true
        viewModel = viewModelWith(AppSettings(appLockEnabled = true, pinEnabled = true, pinHash = "salt:hash"))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelWith(settings: AppSettings): SettingsViewModel {
        every { settingsRepository.settings } returns MutableStateFlow(settings)
        val cacheRepository = mockk<CacheRepository>(relaxed = true)
        coEvery { cacheRepository.calculateCacheSize() } returns 0L
        return SettingsViewModel(
            settingsRepository,
            cacheRepository,
            mockk<AccountRepository>(relaxed = true).also { every { it.activeAccount } returns MutableStateFlow(null) },
            mockk<FcmTokenRepository>(relaxed = true).also { every { it.registrationStatuses } returns MutableStateFlow(emptyMap()) },
        )
    }

    private suspend fun enterCurrentPin(pin: String): PinEntryResult {
        var current = ""
        var result: PinEntryResult = PinEntryResult.NeedMoreDigits
        for (digit in pin) {
            val (next, r) = viewModel.enterPinToChangePin(digit.toString(), current)
            current = next
            result = r
        }
        return result
    }

    @Test
    fun `Given a PIN is set and the current PIN was not verified when setPin then the PIN is not changed`() = runTest {
        viewModel.setPin("654321")
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setPin(any()) }
    }

    @Test
    fun `Given a PIN is set and not verified when setPinThenEnableAppLock then the PIN is not changed`() = runTest {
        viewModel.setPinThenEnableAppLock("654321")
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setPin(any()) }
        verify(exactly = 0) { settingsRepository.updateAppLock(any()) }
    }

    @Test
    fun `Given a wrong current PIN when changing the PIN then the shared failure counter is used and the PIN is not changed`() = runTest {
        coEvery { settingsRepository.verifyPin("000000") } returns false
        every { settingsRepository.isLockedOut() } returns false
        every { settingsRepository.getRemainingAttempts() } returns 4

        val result = enterCurrentPin("000000")
        viewModel.setPin("654321")
        advanceUntilIdle()

        assertEquals(PinEntryResult.WrongPin(remainingAttempts = 4), result)
        coVerify(exactly = 1) { settingsRepository.verifyPin("000000") }
        coVerify(exactly = 0) { settingsRepository.setPin(any()) }
    }

    @Test
    fun `Given the correct current PIN when changing the PIN then the new PIN is stored`() = runTest {
        coEvery { settingsRepository.verifyPin("123456") } returns true

        val result = enterCurrentPin("123456")
        viewModel.setPin("654321")
        advanceUntilIdle()

        assertEquals(PinEntryResult.Success, result)
        coVerify(exactly = 1) { settingsRepository.setPin("654321") }
    }

    @Test
    fun `Given the PIN is locked out when changing the PIN then even the correct PIN is refused and the PIN is not changed`() = runTest {
        // verifyPin refuses every PIN (even the right one) while locked out.
        coEvery { settingsRepository.verifyPin(any()) } returns false
        every { settingsRepository.isLockedOut() } returns true

        val result = enterCurrentPin("123456")
        viewModel.setPin("654321")
        advanceUntilIdle()

        assertEquals(PinEntryResult.LockedOut, result)
        coVerify(exactly = 0) { settingsRepository.setPin(any()) }
    }

    @Test
    fun `Given one successful verification when setPin twice then only the first change is allowed`() = runTest {
        coEvery { settingsRepository.verifyPin("123456") } returns true

        enterCurrentPin("123456")
        viewModel.setPin("654321")
        advanceUntilIdle()
        viewModel.setPin("111111")
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsRepository.setPin("654321") }
        coVerify(exactly = 0) { settingsRepository.setPin("111111") }
    }

    @Test
    fun `Given verification then the change is cancelled when setPin then the PIN is not changed`() = runTest {
        coEvery { settingsRepository.verifyPin("123456") } returns true

        enterCurrentPin("123456")
        viewModel.cancelPinChange()
        viewModel.setPin("654321")
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setPin(any()) }
    }

    @Test
    fun `Given no PIN yet (first-time setup) when setPin then it is stored without verification`() = runTest {
        viewModel = viewModelWith(AppSettings(appLockEnabled = false, pinEnabled = false))

        viewModel.setPinThenEnableAppLock("123456")
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.verifyPin(any()) }
        coVerify(exactly = 1) { settingsRepository.setPin("123456") }
        verify(exactly = 1) { settingsRepository.updateAppLock(true) }
    }

    @Test
    fun `Given no PIN yet when setPin from the PIN item then it is stored without verification`() = runTest {
        viewModel = viewModelWith(AppSettings(appLockEnabled = true, pinEnabled = false))

        viewModel.setPin("123456")
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.verifyPin(any()) }
        coVerify(exactly = 1) { settingsRepository.setPin("123456") }
    }
}
