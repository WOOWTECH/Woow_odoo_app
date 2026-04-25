package io.woowtech.odoo.data.location

import android.Manifest
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LocationPermissionGate.resolve].
 *
 * All Android platform dependencies (Context, ContextCompat) are replaced by the
 * [PermissionChecker] interface fake, so tests run on the JVM without Robolectric.
 * [SettingsRepository] is mocked with MockK relaxed = true; settings are overridden
 * per test using [every].
 *
 * The [activeAccountHost] parameter is pre-computed by the caller (composable) and
 * passed in directly, so these tests do not need to mock [AccountRepository].
 */
class LocationPermissionGateTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var permissionChecker: PermissionChecker

    // Convenience — the host our "active account" uses.
    private val activeHost = "odoo.example.com"
    private val httpsOrigin = "https://odoo.example.com"

    @BeforeEach
    fun setUp() {
        // RelaxED mock — returns defaults (false for booleans) unless overridden.
        settingsRepository = mockk(relaxed = true)
        // Default: settings.locationEnabled = true.
        every { settingsRepository.settings.value } returns AppSettings(locationEnabled = true)

        // Default: no permissions granted.
        permissionChecker = PermissionChecker { false }
    }

    private fun gate() = LocationPermissionGate(
        settingsRepository = settingsRepository,
        permissionChecker = permissionChecker,
    )

    // ─── Test 1 ───────────────────────────────────────────────────────────────

    @Test
    fun `Given origin not HTTPS then Reject origin-not-https`() {
        val decision = gate().resolve(
            origin = "http://odoo.example.com",
            activeAccountHost = activeHost,
        )
        assertInstanceOf(LocationPermissionGate.Decision.Reject::class.java, decision)
        val reason = (decision as LocationPermissionGate.Decision.Reject).reason
        assertEquals("origin-not-https", reason)
    }

    // ─── Test 2 ───────────────────────────────────────────────────────────────

    @Test
    fun `Given origin null then Reject`() {
        val decision = gate().resolve(
            origin = null,
            activeAccountHost = activeHost,
        )
        assertInstanceOf(LocationPermissionGate.Decision.Reject::class.java, decision)
    }

    // ─── Test 3 ───────────────────────────────────────────────────────────────

    @Test
    fun `Given origin host different from active account then Reject origin-host-mismatch`() {
        val decision = gate().resolve(
            origin = "https://evil.attacker.com",
            activeAccountHost = activeHost,
        )
        assertInstanceOf(LocationPermissionGate.Decision.Reject::class.java, decision)
        val reason = (decision as LocationPermissionGate.Decision.Reject).reason
        assert(reason.startsWith("origin-host-mismatch")) {
            "Expected reason starting with origin-host-mismatch, got: $reason"
        }
    }

    // ─── Test 4 ───────────────────────────────────────────────────────────────

    @Test
    fun `Given no active account then Reject no-active-account`() {
        val decision = gate().resolve(
            origin = httpsOrigin,
            activeAccountHost = null,
        )
        assertInstanceOf(LocationPermissionGate.Decision.Reject::class.java, decision)
        val reason = (decision as LocationPermissionGate.Decision.Reject).reason
        assertEquals("no-active-account", reason)
    }

    // ─── Test 5 ───────────────────────────────────────────────────────────────

    @Test
    fun `Given locationEnabled=false then Reject user-opted-out`() {
        every { settingsRepository.settings.value } returns AppSettings(locationEnabled = false)

        val decision = gate().resolve(
            origin = httpsOrigin,
            activeAccountHost = activeHost,
        )
        assertInstanceOf(LocationPermissionGate.Decision.Reject::class.java, decision)
        val reason = (decision as LocationPermissionGate.Decision.Reject).reason
        assertEquals("user-opted-out", reason)
    }

    // ─── Test 6 ───────────────────────────────────────────────────────────────

    @Test
    fun `Given FINE granted and origin matches and toggle on then Grant`() {
        permissionChecker = PermissionChecker { permission ->
            permission == Manifest.permission.ACCESS_FINE_LOCATION
        }

        val decision = gate().resolve(
            origin = httpsOrigin,
            activeAccountHost = activeHost,
        )
        assertInstanceOf(LocationPermissionGate.Decision.Grant::class.java, decision)
    }

    // ─── Test 7 ───────────────────────────────────────────────────────────────

    @Test
    fun `Given only COARSE granted then Grant`() {
        permissionChecker = PermissionChecker { permission ->
            permission == Manifest.permission.ACCESS_COARSE_LOCATION
        }

        val decision = gate().resolve(
            origin = httpsOrigin,
            activeAccountHost = activeHost,
        )
        assertInstanceOf(LocationPermissionGate.Decision.Grant::class.java, decision)
    }

    // ─── Test 8 ───────────────────────────────────────────────────────────────

    @Test
    fun `Given no permission then NeedsRuntimePrompt`() {
        // permissionChecker returns false for everything (default setUp).
        val decision = gate().resolve(
            origin = httpsOrigin,
            activeAccountHost = activeHost,
        )
        assertInstanceOf(LocationPermissionGate.Decision.NeedsRuntimePrompt::class.java, decision)
    }
}
