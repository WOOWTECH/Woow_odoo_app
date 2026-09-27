package io.woowtech.odoo.ui.main

import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 實機缺陷（2026-09-27，live-apporo-ai 22-kbd-bottom-input-gesture）：點聯絡人表單靠下方的 Email 欄，
 * 鍵盤彈出、WebView 本身也縮成 [0,286][1080,1517]，但 3 秒後輸入框仍在鍵盤底下。
 *
 * 根因：Chromium 只在 IME 回報 RESULT_SHOWN 後「第一次」viewport 縮小時捲動焦點欄位一次
 * （`ImeAdapterImpl.onResizeScrollableViewport`），edge-to-edge 下 Compose 逐幀動畫 IME inset，
 * WebView 分多次小幅縮小，這個一次性捲動落空。[ImeFocusScrollTrigger] 決定在鍵盤動畫落定時由 App
 * 自己補一次捲動；[scrollFocusedEditableIntoView] 送出捲動 JS。
 */
class ImeFocusScrollTriggerTest {

    @Test
    fun `Given keyboard hidden when IME inset settles at its target then scroll is requested once`() {
        val trigger = ImeFocusScrollTrigger()

        assertFalse(trigger.onImeInsets(currentBottomPx = 0, targetBottomPx = 0))
        assertTrue(trigger.onImeInsets(currentBottomPx = 883, targetBottomPx = 883))
        assertFalse(trigger.onImeInsets(currentBottomPx = 883, targetBottomPx = 883))
    }

    @Test
    fun `Given keyboard show animation in progress when inset has not reached target then no scroll yet`() {
        val trigger = ImeFocusScrollTrigger()

        val requests = listOf(0, 40, 200, 600, 870).map { trigger.onImeInsets(it, 883) }
        assertEquals(listOf(false, false, false, false, false), requests)
        assertTrue(trigger.onImeInsets(883, 883))
    }

    @Test
    fun `Given keyboard is hiding when inset shrinks toward zero then no scroll is requested`() {
        val trigger = ImeFocusScrollTrigger()
        trigger.onImeInsets(883, 883)

        val requests = listOf(883, 500, 100, 0).map { trigger.onImeInsets(it, 0) }
        assertEquals(listOf(false, false, false, false), requests)
    }

    @Test
    fun `Given keyboard hidden after a show when it is shown again then scroll is requested again`() {
        val trigger = ImeFocusScrollTrigger()
        assertTrue(trigger.onImeInsets(883, 883))
        trigger.onImeInsets(0, 0)

        assertTrue(trigger.onImeInsets(883, 883))
    }

    @Test
    fun `Given a focused WebView field when scroll requested then focused-editable scroll JS is evaluated after posting`() {
        val webView = mockk<WebView>(relaxed = true)
        val posted = slot<Runnable>()
        every { webView.post(capture(posted)) } returns true

        scrollFocusedEditableIntoView(webView)

        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
        assertTrue(posted.isCaptured, "script must be posted so it runs after the WebView layout pass")
        posted.captured.run()
        verify(exactly = 1) { webView.evaluateJavascript(FOCUSED_EDITABLE_SCROLL_JS, null) }
    }

    @Test
    fun `Given the scroll script when inspected then it only scrolls a focused editable that is outside the visual viewport`() {
        val js = FOCUSED_EDITABLE_SCROLL_JS
        assertTrue(js.contains("document.activeElement"), "must target the focused element")
        assertTrue(js.contains("isContentEditable"), "must only act on editable targets")
        assertTrue(js.contains("visualViewport"), "must measure against the shrunk visual viewport")
        assertTrue(js.contains("scrollIntoView({block:'center'"), "must centre the field in view")
    }
}
