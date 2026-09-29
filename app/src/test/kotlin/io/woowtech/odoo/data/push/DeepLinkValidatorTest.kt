package io.woowtech.odoo.data.push

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DeepLinkValidatorTest {

    private val serverHost = "odoo.example.com"

    @ParameterizedTest
    @ValueSource(strings = [
        "javascript:alert(1)",
        "JAVASCRIPT:alert('xss')",
        "data:text/html,<script>alert(1)</script>",
        "DATA:text/html;base64,PHNjcmlwdD4=",
        "",
        "   "
    ])
    fun `Given malicious or empty URL when validate then rejected`(url: String) {
        assertFalse(DeepLinkValidator.isValid(url = url, serverHost = serverHost))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/web#id=42&model=sale.order&view_type=form",
        "/web#action=contacts",
        "/web/login",
        "/web"
    ])
    fun `Given valid Odoo relative path when validate then accepted`(url: String) {
        assertTrue(DeepLinkValidator.isValid(url = url, serverHost = serverHost))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "https://evil.com/phish",
        "https://attacker.example.com/fake",
        "ftp://files.example.com"
    ])
    fun `Given external host URL when validate then rejected`(url: String) {
        assertFalse(DeepLinkValidator.isValid(url = url, serverHost = serverHost))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        // Prefix-spoofing: share the "/web" prefix but "/web" is not a real path segment.
        "/website/attacker",
        "/webhook",
        "/web@evil.com",
        // Path traversal must be rejected anywhere in the URL.
        "/web/../secret",
        "/web/..%2fsecret",
        "/web#../../etc/passwd"
    ])
    fun `Given prefix-spoof or traversal relative path when validate then rejected`(url: String) {
        assertFalse(DeepLinkValidator.isValid(url = url, serverHost = serverHost))
    }

    // ──────────────────────────────────────────────────────────
    // 推播點擊連結加嚴到與 iOS 一致（擁有者 2026-09-29 核准，OWNER-APPROVAL-PUSH-LINK-20260929）。
    // 規格以 iOS `DeepLinkValidator.isValid` 為準：相對只收 ^/web([/?#]|$)；絕對須 https 且與目前
    // 帳號伺服器同 host；拒控制字元（Cc＋Cf）與 `..`／`%2e%2e`（含大小寫與半編碼）路徑跳脫。
    // ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = [
        "https://odoo.example.com/web#action=calendar.action_calendar_event",
        "https://ODOO.example.com/web#id=7&model=res.partner&view_type=form",
        "HTTPS://odoo.example.com/odoo/calendar", // same host absolute: any path, like iOS
        "/web?debug=1",
        "/web/",
    ])
    fun `Given a relative web path or a same-host https URL when validate then accepted like iOS`(url: String) {
        assertTrue(DeepLinkValidator.isValid(url = url, serverHost = serverHost))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        // Same host but not https — iOS requires the https scheme for every absolute URL.
        "http://odoo.example.com/web#action=calendar.action_calendar_event",
        "HTTP://odoo.example.com/web",
        "ftp://odoo.example.com/web",
        "intent://odoo.example.com/web#Intent;scheme=https;end",
        "content://odoo.example.com/web",
        "file://odoo.example.com/etc/passwd",
        "wss://odoo.example.com/web",
        // No scheme at all (scheme-relative) is not a relative /web path either.
        "//odoo.example.com/web",
        // Other hosts, including look-alikes and userinfo tricks.
        "https://odoo.example.com.evil.com/web",
        "https://evil.com/web?next=odoo.example.com",
        "https://odoo.example.com@evil.com/web",
        // Other schemes.
        "vbscript:msgbox(1)",
        "blob:https://odoo.example.com/0c4f",
        "about:blank",
    ])
    fun `Given a non-https or foreign absolute URL when validate then rejected like iOS`(url: String) {
        assertFalse(DeepLinkValidator.isValid(url = url, serverHost = serverHost))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/web/%2e%2e/web",
        "/web/%2E%2E/web",
        "/web/%2e%2E/web",
        "/web/.%2e/web", // WHATWG treats half-encoded dot segments as ".." too
        "/web/%2E./web",
        "/web#%2e%2e/%2e%2e/etc/passwd",
        "https://odoo.example.com/web/%2e%2e/admin",
        "/web/../web",
        "/odoo/calendar",
        "/website/info",
        "/WEB#action=x", // Odoo paths are lowercase; iOS matches strictly
    ])
    fun `Given an encoded traversal or a non-web relative path when validate then rejected`(url: String) {
        assertFalse(DeepLinkValidator.isValid(url = url, serverHost = serverHost))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/web#id=1\u0000",
        "/web#id=1\ttab",
        "/web\r\nSet-Cookie: a=b",
        "/web#id=1\u007f",
        "/web#id=1\u0085",
        "/web#id=1\u200b", // zero-width space (Cf) — iOS controlCharacters covers Cc and Cf
        "/web#id=1\u202e", // right-to-left override (Cf)
        "https://odoo.example.com/web#\u2066x",
    ])
    fun `Given control or format characters inside the URL when validate then rejected`(url: String) {
        assertFalse(DeepLinkValidator.isValid(url = url, serverHost = serverHost))
    }

    @Test
    fun `Given whitespace around a URL when validate then rejected rather than normalized`() {
        // Android is stricter than iOS here on purpose: callers load the exact string they validated,
        // so the validator never accepts a value that would need trimming first.
        assertFalse(DeepLinkValidator.isValid(url = " /web#id=1", serverHost = serverHost))
        assertFalse(DeepLinkValidator.isValid(url = "/web#id=1\n", serverHost = serverHost))
    }

    @Test
    fun `Given no active server host when validate then relative web path still passes and absolute fails`() {
        assertTrue(DeepLinkValidator.isValid(url = "/web#id=1", serverHost = ""))
        assertFalse(DeepLinkValidator.isValid(url = "https://odoo.example.com/web", serverHost = ""))
    }
}
