package io.woowtech.odoo.ui.config

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

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

    fun clearCache(context: Context) {
        context.cacheDir.deleteRecursively()
    }

    fun getCacheSize(context: Context): String {
        val size = getFolderSize(context.cacheDir)
        return formatSize(size)
    }

    private fun getFolderSize(folder: File): Long {
        var length: Long = 0
        folder.listFiles()?.forEach { file ->
            length += if (file.isFile) {
                file.length()
            } else {
                getFolderSize(file)
            }
        }
        return length
    }

    private fun formatSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
    }
}
