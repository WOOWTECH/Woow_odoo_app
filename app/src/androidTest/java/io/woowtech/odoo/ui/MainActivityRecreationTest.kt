package io.woowtech.odoo.ui

import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test skeletons for the L1/L2/L6 lifecycle fixes.
 *
 * These tests require a connected device or emulator with the Hilt test runner. They are
 * annotated with [@Ignore] because no emulator is available in the current CI environment.
 *
 * ## How to run
 *
 * ```bash
 * # Ensure an emulator is running or a device is connected, then:
 * ./gradlew :app:connectedDebugAndroidTest \
 *   --tests "io.woowtech.odoo.ui.MainActivityRecreationTest"
 * ```
 *
 * Or run individual tests from Android Studio by right-clicking the test method and choosing
 * "Run '...'" with a connected device selected.
 *
 * ## Expected behaviours
 *
 * - FLAG_SECURE: The Recents thumbnail for the app should appear blank/blurred on all
 *   Android versions. Verify manually by backgrounding the app and opening the Recents screen.
 *
 * - Observer count: After rotating 3 times, triggering ON_STOP (backgrounding) must call
 *   `authViewModel.onAppBackgrounded()` exactly once per background event — not N times
 *   (where N is the rotation count). Verify by observing the isAuthenticated StateFlow;
 *   it must transition false→true→false exactly once per bg cycle.
 *
 * - Process death: Force-stopping the app via `adb shell am force-stop io.woowtech.odoo`
 *   and relaunching must always show the auth screen, never MainScreen. Verify by checking
 *   the currently visible Composable (auth screen tag must be present).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivityRecreationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * Verifies that FLAG_SECURE is present after activity recreation (simulated rotation).
     *
     * With L5 fix (android:configChanges) the activity is not recreated on rotation, so
     * [ActivityScenario.recreate] simulates the case where recreate() could still be
     * triggered (e.g. night mode toggle). FLAG_SECURE must be present both before and
     * after recreation because it is set unconditionally in [MainActivity.onCreate].
     *
     * Expected: Both assertions pass — FLAG_SECURE is never absent.
     */
    @Ignore(
        "Requires connected device/emulator. Run with: " +
            "./gradlew :app:connectedDebugAndroidTest --tests " +
            "\"io.woowtech.odoo.ui.MainActivityRecreationTest.given biometric screen visible when activity recreated then FLAG_SECURE is set after recreation\""
    )
    @Test
    fun `Given biometric screen visible when activity recreated then FLAG_SECURE is set after recreation`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            assertTrue(
                "FLAG_SECURE must be set before recreation (set in onCreate)",
                flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
        }

        scenario.recreate()

        scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            assertTrue(
                "FLAG_SECURE must be set after recreation — no gap window allowed",
                flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
        }

        scenario.close()
    }

    /**
     * Verifies that relaunching after process death shows the auth screen.
     *
     * The [Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY] flag simulates the Recents-screen
     * relaunch path that can restore the NavController's saved back-stack. Before the L2
     * fix, this could route directly to MainScreen. After the fix, the imperative
     * LaunchedEffect in [io.woowtech.odoo.ui.navigation.WoowOdooNavHost] pops the stack and
     * navigates to Screen.Auth.
     *
     * Expected: The auth screen composable is displayed (biometric or PIN screen depending
     * on device settings), and MainScreen is NOT visible.
     */
    @Ignore(
        "Requires connected device/emulator with App Lock enabled in app settings. Run with: " +
            "./gradlew :app:connectedDebugAndroidTest --tests " +
            "\"io.woowtech.odoo.ui.MainActivityRecreationTest.given process death when app relaunched from history then auth screen shown\""
    )
    @Test
    fun `Given process death when app relaunched from history then auth screen shown`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        }
        val scenario = ActivityScenario.launch<MainActivity>(intent)

        // The auth screen must be visible — not MainScreen.
        // With Compose use onNodeWithTag or onNodeWithText to assert the auth destination.
        // Example (requires test tags on auth composables):
        //   composeTestRule.onNodeWithTag("auth_screen").assertIsDisplayed()
        //   composeTestRule.onNodeWithTag("main_screen").assertDoesNotExist()

        scenario.close()
    }

    /**
     * Verifies that the [ProcessLifecycleOwner] observer does not accumulate after N
     * Activity recreations.
     *
     * Before the L1 fix, each recreation would add a new observer to ProcessLifecycleOwner,
     * so after 3 rotations, `onAppBackgrounded()` would be called 3 times per ON_STOP event.
     * After the fix (plus android:configChanges preventing most recreations), a single
     * background event must trigger exactly one `onAppBackgrounded()` call.
     *
     * Verification approach: Use ActivityScenario.recreate() 3 times, then background the
     * app and count the number of times isAuthenticated transitions from true to false.
     * The count must be exactly 1.
     *
     * Expected: isAuthenticated transitions false exactly once per background event.
     */
    @Ignore(
        "Requires connected device/emulator. Verifies no observer leak from L1 fix. Run with: " +
            "./gradlew :app:connectedDebugAndroidTest --tests " +
            "\"io.woowtech.odoo.ui.MainActivityRecreationTest.given activity recreated N times when app backgrounded then onAppBackgrounded fires exactly once\""
    )
    @Test
    fun `Given activity recreated N times when app backgrounded then onAppBackgrounded fires exactly once`() {
        // Implementation approach:
        // 1. Launch activity, set isAuthenticated=true via test double.
        // 2. Call scenario.recreate() 3 times.
        // 3. Trigger ON_STOP (via UiAutomation or scenario.moveToState(Lifecycle.State.CREATED)).
        // 4. Collect isAuthenticated emissions and assert exactly one false transition fired.
        //
        // This test requires Hilt test injection to replace the real AuthViewModel with a
        // spy so onAppBackgrounded() call count can be tracked.
    }
}
