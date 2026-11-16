package nl.kleuren.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.kleuren.app.data.AppContainer
import nl.kleuren.app.data.AppSettings
import nl.kleuren.app.data.PlateRepository
import nl.kleuren.app.data.ProgressRepository
import nl.kleuren.app.data.SettingsRepository
import nl.kleuren.app.data.ThemeMode
import nl.kleuren.app.data.UserPlateStore

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * ViewModel voor de instellingen.
 *
 * De instellingen zelf staan in [SettingsRepository] (SharedPreferences).
 * Deze ViewModel voegt daar tijdelijke UI-status aan toe: bezig met wissen en
 * een eenmalige melding voor de snackbar.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val progressRepository: ProgressRepository,
    private val plateRepository: PlateRepository,
    private val userPlateStore: UserPlateStore,
) : ViewModel() {

    private data class Transient(
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val transient = MutableStateFlow(Transient())

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        transient,
    ) { settings, extra ->
        SettingsUiState(settings = settings, busy = extra.busy, message = extra.message)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setTheme(mode: ThemeMode) = settingsRepository.setThemeMode(mode)
    fun setShowNumbers(value: Boolean) = settingsRepository.setShowNumbers(value)
    fun setHints(value: Boolean) = settingsRepository.setHintsEnabled(value)
    fun setHaptics(value: Boolean) = settingsRepository.setHapticsEnabled(value)
    fun setMagic(value: Boolean) = settingsRepository.setMagicFillEnabled(value)
    fun setColorBlind(value: Boolean) = settingsRepository.setColorBlindMode(value)

    fun clearProgress() {
        viewModelScope.launch {
            transient.update { it.copy(busy = true) }
            progressRepository.clearAll()
            transient.value = Transient(message = "Alle voortgang is gewist.")
        }
    }

    fun clearMyPhotos() {
        viewModelScope.launch {
            transient.update { it.copy(busy = true) }
            val userIds = userPlateStore.entries().map { it.id }
            userIds.forEach { progressRepository.delete(it) }
            plateRepository.clearUserPlates()
            transient.value = Transient(message = "Je eigen foto's zijn verwijderd.")
        }
    }

    fun clearEverything() {
        viewModelScope.launch {
            transient.update { it.copy(busy = true) }
            progressRepository.clearAll()
            plateRepository.clearUserPlates()
            transient.value = Transient(message = "Alle gegevens zijn gewist.")
        }
    }

    fun clearMessage() {
        transient.update { it.copy(message = null) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    progressRepository = container.progressRepository,
                    plateRepository = container.plateRepository,
                    userPlateStore = container.userPlateStore,
                )
            }
        }
    }
}
