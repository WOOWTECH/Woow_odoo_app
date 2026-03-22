package io.woowtech.odoo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.woowtech.odoo.data.push.DeepLinkManager
import io.woowtech.odoo.data.push.DeepLinkValidator
import io.woowtech.odoo.data.push.NotificationHelper
import io.woowtech.odoo.ui.navigation.WoowOdooNavHost
import io.woowtech.odoo.ui.theme.WoowTechOdooTheme
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deepLinkManager: DeepLinkManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLinkIntent(intent)

        setContent {
            WoowTechOdooTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WoowOdooNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    /**
     * Extracts and validates deep link URL from notification tap intent.
     * Stores validated URL in DeepLinkManager for consumption after auth.
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        val actionUrl = intent?.getStringExtra(NotificationHelper.EXTRA_ACTION_URL) ?: return

        // Validate URL before storing
        if (DeepLinkValidator.isValid(url = actionUrl, serverHost = "")) {
            deepLinkManager.setPending(actionUrl)
            Timber.d("Deep link pending: %s", actionUrl)
        } else {
            Timber.w("Rejected invalid deep link: %s", actionUrl)
        }
    }
}
