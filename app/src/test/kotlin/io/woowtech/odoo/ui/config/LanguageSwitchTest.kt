package io.woowtech.odoo.ui.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for language switching (H2 fix).
 *
 * Verifies:
 * 1. updateLanguage persists the selected [AppLanguage] to EncryptedPrefs
 * 2. The settings StateFlow reflects the new language immediately
 * 3. Switching back to SYSTEM code maps correctly
 *
 * The live locale apply (LocaleManager.setApplicationLocales / Activity.recreate) is
 * triggered from SettingsScreen UI and cannot be tested on the JVM runtime because it
 * requires an Android Activity context. These tests verify the data layer contract only.
 * On-device verification is required to confirm the locale actually changes immediately.
 */
class LanguageSwitchTest {

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var repo: SettingsRepository

    @BeforeEach
    fun setup() {
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.getAppSettings() } returns AppSettings(language = AppLanguage.SYSTEM)
        repo = SettingsRepository(encryptedPrefs)
    }

    @Test
    fun `Given SYSTEM language by default when updateLanguage ENGLISH then settings reflect ENGLISH`() {
        repo.updateLanguage(AppLanguage.ENGLISH)

        assertEquals(AppLanguage.ENGLISH, repo.settings.value.language)
    }

    @Test
    fun `Given ENGLISH language when updateLanguage CHINESE_TW then settings reflect CHINESE_TW`() {
        repo.updateLanguage(AppLanguage.ENGLISH)
        repo.updateLanguage(AppLanguage.CHINESE_TW)

        assertEquals(AppLanguage.CHINESE_TW, repo.settings.value.language)
    }

    @Test
    fun `Given CHINESE_TW language when updateLanguage SYSTEM then settings reflect SYSTEM`() {
        repo.updateLanguage(AppLanguage.CHINESE_TW)
        repo.updateLanguage(AppLanguage.SYSTEM)

        assertEquals(AppLanguage.SYSTEM, repo.settings.value.language)
    }

    @Test
    fun `Given updateLanguage called when persists to EncryptedPrefs`() {
        repo.updateLanguage(AppLanguage.CHINESE_CN)

        verify { encryptedPrefs.updateLanguage(AppLanguage.CHINESE_CN) }
    }

    @Test
    fun `Given AppLanguage SYSTEM when code is system then maps to empty locale list sentinel`() {
        // The applyLocaleChange function in SettingsScreen checks for "system" code
        // to decide to pass LocaleList.getEmptyLocaleList() to LocaleManager.
        // Verify the code constant is correct so the locale manager gets the right value.
        assertEquals("system", AppLanguage.SYSTEM.code)
    }

    @Test
    fun `Given AppLanguage CHINESE_TW when code then is valid BCP47 tag`() {
        // Verify the language tag is a valid BCP47 tag passed to LocaleList.forLanguageTags.
        assertEquals("zh-TW", AppLanguage.CHINESE_TW.code)
    }

    @Test
    fun `Given AppLanguage CHINESE_CN when code then is valid BCP47 tag`() {
        assertEquals("zh-CN", AppLanguage.CHINESE_CN.code)
    }
}
