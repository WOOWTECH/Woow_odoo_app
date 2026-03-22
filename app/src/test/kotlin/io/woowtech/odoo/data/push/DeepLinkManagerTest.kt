package io.woowtech.odoo.data.push

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeepLinkManagerTest {

    private lateinit var manager: DeepLinkManager

    @BeforeEach
    fun setup() {
        manager = DeepLinkManager()
    }

    @Test
    fun `Given no pending URL when consume then returns null`() {
        assertNull(manager.consume())
    }

    @Test
    fun `Given URL set when consume then returns URL and clears`() {
        manager.setPending("/web#id=42&model=sale.order")

        assertEquals("/web#id=42&model=sale.order", manager.consume())
        assertNull(manager.consume())
    }

    @Test
    fun `Given URL set when setPending null then clears`() {
        manager.setPending("/web#action=contacts")
        manager.setPending(null)

        assertNull(manager.consume())
    }

    @Test
    fun `Given URL set when observed via flow then emits URL`() = runTest {
        manager.pendingUrl.test {
            assertEquals(null, awaitItem())

            manager.setPending("/web#id=1")
            assertEquals("/web#id=1", awaitItem())

            manager.setPending(null)
            assertEquals(null, awaitItem())
        }
    }

    @Test
    fun `Given URL overwritten when consume then returns latest`() {
        manager.setPending("/web#id=1")
        manager.setPending("/web#id=2")

        assertEquals("/web#id=2", manager.consume())
    }
}
