package io.woowtech.odoo.data.repository

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Phase 3: strict capability/echo parsing prevents ignored-brand legacy success. */
class ApporoPushContractTest {
    @Test
    fun `Given valid capabilities when checked then v2 and newer are accepted`() {
        listOf(2, 3).forEach { version ->
            ApporoPushContract.requireCapabilities("""{"result":{"push_contract_version":$version,"supported_brands":["woowtech","apporo"]}}""")
        }
    }

    @Test
    fun `Given missing or wrong capability when checked then fail closed`() {
        listOf(
            "{}", "null", "[]", "not JSON", "{\"error\":{}}", "{\"result\":false}",
            """{"result":{"push_contract_version":1,"supported_brands":["apporo"]}}""",
            """{"result":{"push_contract_version":"2","supported_brands":["apporo"]}}""",
            """{"result":{"push_contract_version":2.5,"supported_brands":["apporo"]}}""",
            """{"result":{"push_contract_version":2,"supported_brands":["woowtech"]}}""",
            """{"result":{"push_contract_version":2,"supported_brands":"apporo"}}""",
            """{"result":{"error":"sensitive fixture","push_contract_version":2,"supported_brands":["apporo"]}}""",
        ).forEach { body ->
            val error = assertThrows(PushContractException::class.java) { ApporoPushContract.requireCapabilities(body) }
            assertEquals(PushRegistrationStatus.NOT_CONFIGURED, error.status)
            assertFalse(error.message.orEmpty().contains("sensitive"))
        }
    }

    @Test
    fun `Given wrong or absent echo when register acknowledged then reject`() {
        listOf(
            "{}", """{"result":{"device_id":1}}""",
            """{"result":{"app_brand":"woowtech","push_contract_version":2}}""",
            """{"result":{"app_brand":"apporo","push_contract_version":1}}""",
            """{"result":{"app_brand":"apporo","push_contract_version":"2"}}""",
            """{"result":{"app_brand":"apporo","push_contract_version":2,"error":"secret fixture"}}""",
        ).forEach { body -> assertThrows(PushContractException::class.java) { ApporoPushContract.requireRegistration(body) } }
    }

    @Test
    fun `Given valid echo when parsed then tenant remains unchanged and legacy parser ignores new fields`() {
        val body = """{"result":{"device_id":1,"odoo_tenant_id":"fixture-tenant","app_brand":"apporo","push_contract_version":2}}"""
        ApporoPushContract.requireRegistration(body)
        assertEquals("fixture-tenant", FcmRegistrationResponse.parseTenantId(body))
        assertEquals("fixture-tenant", FcmRegistrationResponse.parseTenantId(body.replace("apporo", "woowtech")))
    }

    @Test
    fun `Given unregister response when parsed then only boolean success shape is accepted`() {
        ApporoPushContract.requireUnregistration("""{"result":{"success":true}}""")
        ApporoPushContract.requireUnregistration("""{"result":{"success":false}}""")
        listOf("{}", """{"result":{"success":"true"}}""", """{"result":{"error":"fixture"}}""").forEach {
            assertThrows(PushContractException::class.java) { ApporoPushContract.requireUnregistration(it) }
        }
    }
}
