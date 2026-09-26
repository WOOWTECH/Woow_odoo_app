package io.woowtech.odoo.ui.main

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：主畫面 WebView 區不得延伸到導覽列或鍵盤底下。
 *
 * 背景：`MainActivity` 呼叫 `enableEdgeToEdge()`，視窗不再替 App 讓出系統列，manifest 的
 * `windowSoftInputMode="adjustResize"` 也不會縮小內容（insets 改交給 App 自己處理）。
 * [MainScreen] 沒有 Scaffold，只有 `TopAppBar`（預設只吃 safeDrawing 的上＋左右）避開狀態列；
 * WebView 所在的 Box 直接 `fillMaxSize()` 到螢幕底。結果：三鍵導覽時 Odoo 頁面底部被導覽列蓋住，
 * 鍵盤彈出時 WebView 高度不變，Chromium 不會把焦點輸入框捲進可視區。
 *
 * 做法：渲染抽出的 [MainScreenLayout]（WebView 以帶 testTag 的 Box 代替），對 Compose 根 View
 * 派送合成 insets（狀態列 24dp、導覽列 48dp、鍵盤 300dp、橫向側邊導覽列 48dp），量內容區 bounds。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp")
class MainScreenWindowInsetsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val contentTag = "main_web_content"

    private val density: Float get() = composeRule.activity.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private fun renderAndDispatch(insets: WindowInsetsCompat): Pair<Rect, Rect> {
        composeRule.setContent {
            MainScreenLayout(onMenuClick = {}, banner = {}) {
                Box(modifier = Modifier.fillMaxSize().testTag(contentTag))
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val content = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeRule.waitForIdle()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val web = composeRule.onNodeWithTag(contentTag).fetchSemanticsNode().boundsInRoot
        return root to web
    }

    @Test
    fun `Given three-button navigation bar inset when main screen shown then web content ends above the navigation bar`() {
        val statusPx = dp(24)
        val navPx = dp(48)
        val (root, web) = renderAndDispatch(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                .build(),
        )

        assertTrue("web content top ${web.top}px must be below status bar ${statusPx}px", web.top >= statusPx)
        assertTrue(
            "web content bottom ${web.bottom}px must be <= nav bar top ${root.bottom - navPx}px",
            web.bottom <= root.bottom - navPx,
        )
        assertTrue("web content must keep a usable height, was ${web.height}px", web.height > 0f)
    }

    @Test
    fun `Given keyboard shown when main screen shown then web content ends above the keyboard`() {
        val statusPx = dp(24)
        val navPx = dp(48)
        val imePx = dp(300)
        val (root, web) = renderAndDispatch(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build(),
        )

        assertTrue(
            "web content bottom ${web.bottom}px must be <= keyboard top ${root.bottom - imePx}px",
            web.bottom <= root.bottom - imePx,
        )
        assertTrue("web content must keep a usable height, was ${web.height}px", web.height > 0f)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun `Given landscape side navigation bar when main screen shown then web content does not extend under it`() {
        val statusPx = dp(24)
        val navPx = dp(48)
        val (root, web) = renderAndDispatch(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, navPx, 0))
                .build(),
        )

        assertTrue(
            "web content right ${web.right}px must be <= side nav bar left ${root.right - navPx}px",
            web.right <= root.right - navPx,
        )
    }
}
