package io.woowtech.odoo.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val login: String,
    val email: String? = null,
    val phone: String? = null,
    val mobile: String? = null,
    val website: String? = null,
    val function: String? = null, // Job title
    val imageBase64: String? = null,
    // v1.0.16: Odoo preferences
    val lang: String? = null,           // e.g., "zh_TW"
    val tz: String? = null,             // e.g., "Asia/Taipei"
    val notificationType: String? = null, // "email" or "inbox"
    val signature: String? = null       // HTML content
)

/**
 * Represents an available language in Odoo
 */
data class OdooLanguage(
    val code: String,    // e.g., "zh_TW"
    val name: String     // e.g., "Chinese (TW) / 繁體中文"
)
