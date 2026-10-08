package io.woowtech.odoo.ui.config

import android.content.Context
import androidx.annotation.StringRes
import io.woowtech.odoo.R
import io.woowtech.odoo.domain.model.AppLanguage

/**
 * Localized label of an in-app language choice (LIVE-0927 r2). "Follow system" comes from the
 * three-locale `language_system` string — `AppLanguage.SYSTEM.displayName` is a hard-coded English
 * "System Default" and must not be shown. Named languages keep their endonym (English, 繁體中文, 简体中文),
 * which is how a language picker should present them in every UI language.
 */
@StringRes
internal fun appLanguageLabelRes(language: AppLanguage): Int? = when (language) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.ENGLISH, AppLanguage.CHINESE_TW, AppLanguage.CHINESE_CN -> null
}

internal fun appLanguageLabel(context: Context, language: AppLanguage): String =
    appLanguageLabelRes(language)?.let(context::getString) ?: language.displayName

/**
 * The language choice that is actually in effect (W2-4, 2026-10-08). On Android 13+ the per-app locale
 * lives in the system (`LocaleManager.applicationLocales`) and can be changed outside the app — system
 * Settings › Apps › Language, or `cmd locale set-app-locales` — while the app's own stored choice stays
 * "System Default". So the system's per-app locale wins whenever it is known:
 * - [appLocaleTags] null (below Android 13, not known) → the stored [stored] choice;
 * - empty → [AppLanguage.SYSTEM];
 * - first tag English → [AppLanguage.ENGLISH]; Chinese with Hant / TW / HK / MO → [AppLanguage.CHINESE_TW];
 *   other Chinese → [AppLanguage.CHINESE_CN];
 * - any other language is none of the app's choices (its texts fall back like the system default)
 *   → [AppLanguage.SYSTEM].
 */
internal fun effectiveAppLanguage(appLocaleTags: String?, stored: AppLanguage): AppLanguage {
    if (appLocaleTags == null) return stored
    val first = appLocaleTags.split(',').firstOrNull()?.trim().orEmpty()
    if (first.isEmpty()) return AppLanguage.SYSTEM
    val locale = java.util.Locale.forLanguageTag(first)
    return when (locale.language) {
        "en" -> AppLanguage.ENGLISH
        "zh" -> if (locale.script.equals("Hant", ignoreCase = true) || locale.country in setOf("TW", "HK", "MO")) {
            AppLanguage.CHINESE_TW
        } else {
            AppLanguage.CHINESE_CN
        }
        else -> AppLanguage.SYSTEM
    }
}

/** The system's per-app locale tags on Android 13+ ("" = follow the system), null below. */
internal fun systemAppLocaleTags(context: Context): String? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.toLanguageTags()
    } else {
        null
    }
