package nl.kleuren.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Eén object dat alle langlevende onderdelen bezit.
 *
 * De Activity maakt dit niet aan (die gaat bij een schermdraai kapot); de
 * Application doet dat. ViewModels krijgen alleen de repositories die ze nodig
 * hebben via hun factory.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /** Scope voor werk dat langer duurt dan één scherm (laden, opslaan). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository = SettingsRepository(appContext)
    val progressRepository = ProgressRepository(appContext)
    val userPlateStore = UserPlateStore(appContext)
    val landscapeVariantStore = LandscapeVariantStore(appContext)
    val plateRepository = PlateRepository(appContext, userPlateStore, landscapeVariantStore)
    val plateGenerator = PlateGenerator(
        context = appContext,
        userPlateStore = userPlateStore,
        landscapeVariantStore = landscapeVariantStore,
        plateRepository = plateRepository,
    )

    init {
        applicationScope.launch {
            plateRepository.refresh()
        }
        applicationScope.launch {
            // Zorg dat de voortgang klaar is voordat een bord opent.
            progressRepository.awaitReady()
        }
    }
}
