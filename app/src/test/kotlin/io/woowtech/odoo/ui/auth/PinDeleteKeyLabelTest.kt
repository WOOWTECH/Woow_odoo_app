package io.woowtech.odoo.ui.auth

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線（2026-09-29 verify-merge-20260929 發現、擁有者同批核准修正）：PIN 鍵盤的刪除鍵
 * contentDescription 寫死英文 "Delete"，zh-TW／zh-CN 讀屏仍念英文。PinScreen（解鎖、確認目前 PIN）
 * 與 PinSetupScreen（設定 PIN）共用同一個 [NumberPad]，所以兩處一起修。
 *
 * 期望值取自譯文（iOS 用 SF Symbol `delete.backward`，系統讀作「刪除」／「删除」／"Delete"）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class PinDeleteKeyLabelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var deletes = 0

    private fun showPad() {
        composeRule.setContent {
            NumberPad(reduceMotion = true, onNumberClick = {}, onDeleteClick = { deletes++ })
        }
        composeRule.waitForIdle()
    }

    private fun assertDeleteKeyReadsAs(label: String) {
        showPad()
        composeRule.onNodeWithContentDescription(label).performClick()
        composeRule.waitForIdle()
        assertEquals(1, deletes)
    }

    @Test
    @Config(qualifiers = "zh-rTW-w411dp-h891dp")
    fun `Given zh-TW when the PIN pad is shown then the delete key reads in Traditional Chinese`() {
        assertDeleteKeyReadsAs("刪除")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp")
    fun `Given zh-CN when the PIN pad is shown then the delete key reads in Simplified Chinese`() {
        assertDeleteKeyReadsAs("删除")
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp")
    fun `Given English when the PIN pad is shown then the delete key reads Delete`() {
        assertDeleteKeyReadsAs("Delete")
    }
}
