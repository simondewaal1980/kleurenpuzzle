package nl.kleuren.app.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.kleuren.app.data.AppContainer
import nl.kleuren.app.data.PlateRepository
import nl.kleuren.app.data.ProgressRepository
import nl.kleuren.app.data.SettingsRepository
import nl.kleuren.format.Plate

/** Resultaat van een tik op het bord; de UI gebruikt dit voor haptics. */
enum class FillOutcome {
    FILLED,
    COMPLETED,
    ALREADY_FILLED,
    WRONG_COLOR,
    NO_PLATE,
}

data class BoardUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val plateId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val credit: String = "",
    val plate: Plate? = null,
    val filled: Set<Int> = emptySet(),
    val selectedColor: Int = 0,
    val showNumbers: Boolean = true,
    val hintsEnabled: Boolean = false,
    val colorBlindMode: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val magicFillEnabled: Boolean = true,
    val undoDepth: Int = 0,
    val completed: Boolean = false,
    val message: String? = null,
) {
    val totalRegions: Int get() = plate?.regionCount ?: 0
    val filledCount: Int get() = filled.count { plate?.regionById(it) != null }
    val progress: Float
        get() = if (totalRegions == 0) 0f else filledCount.toFloat() / totalRegions
    val selectedColorCount: Int
        get() = plate?.palette?.size ?: 0
}

/**
 * Alle speellogica van één kleurplaat.
 *
 * De ViewModel bezit:
 *  - de geladen plaat,
 *  - welke vlakken gevuld zijn,
 *  - de undo-stack (alleen in het geheugen, zoals het plan voorschrijft),
 *  - de geselecteerde kleur en UI-meldingen.
 *
 * Elke vulactie wordt direct opgeslagen via [ProgressRepository]. Daardoor ben
 * je nooit voortgang kwijt, ook niet als de app wordt afgesloten.
 */
class BoardViewModel(
    private val plateRepository: PlateRepository,
    private val progressRepository: ProgressRepository,
    private val settingsRepository: SettingsRepository,
    private val plateId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(BoardUiState(plateId = plateId))
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    /** Undo-acties: één lijst met regio-ids per gebruikersactie. */
    private val undoStack = ArrayDeque<List<Int>>()

    /** Pas na het laden mogen externe voortgangswijzigingen worden toegepast. */
    @Volatile
    private var loaded = false

    init {
        // Instellingen kunnen tijdens het bord wijzigen (bijv. via de
        // instellingen of de hint-knop). We volgen ze live.
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update { current ->
                    current.copy(
                        showNumbers = settings.showNumbers,
                        hintsEnabled = settings.hintsEnabled,
                        colorBlindMode = settings.colorBlindMode,
                        hapticsEnabled = settings.hapticsEnabled,
                        magicFillEnabled = settings.magicFillEnabled,
                        selectedColor = current.selectedColor.coerceIn(
                            0,
                            (current.plate?.palette?.size ?: 1) - 1,
                        ),
                    )
                }
            }
        }

        viewModelScope.launch {
            try {
                // Wacht tot de bibliotheek geladen is. Nodig als dit scherm
                // direct na het opstarten (of na procesherstel) opent.
                val library = plateRepository.state.filter { !it.loading }.first()
                val summary = library.packs.asSequence()
                    .flatMap { it.plates.asSequence() }
                    .firstOrNull { it.id == plateId }
                    ?: run {
                        _state.update {
                            it.copy(
                                loading = false,
                                error = "Deze kleurplaat is niet gevonden.",
                            )
                        }
                        return@launch
                    }

                val plate = plateRepository.loadPlate(summary)
                val saved = progressRepository.filledRegions(plateId)
                    .filter { plate.regionById(it) != null }
                    .toSet()
                progressRepository.markOpened(plateId)

                val completed = saved.size >= plate.regionCount
                loaded = true
                _state.update { current ->
                    current.copy(
                        loading = false,
                        error = null,
                        title = summary.title,
                        subtitle = summary.subtitle,
                        credit = summary.credit,
                        plate = plate,
                        filled = saved,
                        selectedColor = firstUnfilledColor(plate, saved),
                        completed = completed,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "De kleurplaat kon niet worden geopend: ${e.message}",
                    )
                }
            }
        }

        // Als de voortgang buiten dit bord om verandert (bijvoorbeeld via
        // "Voortgang wissen" in de instellingen), passen we het bord aan.
        // Eigen vulacties emitteren dezelfde set en doen dus niets.
        viewModelScope.launch {
            progressRepository.progress.collect { allProgress ->
                val current = _state.value
                val plate = current.plate
                if (!loaded || plate == null) return@collect
                val external = allProgress[plateId].orEmpty()
                    .filter { plate.regionById(it) != null }
                    .toSet()
                if (external != current.filled) {
                    undoStack.clear()
                    _state.update {
                        it.copy(
                            filled = external,
                            completed = external.size >= plate.regionCount,
                            undoDepth = 0,
                        )
                    }
                }
            }
        }
    }

    /**
     * Vult één vlak. Alleen als de geselecteerde kleur klopt; anders krijgt de
     * gebruiker een vriendelijke melding (geen straf, geen tijdverlies).
     */
    fun fill(regionId: Int): FillOutcome {
        val current = _state.value
        val plate = current.plate ?: return FillOutcome.NO_PLATE
        val region = plate.regionById(regionId) ?: return FillOutcome.NO_PLATE

        if (current.filled.contains(regionId)) {
            _state.update { it.copy(message = "Dit vlak is al ingekleurd.") }
            return FillOutcome.ALREADY_FILLED
        }
        if (region.colorIndex != current.selectedColor) {
            _state.update {
                it.copy(
                    message = "Dit vlak hoort bij kleur ${region.colorIndex + 1}. " +
                        "Kies die kleur hieronder.",
                )
            }
            return FillOutcome.WRONG_COLOR
        }

        val newFilled = current.filled + regionId
        undoStack.addLast(listOf(regionId))
        val completed = newFilled.size >= plate.regionCount
        _state.update {
            it.copy(
                filled = newFilled,
                completed = completed,
                undoDepth = undoStack.size,
                message = if (completed) null else it.message,
            )
        }
        persist(newFilled)
        return if (completed) FillOutcome.COMPLETED else FillOutcome.FILLED
    }

    /**
     * "Magisch vullen": alle resterende vlakken van de geselecteerde kleur in
     * één keer. Staat standaard aan, maar is uit te zetten in de instellingen.
     * De actie is in één keer ongedaan te maken.
     */
    fun magicFill(): Int {
        val current = _state.value
        val plate = current.plate ?: return 0
        if (!current.magicFillEnabled) return 0

        val ids = plate.regions
            .filter { it.colorIndex == current.selectedColor && it.id !in current.filled }
            .map { it.id }
        if (ids.isEmpty()) {
            _state.update {
                it.copy(message = "Alle vlakken van kleur ${it.selectedColor + 1} zijn al gevuld.")
            }
            return 0
        }

        val newFilled = current.filled + ids
        undoStack.addLast(ids)
        val completed = newFilled.size >= plate.regionCount
        _state.update {
            it.copy(
                filled = newFilled,
                completed = completed,
                undoDepth = undoStack.size,
                message = "${ids.size} vlakken gevuld met kleur ${it.selectedColor + 1}.",
            )
        }
        persist(newFilled)
        return ids.size
    }

    /** Maakt de laatste vulactie ongedaan (één vlak of één magische actie). */
    fun undo(): Boolean {
        val current = _state.value
        val plate = current.plate ?: return false
        val action = undoStack.removeLastOrNull() ?: return false
        val newFilled = current.filled - action.toSet()
        _state.update {
            it.copy(
                filled = newFilled,
                completed = false,
                undoDepth = undoStack.size,
                message = if (action.size > 1) {
                    "${action.size} vlakken weer leeggemaakt."
                } else {
                    "Laatste vlak weer leeggemaakt."
                },
            )
        }
        persist(newFilled)
        return true
    }

    fun selectColor(colorIndex: Int) {
        val size = _state.value.plate?.palette?.size ?: return
        if (colorIndex in 0 until size) {
            _state.update { it.copy(selectedColor = colorIndex) }
        }
    }

    fun toggleNumbers() {
        settingsRepository.setShowNumbers(!_state.value.showNumbers)
    }

    fun toggleHints() {
        settingsRepository.setHintsEnabled(!_state.value.hintsEnabled)
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun persist(filled: Set<Int>) {
        progressRepository.update(plateId, filled)
    }

    private fun firstUnfilledColor(plate: Plate, filled: Set<Int>): Int {
        val remainingColors = plate.regions
            .asSequence()
            .filter { it.id !in filled }
            .map { it.colorIndex }
            .toSet()
        return remainingColors.minOrNull() ?: 0
    }

    companion object {
        fun factory(container: AppContainer, plateId: String) = viewModelFactory {
            initializer {
                BoardViewModel(
                    plateRepository = container.plateRepository,
                    progressRepository = container.progressRepository,
                    settingsRepository = container.settingsRepository,
                    plateId = plateId,
                )
            }
        }
    }
}
