package io.woowtech.odoo.data.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.woowtech.odoo.R
import io.woowtech.odoo.WoowOdooApp
import io.woowtech.odoo.ui.MainActivity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and displays push notifications from FCM messages.
 * All notifications use VISIBILITY_PRIVATE to hide content on lock screen
 * and FLAG_IMMUTABLE for PendingIntent security (required API 31+).
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deepLinkManager: DeepLinkManager
) {

    /**
     * Shows a notification for an Odoo event (chatter, discuss, mention, activity).
     *
     * @param title sender name or event title
     * @param body message preview text
     * @param actionUrl relative Odoo URL (e.g. "/web#id=42&model=sale.order&view_type=form")
     * @param eventType category for notification grouping
     * @param tenantId opaque tenant id of the account that sent this notification, used on tap to
     *   route the deep link to the correct account. Null for old-plugin payloads, in which case the
     *   tap falls back to current (active-account) behaviour.
     */
    fun showNotification(
        title: String,
        body: String,
        actionUrl: String? = null,
        eventType: String? = null,
        tenantId: String? = null,
    ) {
        val notificationId = System.currentTimeMillis().toInt()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            actionUrl?.let { putExtra(EXTRA_ACTION_URL, it) }
            tenantId?.let { putExtra(EXTRA_TENANT_ID, it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, WoowOdooApp.CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(eventType ?: GROUP_DEFAULT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            Timber.d("Notification shown: %s", title)
        } catch (e: SecurityException) {
            Timber.e(e, "POST_NOTIFICATIONS permission not granted")
        }
    }

    companion object {
        const val EXTRA_ACTION_URL = "odoo_action_url"
        const val EXTRA_TENANT_ID = "odoo_tenant_id"
        private const val GROUP_DEFAULT = "odoo_messages"
    }
}
