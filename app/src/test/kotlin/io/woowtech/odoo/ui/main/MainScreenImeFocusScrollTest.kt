package io.woowtech.odoo.ui.main

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：鍵盤彈出時 (1) WebView「本身」的 View 尺寸要縮小（不只是外層容器 padding，
 * 否則 Chromium 看不到 viewport 變化）；(2) 鍵盤顯示落定後 [MainScreenLayout] 要呼叫一次
 * `onImeShown`，由 MainScreen 對 WebView 補捲焦點欄位（見 [ImeFocusScrollTrigger] 的根因說明）。
 *
 * WebView 以一般 Android View 代替（Robolectric 沒有 Chromium），同樣經 AndroidView 放進內容區。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp")
class MainScreenImeFocusScrollTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val density: Float get() = composeRule.activity.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private var imeShownCalls = 0
    private lateinit var embeddedView: View

    private fun render() {
        composeRule.setContent {
            MainScreenLayout(onMenuClick = {}, banner = {}, onImeShown = { imeShownCalls++ }) {
                AndroidView(
                    factory = { ctx -> View(ctx).also { embeddedView = it } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun dispatch(insets: WindowInsetsCompat) {
        composeRule.runOnUiThread {
            val content = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeRule.waitForIdle()
    }

    private fun insets(imePx: Int): WindowInsetsCompat {
        val builder = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, dp(24), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, dp(48)))
        if (imePx > 0) {
            builder.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
        }
        return builder.build()
    }

    private fun embeddedBottomInWindow(): Int {
        val loc = IntArray(2)
        embeddedView.getLocationInWindow(loc)
        return loc[1] + embeddedView.height
    }

    private fun rootHeight(): Int =
        composeRule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).height

    @Test
    fun `Given keyboard shown when main screen laid out then the embedded web view itself shrinks above the keyboard`() {
        render()
        dispatch(insets(imePx = 0))
        val heightWithoutKeyboard = embeddedView.height

        val imePx = dp(300)
        dispatch(insets(imePx = imePx))

        assertTrue(
            "web view height must shrink (was $heightWithoutKeyboard, now ${embeddedView.height})",
            embeddedView.height < heightWithoutKeyboard,
        )
        assertTrue(
            "web view bottom ${embeddedBottomInWindow()}px must be <= keyboard top ${rootHeight() - imePx}px",
            embeddedBottomInWindow() <= rootHeight() - imePx,
        )
    }

    @Test
    fun `Given keyboard hidden when keyboard becomes visible then onImeShown fires exactly once`() {
        render()
        dispatch(insets(imePx = 0))
        assertEquals(0, imeShownCalls)

        dispatch(insets(imePx = dp(300)))
        dispatch(insets(imePx = dp(300)))

        assertEquals(1, imeShownCalls)
    }

    @Test
    fun `Given keyboard shown then hidden when shown again then onImeShown fires again`() {
        render()
        dispatch(insets(imePx = dp(300)))
        dispatch(insets(imePx = 0))
        dispatch(insets(imePx = dp(300)))

        assertEquals(2, imeShownCalls)
    }

    @Test
    fun `Given only system bars change when keyboard never shows then onImeShown is not called`() {
        render()
        dispatch(insets(imePx = 0))
        dispatch(insets(imePx = 0))

        assertEquals(0, imeShownCalls)
    }
}
