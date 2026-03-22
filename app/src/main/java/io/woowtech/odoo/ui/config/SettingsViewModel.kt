package io.woowtech.odoo.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.CacheRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheRepository: CacheRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _cacheSizeText = MutableStateFlow("")
    val cacheSizeText: StateFlow<String> = _cacheSizeText.asStateFlow()

    init {
        viewModelScope.launch {
            _cacheSizeText.value = formatSize(cacheRepository.calculateCacheSize())
        }
    }

    fun updateThemeColor(color: String) {
        settingsRepository.updateThemeColor(color)
    }

    fun updateReduceMotion(enabled: Boolean) {
        settingsRepository.updateReduceMotion(enabled)
    }

    fun updateAppLock(enabled: Boolean) {
        settingsRepository.updateAppLock(enabled)
    }

    fun updateBiometric(enabled: Boolean) {
        settingsRepository.updateBiometric(enabled)
    }

    fun setPin(pin: String): Boolean {
        return settingsRepository.setPin(pin)
    }

    fun removePin() {
        settingsRepository.removePin()
    }

    fun updateLanguage(language: AppLanguage) {
        settingsRepository.updateLanguage(language)
    }

    fun updateThemeMode(mode: ThemeMode) {
        settingsRepository.updateThemeMode(mode)
    }

    /**
     * Clears app cache and WebView cache via CacheRepository.
     * Does not clear login session or user settings.
     */
    fun clearCache() {
        viewModelScope.launch {
            cacheRepository.clearAppCache()
            cacheRepository.clearWebViewCache()
            _cacheSizeText.value = formatSize(cacheRepository.calculateCacheSize())
        }
    }

    private fun formatSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
    }
}
