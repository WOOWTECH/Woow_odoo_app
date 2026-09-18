package io.woowtech.odoo.ui.theme

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：登入前畫面不得跟著使用者選的主題色變。
 *
 * 背景（2026-09-17）：使用者把主題色設成紫色後，登入頁的「下一步」「登入」按鈕
 * 與伺服器圖示全部變紫。登入前沒有帳號脈絡，沿用上一個帳號的顏色等於把別人的
 * 品牌色漏到一個還不屬於他的畫面上；主題色的用途是讓客戶把 App **裡面**（他自己
 * 的 Odoo）品牌化，登入畫面是本產品自己的門面。
 *
 * 修補方式是 [WoowFixedBrandTheme] 包住 LoginScreen / BiometricScreen / PinScreen。
 * 這個測試直接驗包裝本身的契約，不需要裝置、不需要 hiltViewModel。
 *
 * 兩個方向都驗，否則測試沒有意義：
 *  1. 沒有包裝時，primary **確實**會變成使用者選的顏色（證明這個測試抓得到問題）
 *  2. 包了之後，primary **固定**是 [WoowTechBlue]
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class FixedBrandThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 一個明顯不是品牌藍的顏色，用來確認「跟著變」與「不跟著變」的差異。 */
    private val userPickedGreen = Color(0xFF00C853)

    @After
    fun restoreThemeManager() {
        // ThemeManager 是 process 範圍的 singleton，測試改過就要還原，
        // 否則會污染同一個 JVM 上後續的測試。
        ThemeManager.setPrimaryColor(WoowTechBlue)
    }

    @Test
    fun `Given user picked a non-brand colour when inside WoowFixedBrandTheme then primary stays brand blue`() {
        ThemeManager.setPrimaryColor(userPickedGreen)

        var unwrapped: Color? = null
        var wrapped: Color? = null

        composeRule.setContent {
            WoowTechOdooTheme {
                // 1) 沒包裝 —— 應該跟著使用者選的顏色
                unwrapped = MaterialTheme.colorScheme.primary
                // 2) 包了 —— 應該固定品牌藍
                WoowFixedBrandTheme {
                    wrapped = MaterialTheme.colorScheme.primary
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(
            "沒有包裝時 primary 應該等於使用者選的顏色 —— 若這條失敗，代表這個測試" +
                "已經抓不到問題了（主題機制本身壞掉或被改掉），必須先修這裡",
            userPickedGreen,
            unwrapped,
        )
        assertEquals(
            "WoowFixedBrandTheme 內的 primary 必須固定為 WoowTechBlue，" +
                "不得跟隨使用者選的主題色",
            WoowTechBlue,
            wrapped,
        )
        assertNotEquals(
            "測試設定有誤：使用者選的顏色不該等於品牌藍，否則驗不出差異",
            WoowTechBlue,
            userPickedGreen,
        )
    }

    @Test
    fun `Given default theme when inside WoowFixedBrandTheme then primary is still brand blue`() {
        ThemeManager.setPrimaryColor(WoowTechBlue)

        var wrapped: Color? = null
        composeRule.setContent {
            WoowTechOdooTheme {
                WoowFixedBrandTheme { wrapped = MaterialTheme.colorScheme.primary }
            }
        }
        composeRule.waitForIdle()

        assertEquals(WoowTechBlue, wrapped)
    }
}
