package io.woowtech.odoo.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.net.URI
import java.util.UUID

@Entity(tableName = "accounts")
data class OdooAccount(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val serverUrl: String,
    val database: String,
    val username: String,
    val displayName: String,
    val encryptedPassword: String? = null,
    val avatarBase64: String? = null,
    val userId: Int? = null,
    val lastLogin: Long = System.currentTimeMillis(),
    val isActive: Boolean = false,
    /**
     * Opaque tenant identifier for this account, obtained from the Odoo server at FCM
     * device-registration time. Used to route multi-account push-notification deep links:
     * the FCM payload carries the originating tenant id and the app matches it against this
     * field to resolve which local account a notification belongs to.
     *
     * Null when the server has not yet returned a tenant id (older plugin, or the account
     * has never completed FCM registration). A null value is never used for routing — an
     * unresolved tenant id causes the deep link to be dropped rather than mis-routed.
     */
    val tenantId: String? = null,
) {
    val fullServerUrl: String
        get() = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"

    /**
     * Bare hostname of this account's server — no scheme, port or path (iOS `OdooAccount.serverHost`
     * = `URL(fullServerUrl).host`). This is what [io.woowtech.odoo.data.push.DeepLinkValidator]
     * compares a link's `URI.host` against, so a server on a non-default port (`example.com:8443`)
     * still matches its own links. Empty when unparsable, which makes every absolute link rejected.
     */
    val serverHost: String
        get() {
            val withScheme = if (serverUrl.contains("://")) serverUrl else "https://$serverUrl"
            return runCatching { URI(withScheme).host }.getOrNull().orEmpty()
        }
}
