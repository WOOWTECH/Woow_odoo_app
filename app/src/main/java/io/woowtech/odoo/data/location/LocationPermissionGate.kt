package io.woowtech.odoo.data.location

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.woowtech.odoo.data.repository.SettingsRepository
import timber.log.Timber
import java.net.URI
import javax.inject.Inject

/**
 * Abstraction over [ContextCompat.checkSelfPermission] that allows the gate to be
 * fully unit-tested on the JVM without Robolectric or real Android runtime.
 */
fun interface PermissionChecker {
    /**
     * Returns true if the given Android [permission] string has been granted to the app
     * by the OS, false otherwise.
     */
    fun isGranted(permission: String): Boolean
}

/**
 * Resolves whether the WebView geolocation permission callback should be granted,
 * rejected, or deferred to a runtime OS dialog, for a given [origin] string.
 *
 * Resolution order follows the architect's security-first recommendation:
 * 1. Origin validation (HTTPS + host match against the active account) — checked first
 *    because it does not leak information about user state.
 * 2. App-level preference (user opted out).
 * 3. OS runtime permission (live check, never cached).
 *
 * This class is stateless: every call to [resolve] reads fresh values from the
 * [SettingsRepository] and receives the active-account host as a parameter.
 * The caller (composable) is responsible for caching the host via [LaunchedEffect]
 * to avoid suspending on the WebChromeClient callback thread.
 */
class LocationPermissionGate @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val permissionChecker: PermissionChecker,
) {

    /**
     * The possible outcomes of a geolocation permission resolution.
     */
    sealed class Decision {
        /** Grant the permission to the WebView immediately. */
        data object Grant : Decision()

        /**
         * Deny the permission. [reason] is a short, dash-separated identifier logged
         * at debug level and never shown to the user.
         */
        data class Reject(val reason: String) : Decision()

        /** The OS permission has not been granted yet; the caller should launch the runtime prompt. */
        data object NeedsRuntimePrompt : Decision()
    }

    /**
     * Determines whether geolocation should be granted, rejected, or escalated to the OS.
     *
     * @param origin The origin string supplied by [android.webkit.WebChromeClient.onGeolocationPermissionsShowPrompt].
     *   May be null if the WebView supplies a blank or missing origin.
     * @param activeAccountHost The host component of the currently active Odoo account URL,
     *   pre-computed by the caller to avoid suspending on the WebChromeClient callback.
     *   Pass null if no account is active.
     */
    fun resolve(origin: String?, activeAccountHost: String?): Decision {
        // Step 1 — Origin must be HTTPS with a parseable host.
        // Uses java.net.URI (JVM-compatible) instead of android.net.Uri so the gate
        // can be unit-tested on the JVM without Robolectric.
        val originUri = origin?.let { runCatching { URI(it) }.getOrNull() }
        if (originUri == null || originUri.scheme?.lowercase() != "https") {
            Timber.d("Geolocation gate: reject — origin-not-https (origin=%s)", origin)
            return Decision.Reject("origin-not-https")
        }
        val originHost = originUri.host?.lowercase()
            ?: return Decision.Reject("origin-no-host").also {
                Timber.d("Geolocation gate: reject — origin-no-host")
            }

        // Step 2 — Origin host must match the active account host.
        if (activeAccountHost == null) {
            Timber.d("Geolocation gate: reject — no-active-account")
            return Decision.Reject("no-active-account")
        }
        if (originHost != activeAccountHost.lowercase()) {
            Timber.d(
                "Geolocation gate: reject — origin-host-mismatch (%s vs %s)",
                originHost,
                activeAccountHost,
            )
            return Decision.Reject("origin-host-mismatch:$originHost vs $activeAccountHost")
        }

        // Step 3 — User preference (app-level opt-out).
        if (!settingsRepository.settings.value.locationEnabled) {
            Timber.d("Geolocation gate: reject — user-opted-out")
            return Decision.Reject("user-opted-out")
        }

        // Step 4 — Live OS permission check (never cached).
        val hasFine = permissionChecker.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = permissionChecker.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return if (hasFine || hasCoarse) {
            Decision.Grant
        } else {
            Decision.NeedsRuntimePrompt
        }
    }
}

/**
 * Production [PermissionChecker] implementation backed by [ContextCompat].
 * Injected via Hilt in the real app; replaced by a fake in unit tests.
 */
internal class ContextPermissionChecker @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : PermissionChecker {
    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
