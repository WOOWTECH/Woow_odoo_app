package io.woowtech.odoo.ui.auth

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.R
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * verify-20260928 Android（證據 attempt2-31～34）：在 PIN 畫面上輸錯第 5 次觸發 30 秒鎖定後，畫面固定顯示
 * 「Too many attempts. Try again later.」、鍵盤隱藏，4 分鐘後仍不會回來，只能按返回重進。根因：
 * `PinScreen` 的鎖定輪詢 `LaunchedEffect(lifecycleOwner)` 只在首次組合時已鎖定才跑；輸入中收到
 * `LockedOut` 只把 `isLockedOut` 設成 true，之後沒有人把它改回 false。解鎖閘門共用此元件而且沒有
 * 返回鍵，使用者會被困住。
 *
 * 修法：鎖定狀態由可觀察的鎖定到期時間驅動（`pinLockoutUntil` 與每次 `LockedOut` 都會重啟倒數），
 * 每秒顯示剩餘秒數（對齊 iOS `lockout_timer_%lld`），到期自動收掉訊息並顯示鍵盤。
 *
 * 時間以 Compose 測試時鐘（`mainClock`）推進；假 repository 的鎖定到期與剩餘時間都以同一個時鐘計算。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PinLockoutKeypadReturnsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val lockoutMs = 30_000L
    private var lockoutUntil: Long? = null
    private val settingsFlow = MutableStateFlow(AppSettings(appLockEnabled = true, pinEnabled = true))

    private fun now(): Long = composeRule.mainClock.currentTime

    /** Every wrong 6-digit entry starts (or restarts) a 30 s lockout, like the 5th failure does. */
    private fun lockingRepository(): SettingsRepository = mockk(relaxed = true) {
        every { settings } returns settingsFlow
        coEvery { verifyPin(any()) } answers {
            lockoutUntil = now() + lockoutMs
            settingsFlow.value = settingsFlow.value.copy(failedPinAttempts = 5, pinLockoutUntil = lockoutUntil)
            false
        }
        every { isLockedOut() } answers { lockoutUntil?.let { now() < it } ?: false }
        every { getLockoutRemainingMs() } answers { lockoutUntil?.let { maxOf(0L, it - now()) } ?: 0L }
        every { getRemainingAttempts() } returns 0
    }

    private fun viewModel(repository: SettingsRepository): AuthViewModel {
        val accountRepository = mockk<AccountRepository>(relaxed = true) { every { activeAccount } returns flowOf(null) }
        return AuthViewModel(accountRepository, repository)
    }

    private fun countdown(seconds: Int): String =
        composeRule.activity.resources.getQuantityString(R.plurals.pin_lockout_countdown, seconds, seconds)

    private fun typeWrongPin() {
        repeat(6) {
            composeRule.onNodeWithText("1").performClick()
            composeRule.waitForIdle()
        }
    }

    private fun assertKeypadShown() {
        composeRule.onNodeWithText("1").assertExists()
        composeRule.onNodeWithText("0").assertExists()
    }

    private fun assertKeypadHidden() {
        composeRule.onNodeWithText("1").assertDoesNotExist()
        composeRule.onNodeWithText("0").assertDoesNotExist()
    }

    /** Counts the lockout down second by second, then expects the keypad back and the message gone. */
    private fun assertCountsDownThenKeypadReturns() {
        assertKeypadHidden()
        composeRule.onNodeWithText(countdown(30)).assertExists()

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText(countdown(29)).assertExists()
        composeRule.onNodeWithText(countdown(30)).assertDoesNotExist()
        assertKeypadHidden()

        composeRule.mainClock.advanceTimeBy(28_000)
        composeRule.onNodeWithText(countdown(1)).assertExists()
        assertKeypadHidden()

        composeRule.mainClock.advanceTimeBy(1_500)
        assertKeypadShown()
        composeRule.onNodeWithText(countdown(1)).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.try_again_later)).assertDoesNotExist()
    }

    @Test
    fun `Given the unlock gate without back when the 5th wrong PIN locks out then the keypad returns after the countdown`() {
        val repository = lockingRepository()
        composeRule.setContent {
            PinScreen(viewModel = viewModel(repository), onPinVerified = {}, onBackClick = {}, showBack = false)
        }
        composeRule.waitForIdle()
        assertKeypadShown()

        typeWrongPin()

        assertCountsDownThenKeypadReturns()
    }

    @Test
    fun `Given the Settings turn-off-App-Lock prompt when a wrong PIN locks out then the keypad returns after the countdown`() {
        val repository = lockingRepository()
        composeRule.setContent {
            PinScreen(
                viewModel = viewModel(repository),
                onPinVerified = {},
                onBackClick = {},
                showBack = true,
                subtitle = composeRule.activity.getString(R.string.app_lock_disable_pin_subtitle),
                // Same per-digit check SettingsViewModel.enterPinToDisableAppLock goes through.
                enterPinDigit = { digit, current -> repository.checkPinDigit(digit, current) },
            )
        }
        composeRule.waitForIdle()

        typeWrongPin()

        assertCountsDownThenKeypadReturns()
    }

    @Test
    fun `Given a lockout already running when the unlock gate opens then it counts down and the keypad returns`() {
        val repository = lockingRepository()
        lockoutUntil = now() + lockoutMs
        settingsFlow.value = settingsFlow.value.copy(failedPinAttempts = 5, pinLockoutUntil = lockoutUntil)
        composeRule.setContent {
            PinScreen(viewModel = viewModel(repository), onPinVerified = {}, onBackClick = {}, showBack = false)
        }
        composeRule.waitForIdle()

        assertCountsDownThenKeypadReturns()
    }

    @Test
    fun `Given the keypad came back when the next wrong PIN locks out again then it counts down again`() {
        val repository = lockingRepository()
        composeRule.setContent {
            PinScreen(viewModel = viewModel(repository), onPinVerified = {}, onBackClick = {}, showBack = false)
        }
        composeRule.waitForIdle()
        typeWrongPin()
        composeRule.mainClock.advanceTimeBy(lockoutMs + 500)
        assertKeypadShown()

        typeWrongPin()

        assertCountsDownThenKeypadReturns()
    }

    @Test
    @Config(qualifiers = "+zh-rTW")
    fun `Given Traditional Chinese when locked out then the countdown is localized`() {
        val repository = lockingRepository()
        composeRule.setContent {
            PinScreen(viewModel = viewModel(repository), onPinVerified = {}, onBackClick = {}, showBack = false)
        }
        composeRule.waitForIdle()

        typeWrongPin()

        composeRule.onNodeWithText("嘗試次數過多，請於 30 秒後再試").assertExists()
    }
}
