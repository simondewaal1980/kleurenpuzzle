package nl.kleuren.app.data

import kotlinx.serialization.Serializable

/**
 * JSON-modellen die precies passen bij het manifest dat de generator schrijft
 * (app/src/main/assets/plates/manifest.json).
 *
 * Omdat we `ignoreUnknownKeys = true` gebruiken, kan de generator later extra
 * velden toevoegen zonder dat oude app-versies stukgaan.
 */
@Serializable
data class ManifestRoot(
    val version: Int = 1,
    val generatedAt: String = "",
    val packs: List<ManifestPack> = emptyList(),
)

@Serializable
data class ManifestPack(
    val id: String,
    val title: String,
    val description: String = "",
    val plates: List<ManifestPlate> = emptyList(),
)

@Serializable
data class ManifestPlate(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val asset: String,
    val preview: String,
    val regionCount: Int = 0,
    val paletteSize: Int = 0,
    val credit: String = "",
    val license: String = "",
    val sourceUrl: String = "",
    /**
     * Optionele bronfoto in de assets. Hiermee kan de app een landschap op het
     * toestel opnieuw genereren met een andere moeilijkheidsgraad.
     */
    val source: String = "",
)

/**
 * Index van de platen die de gebruiker zelf heeft gemaakt ("Mijn foto's").
 * De bestanden zelf staan naast deze index in filesDir/myplates/.
 */
@Serializable
data class UserPlateIndex(
    val version: Int = 1,
    val plates: List<UserPlateEntry> = emptyList(),
)

@Serializable
data class UserPlateEntry(
    val id: String,
    val title: String,
    val plateFile: String,
    val previewFile: String,
    val regionCount: Int,
    val paletteSize: Int,
    val createdAt: Long,
    /**
     * De bronfoto op max. 1600 px, zodat de gebruiker later een andere
     * moeilijkheid kan kiezen. Leeg bij foto's van vóór deze functie; dan
     * gebruiken we de preview als bron.
     */
    val sourceFile: String = "",
    /** Naam van het gebruikte [nl.kleuren.pipeline.Difficulty]-niveau. */
    val difficulty: String = "",
    /**
     * Moment waarop de plaat voor het laatst opnieuw is gemaakt. Het bord
     * gebruikt dit om te merken dat de plaat is gewijzigd.
     */
    val updatedAt: Long = 0L,
)
