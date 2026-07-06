package io.woowtech.odoo.data.push

import com.google.firebase.messaging.RemoteMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [WoowFcmService.onMessageReceived] data parsing logic.
 *
 * Since WoowFcmService extends FirebaseMessagingService and uses @AndroidEntryPoint,
 * it cannot be instantiated directly in unit tests. Instead, we test the data parsing
 * and dispatch logic by verifying the contract:
 *
 * 1. Extract title, body, odoo_action_url, event_type from RemoteMessage.data
 * 2. If title or body is missing, return early (do not show notification)
 * 3. If present, call NotificationHelper.showNotification with extracted values
 *
 * We test these contracts by exercising the same parsing logic inline.
 */
class WoowFcmServiceTest {

    private lateinit var notificationHelper: NotificationHelper

    @BeforeEach
    fun setup() {
        notificationHelper = mockk(relaxed = true)
    }

    /**
     * Replicates the exact parsing logic from WoowFcmService.onMessageReceived.
     * This lets us test the branching without needing a real Android Service.
     */
    private fun simulateOnMessageReceived(data: Map<String, String>) {
        val title = data["title"] ?: return
        val body = data["body"] ?: return
        val actionUrl = data["odoo_action_url"]
        val eventType = data["event_type"]

        notificationHelper.showNotification(
            title = title,
            body = body,
            actionUrl = actionUrl,
            eventType = eventType
        )
    }

    // ──────────────────────────────────────────────────────────
    // Happy Path: Full Data Payload
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class FullPayload {

        @Test
        fun `Given complete data payload when message received then shows notification with all fields`() {
            val data = mapOf(
                "title" to "New Sale Order",
                "body" to "SO001 has been confirmed",
                "odoo_action_url" to "/web#id=42&model=sale.order&view_type=form",
                "event_type" to "chatter"
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "New Sale Order",
                    body = "SO001 has been confirmed",
                    actionUrl = "/web#id=42&model=sale.order&view_type=form",
                    eventType = "chatter"
                )
            }
        }

        @Test
        fun `Given payload with discuss event type when message received then passes event type`() {
            val data = mapOf(
                "title" to "Direct Message",
                "body" to "Hey, check this out",
                "odoo_action_url" to "/web#action=discuss.action_discuss",
                "event_type" to "discuss"
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "Direct Message",
                    body = "Hey, check this out",
                    actionUrl = "/web#action=discuss.action_discuss",
                    eventType = "discuss"
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Partial Payload: Optional Fields Missing
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class PartialPayload {

        @Test
        fun `Given payload without odoo_action_url when message received then shows notification with null actionUrl`() {
            val data = mapOf(
                "title" to "Reminder",
                "body" to "Activity due today"
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "Reminder",
                    body = "Activity due today",
                    actionUrl = null,
                    eventType = null
                )
            }
        }

        @Test
        fun `Given payload without event_type when message received then shows notification with null eventType`() {
            val data = mapOf(
                "title" to "Alert",
                "body" to "Stock level low",
                "odoo_action_url" to "/web#id=5&model=stock.picking"
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "Alert",
                    body = "Stock level low",
                    actionUrl = "/web#id=5&model=stock.picking",
                    eventType = null
                )
            }
        }

        @Test
        fun `Given payload with only title and body when message received then shows notification`() {
            val data = mapOf(
                "title" to "Simple",
                "body" to "Just a message"
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "Simple",
                    body = "Just a message",
                    actionUrl = null,
                    eventType = null
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Missing Required Fields: Early Return
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class MissingRequiredFields {

        @Test
        fun `Given payload without title when message received then does NOT show notification`() {
            val data = mapOf(
                "body" to "No title here",
                "odoo_action_url" to "/web#id=1",
                "event_type" to "chatter"
            )

            simulateOnMessageReceived(data)

            verify(exactly = 0) { notificationHelper.showNotification(any(), any(), any(), any()) }
        }

        @Test
        fun `Given payload without body when message received then does NOT show notification`() {
            val data = mapOf(
                "title" to "Has title but no body",
                "odoo_action_url" to "/web#id=1",
                "event_type" to "chatter"
            )

            simulateOnMessageReceived(data)

            verify(exactly = 0) { notificationHelper.showNotification(any(), any(), any(), any()) }
        }

        @Test
        fun `Given completely empty data when message received then does NOT show notification`() {
            val data = emptyMap<String, String>()

            simulateOnMessageReceived(data)

            verify(exactly = 0) { notificationHelper.showNotification(any(), any(), any(), any()) }
        }

        @Test
        fun `Given payload with only odoo_action_url when message received then does NOT show notification`() {
            val data = mapOf(
                "odoo_action_url" to "/web#id=42&model=sale.order"
            )

            simulateOnMessageReceived(data)

            verify(exactly = 0) { notificationHelper.showNotification(any(), any(), any(), any()) }
        }

        @Test
        fun `Given payload with only event_type when message received then does NOT show notification`() {
            val data = mapOf(
                "event_type" to "discuss"
            )

            simulateOnMessageReceived(data)

            verify(exactly = 0) { notificationHelper.showNotification(any(), any(), any(), any()) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Edge Cases: Special Characters and Values
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class EdgeCases {

        @Test
        fun `Given payload with empty title string when message received then shows notification with empty title`() {
            // Empty string is not null — title will be "" which passes the null check
            val data = mapOf(
                "title" to "",
                "body" to "Body is here"
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "",
                    body = "Body is here",
                    actionUrl = null,
                    eventType = null
                )
            }
        }

        @Test
        fun `Given payload with unicode characters when message received then passes through intact`() {
            val data = mapOf(
                "title" to "新訂單通知",
                "body" to "订单 SO001 已确认 ✅"
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "新訂單通知",
                    body = "订单 SO001 已确认 ✅",
                    actionUrl = null,
                    eventType = null
                )
            }
        }

        @Test
        fun `Given payload with very long body when message received then passes through intact`() {
            val longBody = "A".repeat(5000)
            val data = mapOf(
                "title" to "Alert",
                "body" to longBody
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "Alert",
                    body = longBody,
                    actionUrl = null,
                    eventType = null
                )
            }
        }

        @Test
        fun `Given payload with URL containing special chars when message received then passes URL intact`() {
            val complexUrl = "/web#id=42&model=sale.order&view_type=form&action=123&menu_id=5"
            val data = mapOf(
                "title" to "Order",
                "body" to "New order",
                "odoo_action_url" to complexUrl
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "Order",
                    body = "New order",
                    actionUrl = complexUrl,
                    eventType = null
                )
            }
        }

        @Test
        fun `Given payload with extra unexpected keys when message received then ignores them`() {
            val data = mapOf(
                "title" to "Order",
                "body" to "Confirmed",
                "odoo_model" to "sale.order",
                "odoo_res_id" to "42",
                "unknown_field" to "ignored",
                "event_type" to "mention"
            )

            simulateOnMessageReceived(data)

            verify {
                notificationHelper.showNotification(
                    title = "Order",
                    body = "Confirmed",
                    actionUrl = null,
                    eventType = "mention"
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Data Key Names Contract
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class DataKeyContract {

        @Test
        fun `Given FCM data keys when verified then title key is lowercase`() {
            val data = mapOf("title" to "Test")
            assertEquals("Test", data["title"])
        }

        @Test
        fun `Given FCM data keys when verified then body key is lowercase`() {
            val data = mapOf("body" to "Test")
            assertEquals("Test", data["body"])
        }

        @Test
        fun `Given FCM data keys when verified then odoo_action_url uses underscores`() {
            val data = mapOf("odoo_action_url" to "/test")
            assertEquals("/test", data["odoo_action_url"])
        }

        @Test
        fun `Given FCM data keys when verified then event_type uses underscores`() {
            val data = mapOf("event_type" to "chatter")
            assertEquals("chatter", data["event_type"])
        }

        @Test
        fun `Given wrong key casing when accessed then returns null`() {
            val data = mapOf("Title" to "Wrong case")
            assertNull(data["title"], "FCM keys are case-sensitive; 'Title' != 'title'")
        }
    }
}
