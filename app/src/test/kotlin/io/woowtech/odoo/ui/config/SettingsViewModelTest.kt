package io.woowtech.odoo.ui.config

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.repository.CacheRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var cacheRepository: CacheRepository
    private lateinit var settingsFlow: MutableStateFlow<AppSettings>
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk(relaxed = true)
        cacheRepository = mockk(relaxed = true)
        settingsFlow = MutableStateFlow(AppSettings())

        every { settingsRepository.settings } returns settingsFlow
        coEvery { cacheRepository.calculateCacheSize() } returns 0L
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(settingsRepository, cacheRepository)
    }

    // ──────────────────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given cache repository when ViewModel created then calculates cache size on init`() = runTest {
        coEvery { cacheRepository.calculateCacheSize() } returns 2048L

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.cacheSizeText.test {
            assertEquals("2 KB", awaitItem())
        }
    }

    @Test
    fun `Given zero cache size when ViewModel created then shows 0 B`() = runTest {
        coEvery { cacheRepository.calculateCacheSize() } returns 0L

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.cacheSizeText.test {
            assertEquals("0 B", awaitItem())
        }
    }

    @Test
    fun `Given settings repository when ViewModel created then exposes settings flow`() = runTest {
        val customSettings = AppSettings(themeColor = "#FF0000", appLockEnabled = true)
        settingsFlow.value = customSettings

        viewModel = createViewModel()

        viewModel.settings.test {
            val state = awaitItem()
            assertEquals("#FF0000", state.themeColor)
            assertTrue(state.appLockEnabled)
        }
    }

    // ──────────────────────────────────────────────────────────
    // Theme Color
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class ThemeColor {

        @Test
        fun `Given new color when updateThemeColor then delegates to settingsRepository`() {
            viewModel = createViewModel()

            viewModel.updateThemeColor("#FF5722")

            verify { settingsRepository.updateThemeColor("#FF5722") }
        }

        @Test
        fun `Given hex color string when updateThemeColor then passes exact string`() {
            viewModel = createViewModel()

            viewModel.updateThemeColor("#00BCD4")

            verify { settingsRepository.updateThemeColor("#00BCD4") }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Reduce Motion
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given reduce motion toggled when updateReduceMotion true then delegates to repository`() {
        viewModel = createViewModel()

        viewModel.updateReduceMotion(true)

        verify { settingsRepository.updateReduceMotion(true) }
    }

    @Test
    fun `Given reduce motion toggled when updateReduceMotion false then delegates to repository`() {
        viewModel = createViewModel()

        viewModel.updateReduceMotion(false)

        verify { settingsRepository.updateReduceMotion(false) }
    }

    // ──────────────────────────────────────────────────────────
    // App Lock
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class AppLock {

        @Test
        fun `Given app lock toggled when updateAppLock enabled then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateAppLock(true)

            verify { settingsRepository.updateAppLock(true) }
        }

        @Test
        fun `Given app lock toggled when updateAppLock disabled then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateAppLock(false)

            verify { settingsRepository.updateAppLock(false) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Biometric
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class Biometric {

        @Test
        fun `Given biometric toggled when updateBiometric enabled then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateBiometric(true)

            verify { settingsRepository.updateBiometric(true) }
        }

        @Test
        fun `Given biometric toggled when updateBiometric disabled then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateBiometric(false)

            verify { settingsRepository.updateBiometric(false) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // PIN Management
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class PinManagement {

        @Test
        fun `Given valid PIN when setPin then delegates to repository`() = runTest {
            coEvery { settingsRepository.setPin("1234") } returns true
            viewModel = createViewModel()

            viewModel.setPin("1234")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { settingsRepository.setPin("1234") }
        }

        @Test
        fun `Given invalid PIN when setPin then repository is still called`() = runTest {
            coEvery { settingsRepository.setPin("12") } returns false
            viewModel = createViewModel()

            viewModel.setPin("12")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { settingsRepository.setPin("12") }
        }

        @Test
        fun `Given PIN exists when removePin then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.removePin()

            verify { settingsRepository.removePin() }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Language
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class Language {

        @Test
        fun `Given language selected when updateLanguage English then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateLanguage(AppLanguage.ENGLISH)

            verify { settingsRepository.updateLanguage(AppLanguage.ENGLISH) }
        }

        @Test
        fun `Given language selected when updateLanguage Chinese TW then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateLanguage(AppLanguage.CHINESE_TW)

            verify { settingsRepository.updateLanguage(AppLanguage.CHINESE_TW) }
        }

        @Test
        fun `Given language selected when updateLanguage Chinese CN then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateLanguage(AppLanguage.CHINESE_CN)

            verify { settingsRepository.updateLanguage(AppLanguage.CHINESE_CN) }
        }

        @Test
        fun `Given language selected when updateLanguage System then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateLanguage(AppLanguage.SYSTEM)

            verify { settingsRepository.updateLanguage(AppLanguage.SYSTEM) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Theme Mode
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class ThemeModeTests {

        @Test
        fun `Given theme mode selected when updateThemeMode DARK then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateThemeMode(ThemeMode.DARK)

            verify { settingsRepository.updateThemeMode(ThemeMode.DARK) }
        }

        @Test
        fun `Given theme mode selected when updateThemeMode LIGHT then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateThemeMode(ThemeMode.LIGHT)

            verify { settingsRepository.updateThemeMode(ThemeMode.LIGHT) }
        }

        @Test
        fun `Given theme mode selected when updateThemeMode SYSTEM then delegates to repository`() {
            viewModel = createViewModel()

            viewModel.updateThemeMode(ThemeMode.SYSTEM)

            verify { settingsRepository.updateThemeMode(ThemeMode.SYSTEM) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Cache Clearing
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class CacheClearing {

        @Test
        fun `Given cache exists when clearCache then clears both app and WebView cache`() = runTest {
            coEvery { cacheRepository.calculateCacheSize() } returns 1024L * 1024L * 5L // 5 MB initially
            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            // After clear, cache returns 0
            coEvery { cacheRepository.clearAppCache() } returns 0L
            coEvery { cacheRepository.clearWebViewCache() } returns Unit
            coEvery { cacheRepository.calculateCacheSize() } returns 0L

            viewModel.clearCache()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { cacheRepository.clearAppCache() }
            coVerify { cacheRepository.clearWebViewCache() }
        }

        @Test
        fun `Given cache cleared when clearCache completes then cacheSizeText is updated`() = runTest {
            coEvery { cacheRepository.calculateCacheSize() } returns 1024L * 512L // 512 KB initially
            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("512 KB", viewModel.cacheSizeText.value)

            // After clear, cache returns 100 bytes
            coEvery { cacheRepository.clearAppCache() } returns 100L
            coEvery { cacheRepository.clearWebViewCache() } returns Unit
            coEvery { cacheRepository.calculateCacheSize() } returns 100L

            viewModel.clearCache()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("100 B", viewModel.cacheSizeText.value)
        }
    }

    // ──────────────────────────────────────────────────────────
    // Cache Size Formatting
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class CacheSizeFormatting {

        @Test
        fun `Given cache size in bytes when formatted then shows B suffix`() = runTest {
            coEvery { cacheRepository.calculateCacheSize() } returns 512L

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("512 B", viewModel.cacheSizeText.value)
        }

        @Test
        fun `Given cache size in kilobytes when formatted then shows KB suffix`() = runTest {
            coEvery { cacheRepository.calculateCacheSize() } returns 2048L

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("2 KB", viewModel.cacheSizeText.value)
        }

        @Test
        fun `Given cache size in megabytes when formatted then shows MB suffix`() = runTest {
            coEvery { cacheRepository.calculateCacheSize() } returns 1024L * 1024L * 3L

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("3 MB", viewModel.cacheSizeText.value)
        }

        @Test
        fun `Given cache size at 1023 bytes when formatted then shows bytes not KB`() = runTest {
            coEvery { cacheRepository.calculateCacheSize() } returns 1023L

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("1023 B", viewModel.cacheSizeText.value)
        }

        @Test
        fun `Given cache size exactly 1024 when formatted then shows 1 KB`() = runTest {
            coEvery { cacheRepository.calculateCacheSize() } returns 1024L

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("1 KB", viewModel.cacheSizeText.value)
        }

        @Test
        fun `Given cache size at KB-MB boundary when formatted then shows KB not MB`() = runTest {
            // 1024 * 1024 - 1 = 1048575 bytes => should be KB
            coEvery { cacheRepository.calculateCacheSize() } returns (1024L * 1024L - 1L)

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("1023 KB", viewModel.cacheSizeText.value)
        }
    }
}
