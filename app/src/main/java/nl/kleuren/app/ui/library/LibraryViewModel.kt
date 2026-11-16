package nl.kleuren.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.kleuren.app.data.AppContainer
import nl.kleuren.app.data.PlateRepository
import nl.kleuren.app.data.PlateSummary
import nl.kleuren.app.data.ProgressRepository

/** De drie tabbladen van de bibliotheek. */
enum class LibraryTab(val label: String) {
    LANDSCAPES("Landschappen"),
    CONTINUE("Verder gaan"),
    MY_PHOTOS("Mijn foto's"),
}

/** Eén kaart in de bibliotheek, met de berekende voortgang. */
data class PlateItem(
    val summary: PlateSummary,
    val progress: Float,
    val complete: Boolean,
    val lastOpened: Long,
)

data class PackUi(
    val id: String,
    val title: String,
    val description: String,
    val plates: List<PlateItem>,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tab: LibraryTab = LibraryTab.LANDSCAPES,
    val packs: List<PackUi> = emptyList(),
    val continueItems: List<PlateItem> = emptyList(),
    val myPhotos: List<PlateItem> = emptyList(),
)

/**
 * ViewModel voor de bibliotheek.
 *
 * Een ViewModel overleeft schermdraaiingen en houdt de UI-state buiten Compose.
 * De UI is een pure functie van [state]: dat maakt het scherm voorspelbaar en
 * goed testbaar.
 *
 * We combineren vier flows:
 *  - de geladen packs (manifest + eigen foto's),
 *  - de voortgang per plaat,
 *  - wanneer een plaat voor het laatst is geopend,
 *  - het geselecteerde tabblad.
 */
class LibraryViewModel(
    private val plateRepository: PlateRepository,
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(LibraryTab.LANDSCAPES)

    val state: StateFlow<LibraryUiState> = combine(
        plateRepository.state,
        progressRepository.progress,
        progressRepository.lastOpened,
        selectedTab,
    ) { library, progress, lastOpened, tab ->
        // Zet elke plaat om naar een kaart met voortgang. We tellen alleen
        // ids die echt bestaan; mocht een plaat ooit minder vlakken krijgen,
        // dan blijft het percentage betrouwbaar.
        fun toItem(summary: PlateSummary): PlateItem {
            val filled = progress[summary.id].orEmpty()
            val total = summary.regionCount.coerceAtLeast(1)
            val count = filled.size.coerceAtMost(total)
            return PlateItem(
                summary = summary,
                progress = count.toFloat() / total,
                complete = count >= total && summary.regionCount > 0,
                lastOpened = lastOpened[summary.id] ?: summary.createdAt,
            )
        }

        val allPacks = library.packs.filterNot { it.id == USER_PACK_ID }
        val packs = allPacks.map { pack ->
            PackUi(
                id = pack.id,
                title = pack.title,
                description = pack.description,
                plates = pack.plates.map(::toItem),
            )
        }
        val allItems = library.packs.flatMap { pack -> pack.plates.map(::toItem) }

        LibraryUiState(
            loading = library.loading,
            error = library.error,
            tab = tab,
            packs = packs,
            continueItems = allItems
                .filter { it.progress > 0f && !it.complete }
                .sortedByDescending { it.lastOpened },
            myPhotos = allItems.filter { it.summary.isUserPlate },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun selectTab(tab: LibraryTab) {
        selectedTab.value = tab
    }

    fun refresh() {
        viewModelScope.launch { plateRepository.refresh() }
    }

    /** Verwijdert een eigen foto inclusief de bijbehorende voortgang. */
    fun deleteUserPlate(plateId: String) {
        viewModelScope.launch {
            progressRepository.delete(plateId)
            plateRepository.deleteUserPlate(plateId)
        }
    }

    companion object {
        private const val USER_PACK_ID = "mijn-fotos"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                LibraryViewModel(
                    plateRepository = container.plateRepository,
                    progressRepository = container.progressRepository,
                )
            }
        }
    }
}
