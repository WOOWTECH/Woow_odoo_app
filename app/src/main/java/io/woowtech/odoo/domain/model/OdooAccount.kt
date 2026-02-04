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
    val avatarBase64: String? = null,
    val userId: Int? = null,
    val lastLogin: Long = System.currentTimeMillis(),
    val isActive: Boolean = false
) {
    val fullServerUrl: String
        get() = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"
}
