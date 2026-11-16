package nl.kleuren.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.kleuren.app.ui.AppRoot
import nl.kleuren.app.ui.theme.KleurenTheme

/**
 * De enige Activity van de app.
 *
 * Alle schermen zijn Compose-composables; de navigatie staat in [AppRoot].
 * Hier regelen we alleen edge-to-edge (content onder de systeembalken) en het
 * thema, dat live meebeweegt met de instellingen.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = application as KleurenApp
            val settings by app.container.settingsRepository.settings.collectAsStateWithLifecycle()

            KleurenTheme(themeMode = settings.themeMode) {
                AppRoot(container = app.container)
            }
        }
    }
}
