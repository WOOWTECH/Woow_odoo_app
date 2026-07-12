package io.woowtech.odoo.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
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
}
