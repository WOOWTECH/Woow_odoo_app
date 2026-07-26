package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WI-2 data-layer invariant tests for App Lock (story `story-android-applock-deadlock-fix`).
 *
 * Enforces `appLockEnabled ⇒ pinEnabled`: App Lock cannot be armed without a PIN, and removing
 * the PIN co-disables App Lock in a single atomic write — so the persisted state can never reach
 * the dead-end (`appLockEnabled=true && pinEnabled=false`) that bricked the lock screen.
 */
class SettingsRepositoryAppLockTest {

    private fun repoWith(initial: AppSettings): Pair<EncryptedPrefs, SettingsRepository> {
        val prefs = mockk<EncryptedPrefs>(relaxed = true)
        every { prefs.getAppSettings() } returns initial
        return prefs to SettingsRepository(prefs)
    }

    // --- AC2.3: refuse arming App Lock without a PIN ---

    @Test
    fun `Given no PIN when updateAppLock(true) then refused and state unchanged`() {
        val (prefs, repo) = repoWith(AppSettings(pinEnabled = false, appLockEnabled = false))

        val applied = repo.updateAppLock(true)

        assertFalse(applied, "enabling App Lock without a PIN must be refused")
        assertFalse(repo.settings.value.appLockEnabled, "state must stay disabled")
        verify(exactly = 0) { prefs.updateAppLock(true) } // nothing persisted
    }

    @Test
    fun `Given a PIN is set when updateAppLock(true) then enabled and persisted`() {
        val (prefs, repo) = repoWith(AppSettings(pinEnabled = true, pinHash = "salt:hash", appLockEnabled = false))

        val applied = repo.updateAppLock(true)

        assertTrue(applied)
        assertTrue(repo.settings.value.appLockEnabled)
        verify { prefs.updateAppLock(true) }
    }

    // --- AC2.5: disabling App Lock is always allowed, never blocked/auth'd ---

    @Test
    fun `Given App Lock on when updateAppLock(false) then always applied`() {
        val (prefs, repo) = repoWith(AppSettings(pinEnabled = true, pinHash = "salt:hash", appLockEnabled = true))

        val applied = repo.updateAppLock(false)

        assertTrue(applied, "disabling App Lock must never be refused")
        assertFalse(repo.settings.value.appLockEnabled)
        verify { prefs.updateAppLock(false) }
    }

    // --- AC2.4: removing the PIN co-disables App Lock atomically (single write) ---

    @Test
    fun `Given App Lock on with a PIN when removePin then App Lock co-disabled in one atomic write`() {
        val (prefs, repo) = repoWith(
            AppSettings(pinEnabled = true, pinHash = "salt:hash", appLockEnabled = true),
        )

        repo.removePin()

        // Single batched write of the full settings object → no half-written brick window.
        verify(exactly = 1) {
            prefs.saveAppSettings(
                match { !it.appLockEnabled && !it.pinEnabled && it.pinHash == null },
            )
        }
        // And it did NOT use the old separate-commit path that left a gap.
        verify(exactly = 0) { prefs.updatePinHash(any()) }

        val s = repo.settings.value
        assertFalse(s.appLockEnabled)
        assertFalse(s.pinEnabled)
    }

    @Test
    fun `The invariant holds after removePin — never App Lock on without a PIN`() {
        val (_, repo) = repoWith(
            AppSettings(pinEnabled = true, pinHash = "salt:hash", appLockEnabled = true),
        )

        repo.removePin()

        val s = repo.settings.value
        assertFalse(s.appLockEnabled && !s.pinEnabled, "must never be App Lock on without a PIN")
    }
}
