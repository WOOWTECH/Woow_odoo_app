package io.woowtech.odoo.ui.main

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * pi 第四次回歸複查（PI-REVIEW-0929-FIX-RECHECK-4）的 P2：帳號目標的 process-wide token 原本在「組合中」
 * 就取得（`remember { beginTarget() }`）。新目標的組合若在提交前被捨棄，仍在顯示中的舊 WebView 的 token
 * 已被作廢，新 token 又沒有已安裝的 effect 會釋放，舊頁的回呼與 cookie 工作都會被拒絕，載入與待處理連結卡住。
 *
 * 期望：token 只在組合「提交」時取得（RememberObserver.onRemembered），離開時（onForgotten）交回；
 * 被捨棄的組合（onAbandoned）完全不碰全域 token 與本組合的目標計數。
 *
 * 用真的 Compose runtime（Composition + 空的 Applier）驗證提交／捨棄語意（Robolectric 只提供 android.os.Trace）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebViewTargetTokenTest {

    private val coordinator = WebViewCookieCoordinator()

    @Test
    fun `Given a displayed target when a new target's composition is abandoned before commit then the displayed one stays current`() {
        val displayed = compose { rememberWebViewTargetToken(coordinator) }.value
        assertTrue(displayed.isCurrent())

        val abandoned = compose<WebViewTargetToken> {
            rememberWebViewTargetToken(coordinator)
            throw DiscardComposition()
        }

        assertTrue(abandoned.error is DiscardComposition)
        assertTrue("an uncommitted target must not supersede the displayed one", displayed.isCurrent())
    }

    @Test
    fun `Given a target composition when it is still being composed then it is not current yet`() {
        var currentDuringComposition: Boolean? = null
        val token = compose {
            rememberWebViewTargetToken(coordinator).also { currentDuringComposition = it.isCurrent() }
        }.value
        assertEquals(false, currentDuringComposition)
        assertTrue("current once the composition is committed", token.isCurrent())
    }

    @Test
    fun `Given a committed target when a later target is committed then the earlier one is superseded`() {
        val first = compose { rememberWebViewTargetToken(coordinator) }.value
        val second = compose { rememberWebViewTargetToken(coordinator) }.value
        assertFalse(first.isCurrent())
        assertTrue(second.isCurrent())
    }

    @Test
    fun `Given a committed target when its composition is disposed then it is no longer current`() {
        val result = compose { rememberWebViewTargetToken(coordinator) }
        result.composition!!.dispose()
        assertFalse(result.value.isCurrent())
    }


    private class DiscardComposition : RuntimeException("composition discarded before commit")

    private class Result<T>(val value: T, val composition: Composition?, val error: Throwable?)

    private fun <T> compose(content: @Composable () -> T): Result<T> {
        var value: T? = null
        val composition = Composition(UnitApplier(), Recomposer(EmptyCoroutineContext))
        val error = runCatching { composition.setContent { value = content() } }.exceptionOrNull()
        if (error != null && error !is DiscardComposition) throw error
        @Suppress("UNCHECKED_CAST")
        return Result(value as T, if (error == null) composition else null, error)
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
