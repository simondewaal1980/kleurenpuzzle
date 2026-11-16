package nl.kleuren.app.data

import java.io.File

/**
 * Waar een bestand vandaan komt.
 *
 * Meegeleverde platen staan in de APK (assets, alleen-lezen). Eigen foto's
 * staan in de interne opslag van de app (filesDir). De rest van de app hoeft
 * dat verschil niet te kennen: de repository regelt het lezen.
 */
sealed interface ContentSource {
    data class Asset(val path: String) : ContentSource
    data class LocalFile(val file: File) : ContentSource
}

/**
 * Alles wat de bibliotheek en het kleurbord van één plaat moeten weten,
 * zonder de (grote) plaat zelf al te laden.
 */
data class PlateSummary(
    val id: String,
    val title: String,
    val subtitle: String,
    val packId: String,
    val packTitle: String,
    val plateSource: ContentSource,
    val previewSource: ContentSource,
    val regionCount: Int,
    val paletteSize: Int,
    val credit: String = "",
    val license: String = "",
    val sourceUrl: String = "",
    val isUserPlate: Boolean = false,
    val createdAt: Long = 0L,
)

/** Een pack is een groep landschappen, bijvoorbeeld "Alpen & Meren". */
data class PackSummary(
    val id: String,
    val title: String,
    val description: String,
    val plates: List<PlateSummary>,
)

/** True als de plaat door de gebruiker is gemaakt (en dus verwijderd mag worden). */
val PlateSummary.canBeDeleted: Boolean get() = isUserPlate
