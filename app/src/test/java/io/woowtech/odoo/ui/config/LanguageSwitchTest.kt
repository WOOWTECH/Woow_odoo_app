package io.woowtech.odoo.ui.config

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the H2 Language Switch fix.
 *
 * These tests verify the ViewModel→Repository→EncryptedPrefs pipeline. The platform API
 * call ([android.app.LocaleManager.setApplicationLocales]) in [SettingsScreen] is an
 * Android framework call that cannot be exercised in a JVM unit test — it is covered by
 * code review (the call site exists and is guarded by a VERSION_CODES.TIRAMISU check).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageSwitchTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.getAppSettings() } returns AppSettings(language = AppLanguage.SYSTEM)
        settingsRepository = SettingsRepository(encryptedPrefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Given language ENGLISH selected when updateLanguage called then settingsRepository persists ENGLISH`() {
        every { encryptedPrefs.updateLanguage(AppLanguage.ENGLISH) } just runs

        settingsRepository.updateLanguage(AppLanguage.ENGLISH)

        verify { encryptedPrefs.updateLanguage(AppLanguage.ENGLISH) }
    }

    @Test
    fun `Given language CHINESE_TW selected when updateLanguage called then settingsRepository persists CHINESE_TW`() {
        every { encryptedPrefs.updateLanguage(AppLanguage.CHINESE_TW) } just runs

        settingsRepository.updateLanguage(AppLanguage.CHINESE_TW)

        verify { encryptedPrefs.updateLanguage(AppLanguage.CHINESE_TW) }
    }

    @Test
    fun `Given language ENGLISH selected when updateLanguage called then settings flow emits ENGLISH`() =
        runTest(testDispatcher) {
            every { encryptedPrefs.updateLanguage(any()) } just runs

            settingsRepository.updateLanguage(AppLanguage.ENGLISH)

            assertEquals(
                "settings StateFlow must reflect ENGLISH after updateLanguage(ENGLISH)",
                AppLanguage.ENGLISH,
                settingsRepository.settings.first().language
            )
        }

    @Test
    fun `Given language SYSTEM selected when updateLanguage called then settings flow emits SYSTEM`() =
        runTest(testDispatcher) {
            // Start with ENGLISH, switch back to SYSTEM (device default).
            every { encryptedPrefs.getAppSettings() } returns AppSettings(language = AppLanguage.ENGLISH)
            val repo = SettingsRepository(encryptedPrefs)
            every { encryptedPrefs.updateLanguage(any()) } just runs

            repo.updateLanguage(AppLanguage.SYSTEM)

            assertEquals(
                "settings StateFlow must reflect SYSTEM after updateLanguage(SYSTEM)",
                AppLanguage.SYSTEM,
                repo.settings.first().language
            )
        }

    @Test
    fun `Given SettingsViewModel when updateLanguage ENGLISH called then repository called with ENGLISH`() {
        val repo = mockk<SettingsRepository>(relaxed = true)
        every { repo.settings } returns settingsRepository.settings
        val viewModel = SettingsViewModel(repo)

        viewModel.updateLanguage(AppLanguage.ENGLISH)

        verify { repo.updateLanguage(AppLanguage.ENGLISH) }
    }

    @Test
    fun `Given AppLanguage SYSTEM code is system string then locale tag maps to empty list sentinel`() {
        // Verifies the contract: "system" code must map to LocaleList.getEmptyLocaleList()
        // in applyLocaleChange(). The SYSTEM code is the sentinel used to restore device
        // default — if this ever changes, the locale-reset path breaks.
        assertEquals(
            "AppLanguage.SYSTEM code must be 'system' to correctly map to empty LocaleList",
            "system",
            AppLanguage.SYSTEM.code
        )
    }
}
