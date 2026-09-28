package io.woowtech.odoo.ui.config

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 2026-09-28 Android「移除 PIN」（對齊 iOS LIVE-0927-4 `SettingsViewModel.removePin(verifyingCurrentPin:)`）：
 * 設定頁原本沒有移除 PIN 的入口，而 `SettingsViewModel.removePin()` 是不需驗證的一鍵移除（沒有畫面呼叫，
 * 但任何接上它的畫面都等於讓拿到解鎖後手機的人把 PIN 拿掉）。現在移除前必須以目前 PIN 通過與解鎖相同的
 * [SettingsRepository.verifyPin]（同一失敗計數與鎖定，f9a0207／9f5f007／8bea81b），ViewModel 上不再有
 * 未驗證的移除入口。移除走 [SettingsRepository.removePin]：PIN 清除並在同一次寫入關閉 App Lock
 * （Android 的 PIN 是 App Lock 的必要解鎖底線 `appLockEnabled ⇒ pinEnabled`；iOS 以裝置密碼為底線，
 * 所以 iOS 移除 PIN 後 App Lock 保持開啟 — 平台差異，見 commit 說明）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovePinRequiresCurrentPinTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(dispatcher)
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.settings } returns
            MutableStateFlow(AppSettings(appLockEnabled = true, pinEnabled = true, pinHash = "salt:hash"))
        viewModel = viewModelWith(settingsRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelWith(repository: SettingsRepository): SettingsViewModel {
        val cacheRepository = mockk<CacheRepository>(relaxed = true)
        coEvery { cacheRepository.calculateCacheSize() } returns 0L
        return SettingsViewModel(
            repository,
            cacheRepository,
            mockk<AccountRepository>(relaxed = true).also { every { it.activeAccount } returns MutableStateFlow(null) },
            mockk<FcmTokenRepository>(relaxed = true).also { every { it.registrationStatuses } returns MutableStateFlow(emptyMap()) },
        )
    }

    private suspend fun enterCurrentPin(pin: String): PinEntryResult {
        var current = ""
        var result: PinEntryResult = PinEntryResult.NeedMoreDigits
        for (digit in pin) {
            val (next, r) = viewModel.enterPinToRemovePin(digit.toString(), current)
            current = next
            result = r
        }
        return result
    }

    @Test
    fun `The ViewModel has no unverified remove-PIN entry point`() {
        // iOS parity: "There is deliberately no unverified removal entry point on the ViewModel."
        val unverified = SettingsViewModel::class.java.methods.filter { it.name == "removePin" && it.parameterCount == 0 }

        assertTrue(unverified.isEmpty(), "SettingsViewModel.removePin() must not remove the PIN without verification")
    }

    @Test
    fun `Given fewer than 6 digits entered when removing the PIN then nothing is verified or removed`() = runTest {
        val result = enterCurrentPin("12345")

        assertEquals(PinEntryResult.NeedMoreDigits, result)
        coVerify(exactly = 0) { settingsRepository.verifyPin(any()) }
        verify(exactly = 0) { settingsRepository.removePin() }
    }

    @Test
    fun `Given a wrong current PIN when removing the PIN then the shared failure counter is used and the PIN stays`() = runTest {
        coEvery { settingsRepository.verifyPin("000000") } returns false
        every { settingsRepository.isLockedOut() } returns false
        every { settingsRepository.getRemainingAttempts() } returns 4

        val result = enterCurrentPin("000000")

        assertEquals(PinEntryResult.WrongPin(remainingAttempts = 4), result)
        coVerify(exactly = 1) { settingsRepository.verifyPin("000000") }
        verify(exactly = 0) { settingsRepository.removePin() }
    }

    @Test
    fun `Given the wrong PIN starts a lockout when removing the PIN then LockedOut and the PIN stays`() = runTest {
        coEvery { settingsRepository.verifyPin("000000") } returns false
        every { settingsRepository.isLockedOut() } returns true

        val result = enterCurrentPin("000000")

        assertEquals(PinEntryResult.LockedOut, result)
        verify(exactly = 0) { settingsRepository.removePin() }
    }

    @Test
    fun `Given the PIN is locked out when removing the PIN then even the correct PIN is refused and the PIN stays`() = runTest {
        // verifyPin refuses every PIN (even the right one) while locked out.
        coEvery { settingsRepository.verifyPin(any()) } returns false
        every { settingsRepository.isLockedOut() } returns true

        val result = enterCurrentPin("123456")

        assertEquals(PinEntryResult.LockedOut, result)
        verify(exactly = 0) { settingsRepository.removePin() }
    }

    @Test
    fun `Given the correct current PIN when removing the PIN then it is removed exactly once`() = runTest {
        coEvery { settingsRepository.verifyPin("123456") } returns true

        val result = enterCurrentPin("123456")

        assertEquals(PinEntryResult.Success, result)
        coVerify(exactly = 1) { settingsRepository.verifyPin("123456") }
        verify(exactly = 1) { settingsRepository.removePin() }
    }

    @Test
    fun `Given App Lock on with a real PIN when the correct PIN removes it then PIN cleared and App Lock off in one write`() = runTest {
        val prefs = mockk<EncryptedPrefs>(relaxed = true)
        every { prefs.getAppSettings() } returns AppSettings(appLockEnabled = false, pinEnabled = false)
        val repository = SettingsRepository(prefs)
        assertTrue(repository.setPin("123456"))
        assertTrue(repository.updateAppLock(true))
        viewModel = viewModelWith(repository)

        val result = enterCurrentPin("123456")

        assertEquals(PinEntryResult.Success, result)
        val after = repository.settings.value
        assertFalse(after.pinEnabled)
        assertNull(after.pinHash)
        assertFalse(after.appLockEnabled)
        verify(exactly = 1) {
            prefs.saveAppSettings(match { !it.pinEnabled && it.pinHash == null && !it.appLockEnabled })
        }
    }
}
