package nl.kleuren.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import nl.kleuren.format.Plate
import nl.kleuren.format.PlateCodec
import java.io.File

/** Status van de bibliotheek: nog laden, gelukt, of een leesbare fout. */
data class LibraryState(
    val packs: List<PackSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Toegangspoort tot alle kleurplaten: de meegeleverde assets en de eigen foto's.
 *
 * Belangrijk voor de performance:
 *  - De bibliotheek leest alleen het manifest en de kleine previews, niet alle
 *    platen. Een plaat wordt pas gedecodeerd als je hem opent.
 *  - Van geopende platen houden we een kleine LRU-cache bij (max. 3), zodat
 *    heen en weer navigeren niet opnieuw hoeft te decoderen.
 *  - Preview-bitmaps hebben een eigen LRU-cache met een geheugenlimiet.
 */
class PlateRepository(
    context: Context,
    private val userPlateStore: UserPlateStore,
) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val userPlatesDir = File(appContext.filesDir, "myplates")
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val plateCache = object : LruCache<String, Plate>(3) {}
    private val previewCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Leest het manifest en de index van eigen foto's opnieuw in. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            val manifest = readManifest()
            val userEntries = userPlateStore.entries()

            val packs = manifest.packs.map { pack ->
                PackSummary(
                    id = pack.id,
                    title = pack.title,
                    description = pack.description,
                    plates = pack.plates.map { plate ->
                        PlateSummary(
                            id = plate.id,
                            title = plate.title,
                            subtitle = plate.subtitle,
                            packId = pack.id,
                            packTitle = pack.title,
                            plateSource = ContentSource.Asset(plate.asset),
                            previewSource = ContentSource.Asset(plate.preview),
                            regionCount = plate.regionCount,
                            paletteSize = plate.paletteSize,
                            credit = plate.credit,
                            license = plate.license,
                            sourceUrl = plate.sourceUrl,
                        )
                    },
                )
            }.toMutableList()

            if (userEntries.isNotEmpty()) {
                packs += PackSummary(
                    id = USER_PACK_ID,
                    title = "Mijn foto's",
                    description = "Door jou gemaakt, lokaal op dit toestel.",
                    plates = userEntries.map { entry ->
                        PlateSummary(
                            id = entry.id,
                            title = entry.title,
                            subtitle = "Eigen foto · ${entry.paletteSize} kleuren",
                            packId = USER_PACK_ID,
                            packTitle = "Mijn foto's",
                            plateSource = ContentSource.LocalFile(File(userPlatesDir, entry.plateFile)),
                            previewSource = ContentSource.LocalFile(File(userPlatesDir, entry.previewFile)),
                            regionCount = entry.regionCount,
                            paletteSize = entry.paletteSize,
                            credit = "Eigen foto",
                            isUserPlate = true,
                            createdAt = entry.createdAt,
                        )
                    },
                )
            }

            _state.value = LibraryState(packs = packs, loading = false, error = null)
        } catch (e: Exception) {
            Log.e(TAG, "Kon bibliotheek niet laden", e)
            _state.value = LibraryState(
                packs = emptyList(),
                loading = false,
                error = "De kleurplaten konden niet worden geladen. " +
                    "Controleer of de app-content aanwezig is (plates/manifest.json).",
            )
        }
    }

    fun findSummary(plateId: String): PlateSummary? =
        _state.value.packs.asSequence()
            .flatMap { it.plates.asSequence() }
            .firstOrNull { it.id == plateId }

    /** Laadt en decodeert één plaat. Gebruikt de cache als dat kan. */
    suspend fun loadPlate(summary: PlateSummary): Plate = withContext(Dispatchers.IO) {
        plateCache.get(summary.id)?.let { return@withContext it }

        val plate = when (val source = summary.plateSource) {
            is ContentSource.Asset -> assets.open(source.path).use { PlateCodec.decode(it) }
            is ContentSource.LocalFile -> PlateCodec.read(source.file)
        }
        plateCache.put(summary.id, plate)
        plate
    }

    /** Decodeert de preview-JPEG tot een Bitmap (voor de bibliotheekkaarten). */
    suspend fun preview(summary: PlateSummary): Bitmap? = withContext(Dispatchers.IO) {
        previewCache.get(summary.id)?.let { return@withContext it }
        try {
            val bytes = when (val source = summary.previewSource) {
                is ContentSource.Asset -> assets.open(source.path).use { it.readBytes() }
                is ContentSource.LocalFile -> source.file.readBytes()
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) previewCache.put(summary.id, bitmap)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Preview niet beschikbaar voor ${summary.id}", e)
            null
        }
    }

    suspend fun deleteUserPlate(plateId: String) = withContext(Dispatchers.IO) {
        userPlateStore.delete(plateId)
        plateCache.remove(plateId)
        previewCache.remove(plateId)
        refresh()
    }

    suspend fun clearUserPlates() = withContext(Dispatchers.IO) {
        userPlateStore.clear()
        plateCache.evictAll()
        previewCache.evictAll()
        refresh()
    }

    /** Verwijdert de preview uit de cache, bijvoorbeeld na een wijziging. */
    fun invalidatePreview(plateId: String) {
        previewCache.remove(plateId)
    }

    private fun readManifest(): ManifestRoot {
        val text = assets.open("plates/manifest.json").bufferedReader().use { it.readText() }
        return json.decodeFromString<ManifestRoot>(text)
    }

    private companion object {
        const val TAG = "PlateRepository"
        const val USER_PACK_ID = "mijn-fotos"
    }
}
