package nl.kleuren.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.kleuren.format.Plate
import nl.kleuren.format.PlateCodec
import nl.kleuren.pipeline.Difficulty
import java.io.File

/**
 * Index van landschappen die de gebruiker op dit toestel opnieuw heeft
 * gegenereerd met een andere moeilijkheidsgraad.
 *
 * De originele platen blijven onaangeroerd in de APK-assets. Per landschap
 * bewaren we maximaal één variant; "Herstel origineel" verwijdert die weer.
 */
@Serializable
data class LandscapeVariantIndex(
    val version: Int = 1,
    val variants: List<LandscapeVariantEntry> = emptyList(),
)

@Serializable
data class LandscapeVariantEntry(
    val plateId: String,
    val plateFile: String,
    val regionCount: Int,
    val paletteSize: Int,
    val difficulty: String,
    val updatedAt: Long,
)

/**
 * Opslag voor aangepaste landschapsplaten.
 *
 * Mapopbouw:
 * ```
 *   landscape-variants/<plateId>.kplate
 *   landscape-variants/index.json
 * ```
 */
class LandscapeVariantStore(context: Context) {

    private val directory = File(context.filesDir, "landscape-variants")
    private val indexFile = File(directory, "index.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun entries(): Map<String, LandscapeVariantEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndex().variants.associateBy { it.plateId } }
    }

    /** Bewaart een nieuwe variant en vervangt een eventuele eerdere variant. */
    suspend fun save(
        plateId: String,
        plate: Plate,
        difficulty: Difficulty,
    ): LandscapeVariantEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.mkdirs()
            val file = File(directory, "$plateId.kplate")
            val entry = LandscapeVariantEntry(
                plateId = plateId,
                plateFile = file.name,
                regionCount = plate.regionCount,
                paletteSize = plate.palette.size,
                difficulty = difficulty.name,
                updatedAt = System.currentTimeMillis(),
            )
            PlateCodec.write(plate, file)
            val index = readIndex()
            writeIndex(
                LandscapeVariantIndex(
                    variants = index.variants.filterNot { it.plateId == plateId } + entry,
                ),
            )
            entry
        }
    }

    suspend fun delete(plateId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = readIndex()
            index.variants.firstOrNull { it.plateId == plateId }?.let {
                File(directory, it.plateFile).delete()
            }
            writeIndex(
                LandscapeVariantIndex(
                    variants = index.variants.filterNot { it.plateId == plateId },
                ),
            )
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.forEach { it.delete() }
            directory.mkdirs()
            writeIndex(LandscapeVariantIndex())
        }
    }

    fun file(entry: LandscapeVariantEntry): File = File(directory, entry.plateFile)

    private fun readIndex(): LandscapeVariantIndex {
        if (!indexFile.isFile) return LandscapeVariantIndex()
        return runCatching {
            json.decodeFromString<LandscapeVariantIndex>(indexFile.readText())
        }.getOrDefault(LandscapeVariantIndex())
    }

    private fun writeIndex(index: LandscapeVariantIndex) {
        directory.mkdirs()
        val temp = File(directory, "index.json.tmp")
        temp.writeText(json.encodeToString(LandscapeVariantIndex.serializer(), index))
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(temp.readText())
            temp.delete()
        }
    }
}
