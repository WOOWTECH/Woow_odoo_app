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
