package io.woowtech.odoo.ui

import io.woowtech.odoo.data.push.DeepLinkValidator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for DeepLinkValidator with host validation (C2 fix).
 *
 * These tests verify the contract that MainActivity now enforces:
 * - active account host is passed (not empty string)
 * - no active account → all deep links rejected
 * - external host rejected
 * - relative /web paths still pass
 */
class DeepLinkHostValidationTest {

    @Test
    fun `Given active account host when isValid with matching host URL then returns true`() {
        assertTrue(
            DeepLinkValidator.isValid(
                url = "https://odoo.example.com/web#action=stock.action_picking_tree_all",
                serverHost = "odoo.example.com",
            )
        )
    }

    @Test
    fun `Given no active account (empty host) when isValid then returns false for absolute URL`() {
        // C2: empty string is what the old code passed — ensure it rejects absolute URLs
        assertFalse(
            DeepLinkValidator.isValid(
                url = "https://odoo.example.com/web#action=123",
                serverHost = "",
            )
        )
    }

    @Test
    fun `Given external host URL when isValid then returns false`() {
        assertFalse(
            DeepLinkValidator.isValid(
                url = "https://evil.attacker.com/steal",
                serverHost = "odoo.example.com",
            )
        )
    }

    @Test
    fun `Given relative web path when isValid then returns true regardless of host`() {
        // Relative /web paths are always safe — they are relative to the loaded server
        assertTrue(
            DeepLinkValidator.isValid(
                url = "/web#action=sale.action_quotations_with_onboarding",
                serverHost = "odoo.example.com",
            )
        )
    }

    @Test
    fun `Given javascript scheme when isValid then returns false`() {
        assertFalse(
            DeepLinkValidator.isValid(
                url = "javascript:alert('xss')",
                serverHost = "odoo.example.com",
            )
        )
    }

    @Test
    fun `Given data scheme when isValid then returns false`() {
        assertFalse(
            DeepLinkValidator.isValid(
                url = "data:text/html,<h1>hi</h1>",
                serverHost = "odoo.example.com",
            )
        )
    }
}
