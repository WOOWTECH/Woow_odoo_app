package io.woowtech.odoo.data.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import io.woowtech.odoo.data.repository.FcmTokenRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives FCM push notifications from Odoo server.
 * Handles token refresh (registers with all active accounts)
 * and incoming message display via NotificationHelper.
 *
 * C1 fix: [onNewToken] is now wired to [FcmTokenRepository.registerTokenForAllAccounts]
 * so the refreshed token is actually sent to the Odoo server for each active account.
 * Previously the method only logged a TODO and never registered, meaning users would
 * stop receiving notifications after any FCM token rotation.
 */
@AndroidEntryPoint
class WoowFcmService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var fcmTokenRepository: FcmTokenRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        // Token is redacted in release builds to avoid leaking the device token to logs.
        Timber.d("FCM token refreshed — registering with all active accounts")
        scope.launch {
            fcmTokenRepository.registerTokenForAllAccounts(token)
                .onSuccess {
                    Timber.d("FCM token registered with all accounts")
                }
                .onFailure { error ->
                    // Failure is non-fatal — the token will be re-registered on the next
                    // successful login. Log a warning so the on-call engineer can correlate
                    // with missed notifications.
                    Timber.w(error, "FCM token registration partially failed — some accounts may not receive notifications")
                }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Timber.d("FCM message received from: %s", message.from)

        val data = message.data
        val title = data["title"] ?: return
        val body = data["body"] ?: return
        val actionUrl = data["odoo_action_url"]
        val eventType = data["event_type"]
        // Opaque originating tenant id (added by the version-gated plugin). Absent for old
        // senders — the tap then falls back to current active-account behaviour.
        val tenantId = data["odoo_tenant_id"]
        // ACCOUNT-scoped routing key (P2-9). Absent from older senders, in which case the
        // tap falls back to the tenant id — which cannot separate two users on one database.
        val deviceId = data["odoo_device_id"]

        notificationHelper.showNotification(
            title = title,
            body = body,
            actionUrl = actionUrl,
            eventType = eventType,
            tenantId = tenantId,
            deviceId = deviceId,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
