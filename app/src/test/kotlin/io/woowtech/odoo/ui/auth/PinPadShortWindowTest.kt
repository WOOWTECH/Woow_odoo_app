package io.woowtech.odoo.ui.auth

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.woowtech.odoo.R
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 回歸防線：短視窗下 PIN 鍵盤第四列（0 與刪除）不得被壓成 0 高度或畫到可視區外。
 *
 * 背景（2026-09-26 獨立審查 PI-REVIEW-LATEST-MOBILE，Spec Android P1）：`76aacd7` 讓
 * [PinSetupScreen] 與 [PinScreen] 的根 Column 吃 `WindowInsets.safeDrawing`（返回鍵不再被狀態列
 * 蓋住），但 Column 沒有捲動。360×640dp、狀態列 24dp／導覽列 48dp 時，可用高度剩 520dp，
 * 前方固定內容約 256dp，共用 [NumberPad]（4 列 × 76dp、間距 16dp＝336dp）只剩約 264dp，
 * 第四列被壓縮。`76aacd7` 上實測：兩個畫面、fontScale 1 與 1.3，0 與刪除鍵量測尺寸都是 76×0、
 * 位置 y＝568（可視區底 592 減 padding 24），boundsInRoot 為空。修法：inset 之後加 verticalScroll
 * （與本測試同一個 commit）。
 *
 * 做法：Robolectric（NATIVE graphics，才有真實字型量測，字級放大才會真的撐高文字）以 qualifiers 設定視窗大小，對 Compose 根 View 派送合成的狀態列＋導覽列
 * insets，量 0 與刪除鍵的 bounds，並實際點擊確認輸入（數字／刪除）有生效。長視窗另以
 * `76aacd7` 實測值斷言版面不變。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PinPadShortWindowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val statusBarDp = 24
    private val navBarDp = 48
    private val keySizeDp = 76f

    private val density: Float get() = composeRule.activity.resources.displayMetrics.density
    private val statusBarPx: Int get() = (statusBarDp * density).toInt()
    private val navBarPx: Int get() = (navBarDp * density).toInt()

    // ---- setup (create PIN) ----

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `Given 360x640 window with system bars when PinSetupScreen shown then zero and delete keys are visible and work`() {
        assertSetupPadUsable(fontScale = 1f)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `Given 360x640 window and font scale 1_3 when PinSetupScreen shown then zero and delete keys are visible and work`() {
        assertSetupPadUsable(fontScale = 1.3f)
    }

    // ---- verify (unlock, reached from biometric so it has a back button) ----

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `Given 360x640 window with system bars when PinScreen with back shown then zero and delete keys are visible and work`() {
        assertVerifyPadUsable(fontScale = 1f)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `Given 360x640 window and font scale 1_3 when PinScreen with back shown then zero and delete keys are visible and work`() {
        assertVerifyPadUsable(fontScale = 1.3f)
    }

    // ---- long window: layout must not change ----

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `Given 411x891 window when PinSetupScreen shown then keypad layout is unchanged`() {
        showWithBars(fontScale = 1f) { PinSetupScreen(onPinConfirmed = {}, onCancel = {}) }
        // 76aacd7 實測（mdpi，1dp＝1px）：0 鍵 [168,719][244,795]、刪除鍵 [272,719][348,795]；
        // 底部 24dp spacer＋24dp padding 後貼齊導覽列上緣 843dp。
        assertLongWindowLayout(zeroTopDp = 719f, zeroLeftDp = 168f, deleteLeftDp = 272f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `Given 411x891 window when PinScreen with back shown then keypad layout is unchanged`() {
        val (viewModel, _) = verifyViewModel()
        showWithBars(fontScale = 1f) {
            PinScreen(viewModel = viewModel, onPinVerified = {}, onBackClick = {}, showBack = true)
        }
        // 76aacd7 實測：0 鍵 [168,703][244,779]（底部 40dp spacer，比 setup 高 16dp）。
        assertLongWindowLayout(zeroTopDp = 703f, zeroLeftDp = 168f, deleteLeftDp = 272f)
    }

    // ---- helpers ----

    private fun assertSetupPadUsable(fontScale: Float) {
        var confirmed: String? = null
        showWithBars(fontScale) { PinSetupScreen(onPinConfirmed = { confirmed = it }, onCancel = {}) }
        println(
            "PIN_PAD_TEXT fontScale=$fontScale titleDp=${textHeightDp(R.string.pin_setup_title)} " +
                "subtitleDp=${textHeightDp(R.string.pin_setup_subtitle)}",
        )
        assertFontScaleApplied(fontScale, R.string.pin_setup_subtitle)

        logKey(zeroKey(), "0")
        logKey(deleteKey(), "Delete")
        assertKeyVisibleAndClickable(zeroKey(), "0")
        assertKeyVisibleAndClickable(deleteKey(), "Delete")

        // ENTER: 1 2 3 4 5 ⌫ 0 0 → "123400"（刪除與 0 都要生效才會剛好 6 碼）→ 自動進 CONFIRM。
        typeWithDeleteAndZero()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.pin_setup_confirm_title))
            .assertExists()
        // CONFIRM: 同一組輸入，吻合才會回呼。
        typeWithDeleteAndZero()
        composeRule.waitForIdle()
        assertEquals("delete and 0 must both register on the short window", "123400", confirmed)
    }

    private fun assertVerifyPadUsable(fontScale: Float) {
        val (viewModel, entered) = verifyViewModel()
        var verified = false
        showWithBars(fontScale) {
            PinScreen(viewModel = viewModel, onPinVerified = { verified = true }, onBackClick = {}, showBack = true)
        }
        println(
            "PIN_PAD_TEXT fontScale=$fontScale titleDp=${textHeightDp(R.string.enter_pin)} " +
                "subtitleDp=${textHeightDp(R.string.enter_pin_subtitle)}",
        )
        assertFontScaleApplied(fontScale, R.string.enter_pin_subtitle)

        logKey(zeroKey(), "0")
        logKey(deleteKey(), "Delete")
        assertKeyVisibleAndClickable(zeroKey(), "0")
        assertKeyVisibleAndClickable(deleteKey(), "Delete")

        typeWithDeleteAndZero()
        composeRule.waitForIdle()
        assertEquals("delete and 0 must both register on the short window", "123400", entered.captured)
        assertTrue("onPinVerified must fire", verified)
    }

    private fun verifyViewModel(): Pair<AuthViewModel, io.mockk.CapturingSlot<String>> {
        val entered = slot<String>()
        val accountRepository = mockk<AccountRepository>(relaxed = true) {
            every { activeAccount } returns flowOf(null)
        }
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { settings } returns MutableStateFlow(AppSettings())
            every { getLockoutRemainingMs() } returns 0L
            coEvery { verifyPin(capture(entered)) } returns true
        }
        return AuthViewModel(accountRepository, settingsRepository) to entered
    }

    private fun showWithBars(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                content()
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val root = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(root.getChildAt(0), insets)
        }
        composeRule.waitForIdle()
    }

    /** 字級生效的證據：標題與副標的量測高度（dp）。 */
    private fun textHeightDp(resId: Int): Float =
        composeRule.onNodeWithText(composeRule.activity.getString(resId)).fetchSemanticsNode().size.height / density

    /** 防假綠燈：放大字級的案例必須真的放大了副標（bodyMedium 1.0 倍時一行 20dp）。 */
    private fun assertFontScaleApplied(fontScale: Float, subtitleRes: Int) {
        if (fontScale > 1f) {
            val h = textHeightDp(subtitleRes)
            assertTrue("font scale $fontScale not applied: subtitle ${h}dp", h > 20f)
        }
    }

    private fun zeroKey() = composeRule.onNodeWithText("0")
    private fun deleteKey() = composeRule.onNodeWithContentDescription("Delete")

    /** 1 2 3 4 5 ⌫ 0 0 */
    private fun typeWithDeleteAndZero() {
        listOf("1", "2", "3", "4", "5").forEach { tap(composeRule.onNodeWithText(it)) }
        tap(deleteKey())
        tap(zeroKey())
        tap(zeroKey())
    }

    private fun tap(node: SemanticsNodeInteraction) {
        revealIfScrollable(node)
        node.performClick()
        composeRule.waitForIdle()
    }

    /** 使用者會做的事：有可捲動的祖先就捲到看得見；沒有就原地量。 */
    private fun revealIfScrollable(node: SemanticsNodeInteraction) {
        var parent: SemanticsNode? = node.fetchSemanticsNode().parent
        while (parent != null) {
            if (parent.config.contains(SemanticsActions.ScrollBy)) {
                node.performScrollTo()
                return
            }
            parent = parent.parent
        }
    }

    private fun safeViewport(): Rect {
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        return Rect(root.left, statusBarPx.toFloat(), root.right, root.bottom - navBarPx)
    }

    /** 證據：clip 後的 boundsInRoot 與未 clip 的量測尺寸／位置都印出來（在 revealIfScrollable 之前）。 */
    private fun logKey(node: SemanticsNodeInteraction, label: String) {
        val n = node.fetchSemanticsNode()
        println(
            "PIN_PAD_BEFORE_REVEAL key=$label density=$density boundsInRoot=${n.boundsInRoot} " +
                "measuredSize=${n.size} positionInRoot=${n.positionInRoot} safeViewport=${safeViewport()}",
        )
    }

    private fun assertKeyVisibleAndClickable(node: SemanticsNodeInteraction, label: String) {
        revealIfScrollable(node)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = safeViewport()
        val visible = bounds.intersect(viewport)
        println(
            "PIN_PAD_BOUNDS key=$label density=$density bounds=$bounds " +
                "heightDp=${bounds.height / density} safeViewport=$viewport",
        )
        assertTrue(
            "$label key height ${bounds.height}px must be a full ${keySizeDp}dp key (bounds=$bounds)",
            abs(bounds.height - keySizeDp * density) <= 1f,
        )
        assertTrue(
            "$label key must lie fully inside the safe viewport $viewport (bounds=$bounds, visible=$visible)",
            !visible.isEmpty && abs(visible.height - bounds.height) <= 1f,
        )
        node.assertIsDisplayed()
        node.assertHasClickAction()
    }

    private fun assertLongWindowLayout(zeroTopDp: Float, zeroLeftDp: Float, deleteLeftDp: Float) {
        val zero = zeroKey().fetchSemanticsNode().boundsInRoot
        val delete = deleteKey().fetchSemanticsNode().boundsInRoot
        val back = composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.back_button))
            .fetchSemanticsNode().boundsInRoot
        println("PIN_PAD_LONG zero=$zero delete=$delete back=$back density=$density")
        // 返回圖示 semantics 節點：狀態列 24＋padding 24＋IconButton 內距 4（76aacd7 實測 52dp）。
        assertEquals("back top", (statusBarDp + 24 + 4) * density, back.top, 1f)
        assertEquals("0 key top", zeroTopDp * density, zero.top, 1f)
        assertEquals("0 key left", zeroLeftDp * density, zero.left, 1f)
        assertEquals("0 key height", keySizeDp * density, zero.height, 1f)
        assertEquals("delete top", zero.top, delete.top, 1f)
        assertEquals("delete left", deleteLeftDp * density, delete.left, 1f)
        assertEquals("delete height", keySizeDp * density, delete.height, 1f)
    }
}
