package io.woowtech.odoo.ui.config

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the C1 Reduce Motion fix.
 *
 * These tests verify that the persistence layer correctly stores the reduceMotion flag and
 * that the SettingsViewModel exposes it via its StateFlow. The animation spec selection
 * (snap() vs tween()) in BiometricScreen and PinScreen is a Compose concern that cannot
 * be verified in a JVM unit test — code-review is the verification path for that layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimationReduceMotionTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.getAppSettings() } returns AppSettings(reduceMotion = false)
        settingsRepository = SettingsRepository(encryptedPrefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------- SettingsRepository persistence ----------

    @Test
    fun `Given reduceMotion toggled on then SettingsRepository persists true to EncryptedPrefs`() {
        val settingsSlot = slot<AppSettings>()
        every { encryptedPrefs.saveAppSettings(capture(settingsSlot)) } just runs

        settingsRepository.updateReduceMotion(enabled = true)

        verify { encryptedPrefs.saveAppSettings(any()) }
        assertTrue(
            "saveAppSettings must be called with reduceMotion=true",
            settingsSlot.captured.reduceMotion
        )
    }

    @Test
    fun `Given reduceMotion toggled off then SettingsRepository persists false to EncryptedPrefs`() {
        // Start with true so toggling off is meaningful.
        every { encryptedPrefs.getAppSettings() } returns AppSettings(reduceMotion = true)
        val repo = SettingsRepository(encryptedPrefs)
        val settingsSlot = slot<AppSettings>()
        every { encryptedPrefs.saveAppSettings(capture(settingsSlot)) } just runs

        repo.updateReduceMotion(enabled = false)

        verify { encryptedPrefs.saveAppSettings(any()) }
        assertFalse(
            "saveAppSettings must be called with reduceMotion=false",
            settingsSlot.captured.reduceMotion
        )
    }

    // ---------- In-memory StateFlow update ----------

    @Test
    fun `Given reduceMotion=false when updateReduceMotion true called then settings flow emits true`() =
        runTest(testDispatcher) {
            every { encryptedPrefs.saveAppSettings(any()) } just runs

            settingsRepository.updateReduceMotion(enabled = true)

            assertTrue(
                "settings StateFlow must reflect reduceMotion=true after updateReduceMotion(true)",
                settingsRepository.settings.first().reduceMotion
            )
        }

    @Test
    fun `Given reduceMotion=true when updateReduceMotion false called then settings flow emits false`() =
        runTest(testDispatcher) {
            every { encryptedPrefs.getAppSettings() } returns AppSettings(reduceMotion = true)
            val repo = SettingsRepository(encryptedPrefs)
            every { encryptedPrefs.saveAppSettings(any()) } just runs

            repo.updateReduceMotion(enabled = false)

            assertFalse(
                "settings StateFlow must reflect reduceMotion=false after updateReduceMotion(false)",
                repo.settings.first().reduceMotion
            )
        }

    // ---------- SettingsViewModel delegation ----------

    @Test
    fun `Given SettingsViewModel when updateReduceMotion true called then repository called with true`() {
        val repo = mockk<SettingsRepository>(relaxed = true)
        every { repo.settings } returns settingsRepository.settings
        val viewModel = SettingsViewModel(repo)

        viewModel.updateReduceMotion(enabled = true)

        verify { repo.updateReduceMotion(enabled = true) }
    }

    @Test
    fun `Given SettingsViewModel when updateReduceMotion false called then repository called with false`() {
        val repo = mockk<SettingsRepository>(relaxed = true)
        every { repo.settings } returns settingsRepository.settings
        val viewModel = SettingsViewModel(repo)

        viewModel.updateReduceMotion(enabled = false)

        verify { repo.updateReduceMotion(enabled = false) }
    }
}
