package io.woowtech.odoo.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for [FcmRegistrationResponse.parseTenantId] — the persistence source for each account's
 * opaque tenant id, obtained at device registration.
 */
class FcmRegistrationResponseTest {

    @Test
    fun `Given result with tenant_id when parse then returns it`() {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"status":"ok","tenant_id":"tenant-B"}}"""
        assertEquals("tenant-B", FcmRegistrationResponse.parseTenantId(body))
    }

    @Test
    fun `Given result with odoo_tenant_id alias when parse then returns it`() {
        val body = """{"result":{"odoo_tenant_id":"tenant-XYZ"}}"""
        assertEquals("tenant-XYZ", FcmRegistrationResponse.parseTenantId(body))
    }

    @Test
    fun `Given numeric tenant id when parse then returns string form`() {
        val body = """{"result":{"tenant_id":4242}}"""
        assertEquals("4242", FcmRegistrationResponse.parseTenantId(body))
    }

    @Test
    fun `Given response without tenant id (old server) when parse then null`() {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}"""
        assertNull(FcmRegistrationResponse.parseTenantId(body))
    }

    @Test
    fun `Given response without result object when parse then null`() {
        val body = """{"jsonrpc":"2.0","id":1}"""
        assertNull(FcmRegistrationResponse.parseTenantId(body))
    }

    @Test
    fun `Given blank tenant id when parse then null`() {
        val body = """{"result":{"tenant_id":""}}"""
        assertNull(FcmRegistrationResponse.parseTenantId(body))
    }

    @Test
    fun `Given malformed json when parse then null`() {
        assertNull(FcmRegistrationResponse.parseTenantId("not-json"))
    }

    @Test
    fun `Given null or blank body when parse then null`() {
        assertNull(FcmRegistrationResponse.parseTenantId(null))
        assertNull(FcmRegistrationResponse.parseTenantId(""))
    }
}
