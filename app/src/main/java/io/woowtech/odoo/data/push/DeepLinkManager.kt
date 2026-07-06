package io.woowtech.odoo.data.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists a pending deep link URL across the authentication flow.
 * When a notification is tapped while the app is locked, the URL is stored
 * here and consumed after successful biometric/PIN authentication.
 */
@Singleton
class DeepLinkManager @Inject constructor() {

    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()

    /**
     * Stores a URL to be navigated to after authentication completes.
     */
    fun setPending(url: String?) {
        _pendingUrl.value = url
    }

    /**
     * Returns and clears the pending URL. Returns null if no URL is pending.
     */
    fun consume(): String? {
        val current = _pendingUrl.value
        _pendingUrl.value = null
        return current
    }
}
