package io.woowtech.odoo.ui

import android.content.Intent
import io.woowtech.odoo.BuildConfig
import io.woowtech.odoo.data.repository.SettingsRepository
import timber.log.Timber

/**
 * Debug-only entry point for E2E tests to seed app state without UI navigation.
 *
 * SECURITY:
 * - Every method body is gated on [BuildConfig.DEBUG]. In release variants R8
 *   strips the entire body (verified by checking apkanalyzer output of
 *   :app:bundleRelease).
 * - The release APK uses `applicationId = "io.woowtech.odoo"` while debug uses
 *   `io.woowtech.odoo.debug` — they cannot share data even if both installed.
 * - Invalid input is LOGGED and SKIPPED, never crashes the app. This matters
 *   because FLAG_SECURE redacts crash screens making them un-debuggable.
 *
 * Usage from adb (debug builds only):
 *   adb shell am start -n io.woowtech.odoo.debug/io.woowtech.odoo.ui.MainActivity \
 *     --es test-pin 1234 --ez app-lock-enabled true --ez biometric-enabled false
 *
 * Recognized extras:
 *   test-pin              (String,  4-6 digits)        — seeds PIN via SettingsRepository
 *   app-lock-enabled      (Boolean)                    — sets requiresAuth precondition
 *   biometric-enabled     (Boolean)                    — toggles biometric (capability-gated)
 *   reset-state           (Boolean)                    — clears auth, lockout counters
 */
internal object TestHooks {

    private const val TAG = "TestHooks"
    private const val EXTRA_TEST_PIN = "test-pin"
    private const val EXTRA_APP_LOCK = "app-lock-enabled"
    private const val EXTRA_BIOMETRIC = "biometric-enabled"
    private const val EXTRA_RESET = "reset-state"

    fun applyIfPresent(intent: Intent?, settings: SettingsRepository) {
        if (!BuildConfig.DEBUG) return
        if (intent == null || intent.extras == null) return

        try {
            val pin = intent.getStringExtra(EXTRA_TEST_PIN)
            if (pin != null) {
                if (pin.length in 4..6 && pin.all { it.isDigit() }) {
                    settings.setPin(pin)
                    Timber.tag(TAG).w("Seeded PIN via test hook (DEBUG only)")
                } else {
                    Timber.tag(TAG).w("Ignored invalid test-pin (must be 4-6 digits)")
                }
            }

            if (intent.hasExtra(EXTRA_APP_LOCK)) {
                settings.updateAppLock(intent.getBooleanExtra(EXTRA_APP_LOCK, false))
                Timber.tag(TAG).w("App Lock set via test hook (DEBUG only)")
            }

            if (intent.hasExtra(EXTRA_BIOMETRIC)) {
                settings.updateBiometric(
                    enabled = intent.getBooleanExtra(EXTRA_BIOMETRIC, false),
                    canEnable = true,
                )
                Timber.tag(TAG).w("Biometric set via test hook (DEBUG only)")
            }

            if (intent.getBooleanExtra(EXTRA_RESET, false)) {
                settings.resetFailedPinAttempts()
                Timber.tag(TAG).w("Auth state reset via test hook (DEBUG only)")
            }
        } catch (t: Throwable) {
            // Defense in depth: NEVER crash the app from a test hook. If
            // anything goes wrong, log and continue. Tests will fail their
            // own assertions if the precondition wasn't applied.
            Timber.tag(TAG).e(t, "Test hook threw — ignored to avoid Activity crash")
        }
    }
}
