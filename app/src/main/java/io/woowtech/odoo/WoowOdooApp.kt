package io.woowtech.odoo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class WoowOdooApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        createNotificationChannels()
        logFcmToken()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_messages_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Timber.d("Notification channel created: %s", CHANNEL_ID_MESSAGES)
        }
    }

    /**
     * Retrieves and logs the FCM device token for debugging.
     * In production, the token is sent to Odoo via FcmTokenRepository.
     */
    private fun logFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Timber.d("FCM_TOKEN: %s", task.result)
            } else {
                Timber.e(task.exception, "Failed to get FCM token")
            }
        }
    }

    companion object {
        const val CHANNEL_ID_MESSAGES = "woow_odoo_messages"
    }
}
