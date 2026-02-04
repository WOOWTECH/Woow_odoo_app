package io.woowtech.odoo.domain.model

data class AppSettings(
    val themeColor: String = "#6183FC",
    val reduceMotion: Boolean = false,
    val appLockEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val pinEnabled: Boolean = false,
    val pinHash: String? = null,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val failedPinAttempts: Int = 0,
    val pinLockoutUntil: Long? = null
)

enum class AppLanguage(val code: String, val displayName: String) {
    SYSTEM("system", "System Default"),
    ENGLISH("en", "English"),
    CHINESE_TW("zh-TW", "繁體中文")
}
