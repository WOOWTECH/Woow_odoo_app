package io.woowtech.odoo.ui.config

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.R
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.CacheRepository
import io.woowtech.odoo.data.repository.FcmTokenRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線（2026-09-29 verify-merge-20260929 發現、擁有者同批核准修正）：
 * 從設定頁「App 鎖定」開關（尚未設 PIN）進入 PinSetupScreen 後按系統返回，畫面直接跳過設定頁
 * 回到上一層面板；畫面左上角返回鍵則是取消設定、回設定頁。根因：SettingsScreen 以 `if (showPinSetup)`
 * 疊出 PinSetupScreen，卻沒有對應的 BackHandler（同頁其他 PIN 疊層都有），系統返回交給了 NavHost。
 *
 * 期望：系統返回＝畫面返回鍵——取消設定、回到設定頁、不設 PIN、App 鎖定維持關閉、不離開 Activity。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PinSetupSystemBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repository: SettingsRepository

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun showSettingsWithoutPin() {
        repository = mockk(relaxed = true) {
            every { this@mockk.settings } returns MutableStateFlow(AppSettings(appLockEnabled = false, pinEnabled = false))
        }
        val cacheRepository = mockk<CacheRepository>(relaxed = true)
        coEvery { cacheRepository.calculateCacheSize() } returns 0L
        val viewModel = SettingsViewModel(
            repository,
            cacheRepository,
            mockk<AccountRepository>(relaxed = true).also { every { it.activeAccount } returns MutableStateFlow(null) },
            mockk<FcmTokenRepository>(relaxed = true).also { every { it.registrationStatuses } returns MutableStateFlow(emptyMap()) },
        )
        composeRule.setContent { SettingsScreen(viewModel = viewModel, onBackClick = {}) }
        composeRule.waitForIdle()
    }

    private fun openPinSetupFromAppLockSwitch() {
        composeRule.onNodeWithText(string(R.string.app_lock)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.pin_setup_title)).assertExists()
    }

    private fun assertBackOnSettingsWithAppLockStillOff() {
        composeRule.onNodeWithText(string(R.string.pin_setup_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.app_lock)).assertExists()
        coVerify(exactly = 0) { repository.setPin(any()) }
        verify(exactly = 0) { repository.updateAppLock(any()) }
        assertFalse("system back must not leave the activity", composeRule.activity.isFinishing)
    }

    @Test
    fun `Given PIN setup opened from the App Lock switch when system back is pressed then Settings returns and App Lock stays off`() {
        showSettingsWithoutPin()
        openPinSetupFromAppLockSwitch()

        composeRule.runOnUiThread {
            assertTrue(
                "PIN setup must intercept system back",
                composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks(),
            )
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        assertBackOnSettingsWithAppLockStillOff()
    }

    @Test
    fun `Given PIN setup opened from the App Lock switch when the on-screen back is tapped then the same happens`() {
        showSettingsWithoutPin()
        openPinSetupFromAppLockSwitch()

        composeRule.onNodeWithContentDescription(string(R.string.back_button)).performClick()
        composeRule.waitForIdle()

        assertBackOnSettingsWithAppLockStillOff()
    }
}
