package io.woowtech.odoo

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hermetic on-emulator proof that each installed flavor has its own identity and can launch. */
@RunWith(AndroidJUnit4::class)
class BrandLaunchInstrumentationTest {

    @Test
    fun selectedBrand_launchesItsRealMainActivity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expectedPackage = if (BuildConfig.APP_BRAND == "apporo") {
            "com.apporo.odoo.debug"
        } else {
            "io.woowtech.odoo.debug"
        }

        assertEquals(expectedPackage, context.packageName)
        assertEquals(BuildConfig.APP_BRAND, AppBrand.current.code)
        assertEquals(
            context.getString(R.string.app_name),
            context.packageManager.getApplicationLabel(context.applicationInfo).toString(),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
            }
        }
    }

    @Test
    fun selectedBrand_registersOnlyItsOwnDeepLinkScheme() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = context.packageManager
        fun resolverFor(scheme: String) = packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://open?url=%2Fweb")).apply {
                setPackage(context.packageName)
            },
            0,
        )

        assertNotNull(resolverFor(AppBrand.current.scheme))
        val foreignScheme = if (BuildConfig.APP_BRAND == "apporo") "woowodoo" else "apporoodoo-dev"
        assertTrue(resolverFor(foreignScheme) == null)
    }
}
