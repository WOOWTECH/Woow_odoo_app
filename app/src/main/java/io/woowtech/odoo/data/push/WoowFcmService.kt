package io.woowtech.odoo.data.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
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
 */
@AndroidEntryPoint
class WoowFcmService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        Timber.d("FCM token refreshed")
        scope.launch {
            // TODO: Register token with all active Odoo servers via FcmTokenRepository
            // fcmTokenRepository.registerTokenForAllAccounts(token)
            Timber.d("FCM token registration pending — FcmTokenRepository not yet wired")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Timber.d("FCM message received from: %s", message.from)

        val data = message.data
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
