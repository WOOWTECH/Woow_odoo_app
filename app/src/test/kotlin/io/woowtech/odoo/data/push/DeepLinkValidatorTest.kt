package io.woowtech.odoo.data.push

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
}
