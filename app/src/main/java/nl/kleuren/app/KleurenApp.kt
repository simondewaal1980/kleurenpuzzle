package nl.kleuren.app

import android.app.Application
import nl.kleuren.app.data.AppContainer

/**
 * De Application-klasse leeft zolang het app-proces leeft. Hier maken we één
 * keer de repositories aan (een simpele vorm van dependency injection).
 *
 * Waarom geen Hilt/Dagger? Voor een project van deze omvang is een handmatige
 * container overzichtelijker en leer je meteen wat die frameworks voor je doen.
 */
class KleurenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
