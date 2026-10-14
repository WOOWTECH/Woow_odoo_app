package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.local.PersistedPinLockout
import io.woowtech.odoo.domain.model.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsRepositoryLockoutPersistenceTest {

    @Test
    fun `same boot countdown uses monotonic time and ignores wall clock edits`() {
        val clock = FakeAppLockClock(elapsed = 100_000L, wall = 1_000_000L, boot = "boot-a")
        val fixture = fixture(
            lockout = persisted(
                boot = "boot-a",
                elapsedDeadline = 130_000L,
                wallDeadline = 1_030_000L,
                budget = 30_000L,
            )
        )
        val repository = SettingsRepository(fixture.prefs, clock)

        assertEquals(30_000L, repository.getLockoutRemainingMs())

        clock.elapsed = 112_000L
        clock.wall = 100L // manual rollback must not extend a same-boot lockout
        assertEquals(18_000L, repository.getLockoutRemainingMs())

        clock.wall = 9_000_000_000L // manual forward jump must not bypass it either
        assertEquals(18_000L, repository.getLockoutRemainingMs())
        assertTrue(repository.isLockedOut())
    }

    @Test
    fun `process restart on same boot preserves monotonic countdown`() {
        val clock = FakeAppLockClock(elapsed = 105_000L, wall = 1_005_000L, boot = "boot-a")
        val fixture = fixture(
            lockout = persisted(
                boot = "boot-a",
                elapsedDeadline = 130_000L,
                wallDeadline = 1_030_000L,
                budget = 30_000L,
            )
        )

        val firstProcess = SettingsRepository(fixture.prefs, clock)
        assertEquals(25_000L, firstProcess.getLockoutRemainingMs())

        clock.elapsed = 110_000L
        clock.wall = 5L // even a changed wall clock is ignored while boot identity matches
        val restartedProcess = SettingsRepository(fixture.prefs, clock)

        assertEquals(20_000L, restartedProcess.getLockoutRemainingMs())
        assertEquals("boot-a", fixture.lockout?.bootId)
        assertEquals(130_000L, fixture.lockout?.elapsedDeadlineMs)
        assertEquals(20_000L, fixture.lockout?.remainingBudgetMs)
    }

    @Test
    fun `reboot restores remaining wall duration then rebases to new monotonic epoch`() {
        val clock = FakeAppLockClock(elapsed = 2_000L, wall = 1_010_000L, boot = "boot-b")
        val fixture = fixture(
            lockout = persisted(
                boot = "boot-a",
                elapsedDeadline = 130_000L,
                wallDeadline = 1_030_000L,
                budget = 30_000L,
            )
        )

        val repository = SettingsRepository(fixture.prefs, clock)

        assertEquals(20_000L, repository.getLockoutRemainingMs())
        assertEquals("boot-b", fixture.lockout?.bootId)
        assertEquals(22_000L, fixture.lockout?.elapsedDeadlineMs)
        assertEquals(1_030_000L, fixture.lockout?.wallDeadlineMs)
        assertEquals(20_000L, fixture.lockout?.remainingBudgetMs)
    }

    @Test
    fun `wall clock rollback after reboot is capped by saved budget`() {
        val clock = FakeAppLockClock(elapsed = 2_000L, wall = 900_000L, boot = "boot-b")
        val fixture = fixture(
            lockout = persisted(
                boot = "boot-a",
                elapsedDeadline = 130_000L,
                wallDeadline = 1_030_000L,
                budget = 30_000L,
            )
        )

        val repository = SettingsRepository(fixture.prefs, clock)

        assertEquals(30_000L, repository.getLockoutRemainingMs())
        assertEquals(30_000L, fixture.lockout?.remainingBudgetMs)
        assertEquals(32_000L, fixture.lockout?.elapsedDeadlineMs)
    }

    @Test
    fun `large wall clock forward jump after reboot uses bounded saved budget`() {
        val clock = FakeAppLockClock(elapsed = 2_000L, wall = 2_000_000L, boot = "boot-b")
        val fixture = fixture(
            lockout = persisted(
                boot = "boot-a",
                elapsedDeadline = 130_000L,
                wallDeadline = 1_030_000L,
                budget = 30_000L,
            )
        )

        val repository = SettingsRepository(fixture.prefs, clock)

        assertEquals(30_000L, repository.getLockoutRemainingMs())
        assertTrue(repository.isLockedOut())
        assertEquals("boot-b", fixture.lockout?.bootId)
        assertEquals(32_000L, fixture.lockout?.elapsedDeadlineMs)
        assertEquals(30_000L, fixture.lockout?.remainingBudgetMs)
    }

    @Test
    fun `ordinary reboot after lockout expiry clears within bounded wall tolerance`() {
        val clock = FakeAppLockClock(elapsed = 2_000L, wall = 1_040_000L, boot = "boot-b")
        val fixture = fixture(
            lockout = persisted(
                boot = "boot-a",
                elapsedDeadline = 130_000L,
                wallDeadline = 1_030_000L,
                budget = 30_000L,
            )
        )

        val repository = SettingsRepository(fixture.prefs, clock)

        assertEquals(0L, repository.getLockoutRemainingMs())
        assertFalse(repository.isLockedOut())
        assertNull(fixture.lockout)
        verify(atLeast = 1) { fixture.prefs.clearPinLockout() }
    }

    @Test
    fun `legacy elapsed deadline is migrated when plausible on current boot`() {
        val clock = FakeAppLockClock(elapsed = 110_000L, wall = 1_010_000L, boot = "boot-a")
        val fixture = fixture(legacyDeadline = 130_000L)

        val repository = SettingsRepository(fixture.prefs, clock)

        assertEquals(20_000L, repository.getLockoutRemainingMs())
        assertNull(fixture.settings.pinLockoutUntil)
        assertEquals("boot-a", fixture.lockout?.bootId)
        assertEquals(130_000L, fixture.lockout?.elapsedDeadlineMs)
        assertEquals(1_030_000L, fixture.lockout?.wallDeadlineMs)
        assertEquals(20_000L, fixture.lockout?.remainingBudgetMs)
    }

    @Test
    fun `stale legacy deadline larger than retry tier is cleared fail-safe`() {
        val clock = FakeAppLockClock(elapsed = 1_000L, wall = 1_000_000L, boot = "new-boot")
        val fixture = fixture(legacyDeadline = 900_000L)

        val repository = SettingsRepository(fixture.prefs, clock)

        assertEquals(0L, repository.getLockoutRemainingMs())
        assertNull(fixture.lockout)
        assertNull(fixture.settings.pinLockoutUntil)
        verify(atLeast = 1) { fixture.prefs.clearPinLockout() }
    }

    @Test
    fun `expiry clears persisted lockout but preserves cumulative failures`() {
        val clock = FakeAppLockClock(elapsed = 130_000L, wall = 1_030_000L, boot = "boot-a")
        val fixture = fixture(
            lockout = persisted(
                boot = "boot-a",
                elapsedDeadline = 130_000L,
                wallDeadline = 1_030_000L,
                budget = 30_000L,
            )
        )

        val repository = SettingsRepository(fixture.prefs, clock)

        assertEquals(0L, repository.getLockoutRemainingMs())
        assertNull(repository.settings.value.pinLockoutUntil)
        assertEquals(5, repository.settings.value.failedPinAttempts)
        assertNull(fixture.lockout)
        verify(atLeast = 1) { fixture.prefs.clearPinLockout() }
    }

    private fun fixture(
        legacyDeadline: Long? = null,
        lockout: PersistedPinLockout? = null,
    ): PrefsFixture {
        val prefs = mockk<EncryptedPrefs>(relaxed = true)
        val fixture = PrefsFixture(
            prefs = prefs,
            settings = AppSettings(
                failedPinAttempts = 5,
                pinLockoutUntil = legacyDeadline,
            ),
            lockout = lockout,
        )

        every { prefs.getAppSettings() } answers { fixture.settings }
        every { prefs.getPinLockout() } answers { fixture.lockout }
        every { prefs.setPinLockout(any()) } answers {
            fixture.lockout = firstArg()
            fixture.settings = fixture.settings.copy(pinLockoutUntil = null)
        }
        every { prefs.clearPinLockout() } answers {
            fixture.lockout = null
            fixture.settings = fixture.settings.copy(pinLockoutUntil = null)
        }
        return fixture
    }

    private fun persisted(
        boot: String,
        elapsedDeadline: Long,
        wallDeadline: Long,
        budget: Long,
    ) = PersistedPinLockout(
        bootId = boot,
        elapsedDeadlineMs = elapsedDeadline,
        wallDeadlineMs = wallDeadline,
        remainingBudgetMs = budget,
    )

    private data class PrefsFixture(
        val prefs: EncryptedPrefs,
        var settings: AppSettings,
        var lockout: PersistedPinLockout?,
    )

    private class FakeAppLockClock(
        var elapsed: Long,
        var wall: Long,
        var boot: String,
    ) : AppLockClock() {
        override fun now() = AppLockTime(
            elapsedRealtimeMs = elapsed,
            wallClockMs = wall,
            bootId = boot,
        )
    }
}
