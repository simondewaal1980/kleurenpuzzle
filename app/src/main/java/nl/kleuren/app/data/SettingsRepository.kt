package nl.kleuren.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Donker/licht: volg het systeem of forceer een keuze. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Alle gebruikersinstellingen op één plek.
 *
 * Waarom SharedPreferences en niet DataStore (zoals in het plan)?
 *  - SharedPreferences zit in Android zelf, heeft geen extra bibliotheek of
 *    code-generatie nodig en is voor een handvol schakelaars ruim voldoende.
 *  - De repository-interface hieronder is bewust simpel. Later vervangen door
 *    DataStore is daardoor een lokale wijziging.
 */
data class AppSettings(
    val showNumbers: Boolean = true,
    val hintsEnabled: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val magicFillEnabled: Boolean = true,
    val colorBlindMode: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * Leest en schrijft instellingen en stelt ze beschikbaar als StateFlow.
 *
 * Compose-schermen kunnen `settings.collectAsStateWithLifecycle()` gebruiken;
 * een schakelaar aanpassen is dan meteen zichtbaar in de hele app.
 */
class SettingsRepository(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences("kleuren_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readFromPreferences())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun readFromPreferences(): AppSettings = AppSettings(
        showNumbers = preferences.getBoolean(KEY_SHOW_NUMBERS, true),
        hintsEnabled = preferences.getBoolean(KEY_HINTS, false),
        hapticsEnabled = preferences.getBoolean(KEY_HAPTICS, true),
        magicFillEnabled = preferences.getBoolean(KEY_MAGIC, true),
        colorBlindMode = preferences.getBoolean(KEY_COLOR_BLIND, false),
        themeMode = runCatching {
            ThemeMode.valueOf(preferences.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM),
    )

    fun setShowNumbers(value: Boolean) = update(KEY_SHOW_NUMBERS, value)
    fun setHintsEnabled(value: Boolean) = update(KEY_HINTS, value)
    fun setHapticsEnabled(value: Boolean) = update(KEY_HAPTICS, value)
    fun setMagicFillEnabled(value: Boolean) = update(KEY_MAGIC, value)
    fun setColorBlindMode(value: Boolean) = update(KEY_COLOR_BLIND, value)

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    private fun update(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
        _settings.value = when (key) {
            KEY_SHOW_NUMBERS -> _settings.value.copy(showNumbers = value)
            KEY_HINTS -> _settings.value.copy(hintsEnabled = value)
            KEY_HAPTICS -> _settings.value.copy(hapticsEnabled = value)
            KEY_MAGIC -> _settings.value.copy(magicFillEnabled = value)
            KEY_COLOR_BLIND -> _settings.value.copy(colorBlindMode = value)
            else -> _settings.value
        }
    }

    private companion object {
        const val KEY_SHOW_NUMBERS = "show_numbers"
        const val KEY_HINTS = "hints_enabled"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_MAGIC = "magic_fill_enabled"
        const val KEY_COLOR_BLIND = "color_blind_mode"
        const val KEY_THEME = "theme_mode"
    }
}
