package io.woowtech.odoo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import io.woowtech.odoo.data.repository.FcmTokenRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
class WoowOdooApp : Application() {

    // Application-scoped background scope for the launch-time FCM token reconcile. Uses a
    // SupervisorJob so a failure in the reconcile never tears down other work, and Dispatchers.IO
    // because the reconcile performs storage + network I/O (consistent with WoowFcmService).
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        createNotificationChannels()
        reconcileFcmToken()
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
     * Reconciles the FCM device token with the Odoo server on every app start.
     *
     * Fetches the current token from [FirebaseMessaging] and, when at least one account is logged
     * in, registers it for all active accounts via [FcmTokenRepository.registerTokenForAllAccounts].
     * This self-heals a stale server-side token (token rotation after inactivity, a failed or
     * never-fired `onNewToken`, or Firebase-initiated rotation) without waiting for a re-login.
     *
     * The work runs on [Dispatchers.IO] so it never blocks app startup. A failed or empty token
     * fetch is caught and logged — registration is never attempted with a null/empty token and the
     * app never crashes; recovery happens on the next successful launch or `onNewToken`. Registration
     * itself is serialized by the repository's existing mutex, so this can safely race with
     * `onNewToken` and login-time registration.
     *
     * The token is only logged in debug builds to avoid leaking it in release.
     */
    private fun reconcileFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.e(task.exception, "Failed to get FCM token — skipping launch reconcile")
                return@addOnCompleteListener
            }

            val token = task.result
            if (token.isNullOrBlank()) {
                Timber.w("FCM token was null/empty — skipping launch reconcile")
                return@addOnCompleteListener
            }

            if (BuildConfig.DEBUG) {
                Timber.d("FCM_TOKEN: %s", token)
            }

            appScope.launch {
                val repository = EntryPointAccessors.fromApplication(
                    this@WoowOdooApp,
                    FcmTokenEntryPoint::class.java,
                ).fcmTokenRepository()

                // reconcileToken short-circuits internally when no account is logged in (AC2.4) and
                // otherwise registers the current token for all active accounts through the same
                // mutex-guarded path used by onNewToken and login.
                repository.reconcileToken(token)
                    .onSuccess {
                        Timber.d("FCM token reconciled on launch")
                    }
                    .onFailure { error ->
                        // Non-fatal — the token re-registers on the next successful launch or
                        // onNewToken. Logged so missed notifications can be correlated.
                        Timber.w(error, "FCM launch reconcile partially failed — some accounts may not receive notifications")
                    }
            }
        }
    }

    /**
     * Hilt entry point used to obtain the [FcmTokenRepository] from the [Application] class, which
     * cannot use field injection cleanly at [onCreate] time. Installed in [SingletonComponent] so it
     * resolves the same singleton repository the rest of the app uses.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FcmTokenEntryPoint {
        fun fcmTokenRepository(): FcmTokenRepository
    }

    companion object {
        const val CHANNEL_ID_MESSAGES = "woow_odoo_messages"
    }
}
