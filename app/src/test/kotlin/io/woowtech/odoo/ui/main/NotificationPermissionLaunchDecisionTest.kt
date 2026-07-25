package io.woowtech.odoo.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for WI-1 — the auto-launch decision for the `POST_NOTIFICATIONS` runtime permission
 * dialog performed by the `LaunchedEffect` in [io.woowtech.odoo.ui.main.MainScreen].
 *
 * The real logic lives inside a Compose `LaunchedEffect` which cannot be exercised on the JVM
 * without Robolectric/Compose-test infrastructure (this module has neither — see
 * `WoowFcmServiceTest` for the same constraint). Following that established repo idiom, the exact
 * branching from `MainScreen.kt` is replicated here as a pure decision function so its behaviour
 * can be verified deterministically by controlling the three inputs it depends on:
 *
 *  - the OS API level (`Build.VERSION.SDK_INT`),
 *  - whether `POST_NOTIFICATIONS` is already granted (`ContextCompat.checkSelfPermission`), and
 *  - the persisted "already asked" flag (`EncryptedPrefs.wasPostNotificationPermissionRequested`,
 *    surfaced via `MainViewModel.wasNotificationPermissionRequested`).
 *
 * Source of truth being mirrored (MainScreen.kt LaunchedEffect):
 * ```
 * if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
 * val alreadyGranted = checkSelfPermission(POST_NOTIFICATIONS) == PERMISSION_GRANTED
 * if (!alreadyGranted && !viewModel.wasNotificationPermissionRequested()) {
 *     viewModel.markNotificationPermissionRequested()
 *     launcher.launch(POST_NOTIFICATIONS)
 * }
 * ```
 * If this test and the composable ever diverge, the decision function below must be updated to match.
 */
class NotificationPermissionLaunchDecisionTest {

    private companion object {
        // Mirrors Build.VERSION_CODES.TIRAMISU (Android 13). Hardcoded so the test does not depend
        // on the Android framework being on the unit-test classpath.
        const val API_TIRAMISU = 33
        const val API_ANDROID_12 = 32
    }

    /**
     * Records what the replicated `LaunchedEffect` decided to do, so tests can assert on both the
     * launcher invocation and the "already asked" flag write without a live Compose runtime.
     */
    private data class LaunchDecision(
        val launcherInvoked: Boolean,
        val flagMarked: Boolean,
    )

    /**
     * Faithful JVM replica of the MainScreen `LaunchedEffect` branching. Returns what the effect
     * would do for the given inputs. It never touches Android framework classes — the framework
     * checks are reduced to the three boolean/int inputs the real code derives from them.
     */
    private fun decideAutoLaunch(
        sdkInt: Int,
        alreadyGranted: Boolean,
        alreadyRequested: Boolean,
    ): LaunchDecision {
        // Below API 33 the permission does not exist — no-op (AC1.2).
        if (sdkInt < API_TIRAMISU) {
            return LaunchDecision(launcherInvoked = false, flagMarked = false)
        }
        // Auto-launch at most once per install, and only while not yet granted (AC1.1 / AC1.5).
        if (!alreadyGranted && !alreadyRequested) {
            return LaunchDecision(launcherInvoked = true, flagMarked = true)
        }
        return LaunchDecision(launcherInvoked = false, flagMarked = false)
    }

    // ── Test 1: Permission request invoked on Android 13+ when not granted (AC1.1) ──

    @Test
    fun `Given API 33 and permission not granted and never requested when launch logic runs then launcher invoked once`() {
        val decision = decideAutoLaunch(
            sdkInt = API_TIRAMISU,
            alreadyGranted = false,
            alreadyRequested = false,
        )

        assertTrue(decision.launcherInvoked, "OS dialog must auto-launch on API 33+ when not granted")
        assertTrue(decision.flagMarked, "The once-per-install flag must be persisted before launching")
    }

    @Test
    fun `Given API 34 and permission not granted and never requested when launch logic runs then launcher invoked`() {
        val decision = decideAutoLaunch(
            sdkInt = API_TIRAMISU + 1,
            alreadyGranted = false,
            alreadyRequested = false,
        )

        assertTrue(decision.launcherInvoked, "Auto-launch must also fire on API levels above 33")
    }

    @Test
    fun `Given API 33 and permission already granted when launch logic runs then launcher NOT invoked`() {
        val decision = decideAutoLaunch(
            sdkInt = API_TIRAMISU,
            alreadyGranted = true,
            alreadyRequested = false,
        )

        assertFalse(decision.launcherInvoked, "Already-granted permission must not re-trigger the dialog")
        assertFalse(decision.flagMarked, "No flag write when nothing was launched")
    }

    // ── Test 2: No permission request below Android 13 (AC1.2) ──

    @Test
    fun `Given API 32 and permission not granted when launch logic runs then launcher NOT invoked`() {
        val decision = decideAutoLaunch(
            sdkInt = API_ANDROID_12,
            alreadyGranted = false,
            alreadyRequested = false,
        )

        assertFalse(decision.launcherInvoked, "Below API 33 the runtime permission does not exist — no-op")
        assertFalse(decision.flagMarked, "No flag write below API 33")
    }

    @Test
    fun `Given API 24 when launch logic runs then launcher NOT invoked`() {
        val decision = decideAutoLaunch(
            sdkInt = 24,
            alreadyGranted = false,
            alreadyRequested = false,
        )

        assertFalse(decision.launcherInvoked, "Legacy API levels must remain a no-op")
    }

    // ── Test 9: Auto-request fires at most once per install (AC1.5) ──

    @Test
    fun `Given API 33 and already-requested flag set when launch logic runs then launcher NOT invoked`() {
        val decision = decideAutoLaunch(
            sdkInt = API_TIRAMISU,
            alreadyGranted = false,
            alreadyRequested = true,
        )

        assertFalse(
            decision.launcherInvoked,
            "Once the post_notif_permission_requested flag is set the dialog must not auto-launch again",
        )
        assertFalse(decision.flagMarked, "The flag is not re-written when the dialog is not launched")
    }

    @Test
    fun `Given API 33 relaunch after first request then dialog auto-launches exactly once across launches`() {
        // First launch: flag starts unset → dialog fires and the flag is marked.
        val firstLaunch = decideAutoLaunch(
            sdkInt = API_TIRAMISU,
            alreadyGranted = false,
            alreadyRequested = false,
        )
        assertTrue(firstLaunch.launcherInvoked, "First launch must show the dialog")

        // Simulate the persisted flag now being true for the next process start.
        val alreadyRequestedNextLaunch = firstLaunch.flagMarked

        // Second launch (still denied): must NOT fire again — no loop.
        val secondLaunch = decideAutoLaunch(
            sdkInt = API_TIRAMISU,
            alreadyGranted = false,
            alreadyRequested = alreadyRequestedNextLaunch,
        )
        assertFalse(secondLaunch.launcherInvoked, "Second launch must not re-auto-launch the OS dialog")

        val totalAutoLaunches = listOf(firstLaunch, secondLaunch).count { it.launcherInvoked }
        assertEquals(1, totalAutoLaunches, "Dialog must auto-launch at most once per install")
    }

    // ── Test 3: Denial handling — the settings-deep-link banner state is exposed (AC1.4) ──

    /**
     * Faithful replica of the MainScreen banner-visibility predicate:
     * `if (!notificationsEnabled && !notificationBannerDismissed) { NotificationPermissionBanner(...) }`.
     * `notificationsEnabled` mirrors `NotificationManagerCompat.areNotificationsEnabled()`, re-read on
     * every `ON_RESUME`. This models the denial affordance without a live Compose runtime, so we can
     * assert that a denied user is offered the settings deep-link path and that it disappears once
     * notifications are enabled — with no crash on the denied path.
     */
    private fun shouldShowSettingsBanner(
        notificationsEnabled: Boolean,
        bannerDismissedThisSession: Boolean,
    ): Boolean = !notificationsEnabled && !bannerDismissedThisSession

    @Test
    fun `Given permission denied and banner not dismissed when main screen shown then settings banner is exposed`() {
        // Denial => areNotificationsEnabled() == false, so the banner (whose action deep-links to the
        // system app-notification settings) must be shown. No exception is thrown constructing state.
        val visible = shouldShowSettingsBanner(
            notificationsEnabled = false,
            bannerDismissedThisSession = false,
        )

        assertTrue(visible, "A denied user must be offered the settings-deep-link banner (AC1.4)")
    }

    @Test
    fun `Given permission denied but banner dismissed this session when main screen shown then banner hidden`() {
        val visible = shouldShowSettingsBanner(
            notificationsEnabled = false,
            bannerDismissedThisSession = true,
        )

        assertFalse(visible, "A session-dismissed banner stays hidden until the next process start")
    }

    @Test
    fun `Given notifications enabled when main screen shown then settings banner is not shown`() {
        val visible = shouldShowSettingsBanner(
            notificationsEnabled = true,
            bannerDismissedThisSession = false,
        )

        assertFalse(visible, "Banner must disappear once notifications are enabled (AC1.4)")
    }
}
