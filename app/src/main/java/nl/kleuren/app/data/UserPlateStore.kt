package nl.kleuren.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import nl.kleuren.format.Plate
import nl.kleuren.format.PlateCodec
import nl.kleuren.pipeline.Difficulty
import java.io.File

/**
 * Opslag voor kleurplaten die de gebruiker zelf heeft gemaakt.
 *
 * Elke foto wordt een gewoon .kplate-bestand, precies hetzelfde formaat als de
 * meegeleverde landschappen. Daardoor werkt het kleurbord zonder enige
 * uitzondering voor "Mijn foto's".
 *
 * Mapopbouw (in de interne opslag van de app):
 * ```
 *   myplates/<id>.kplate         de kleurplaat
 *   myplates/<id>.jpg            preview voor de bibliotheek
 *   myplates/<id>-source.jpg     bronfoto, voor opnieuw genereren
 *   myplates/index.json          lijst met titels en bestandsnamen
 * ```
 */
class UserPlateStore(context: Context) {

    private val directory = File(context.filesDir, "myplates")
    private val indexFile = File(directory, "index.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun entries(): List<UserPlateEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndex().plates.sortedByDescending { it.createdAt } }
    }

    /**
     * Slaat een gegenereerde plaat op en geeft de index-entry terug.
     * [previewJpeg] is de verkleinde foto voor de bibliotheekkaart.
     * [sourceJpeg] is de grotere bronfoto, zodat de gebruiker later een andere
     * moeilijkheid kan kiezen zonder de oorspronkelijke foto opnieuw te kiezen.
     */
    suspend fun save(
        plate: Plate,
        title: String,
        previewJpeg: ByteArray,
        sourceJpeg: ByteArray,
        difficulty: Difficulty,
    ): UserPlateEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.mkdirs()
            val id = "user-${System.currentTimeMillis()}"
            val plateFile = File(directory, "$id.kplate")
            val previewFile = File(directory, "$id.jpg")
            val sourceFile = File(directory, "$id-source.jpg")
            val entry = UserPlateEntry(
                id = id,
                title = title.ifBlank { "Mijn foto" },
                plateFile = plateFile.name,
                previewFile = previewFile.name,
                regionCount = plate.regionCount,
                paletteSize = plate.palette.size,
                createdAt = System.currentTimeMillis(),
                sourceFile = sourceFile.name,
                difficulty = difficulty.name,
            )

            try {
                PlateCodec.write(plate.withPreviewJpeg(previewJpeg), plateFile)
                previewFile.writeBytes(previewJpeg)
                sourceFile.writeBytes(sourceJpeg)
                val index = readIndex()
                writeIndex(UserPlateIndex(plates = index.plates + entry))
                entry
            } catch (e: Exception) {
                // Laat geen half opgeslagen foto achter.
                plateFile.delete()
                previewFile.delete()
                sourceFile.delete()
                throw e
            }
        }
    }

    /**
     * Vervangt de plaat van een bestaande foto nadat die opnieuw is
     * gegenereerd. Titel, aanmaakdatum en preview blijven behouden.
     */
    suspend fun replace(
        id: String,
        plate: Plate,
        difficulty: Difficulty,
    ): UserPlateEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = readIndex()
            val existing = index.plates.firstOrNull { it.id == id } ?: return@withLock null
            val plateFile = File(directory, existing.plateFile)
            val previewFile = File(directory, existing.previewFile)
            val previewBytes = if (previewFile.isFile) previewFile.readBytes() else ByteArray(0)
            val updated = existing.copy(
                regionCount = plate.regionCount,
                paletteSize = plate.palette.size,
                difficulty = difficulty.name,
            )

            PlateCodec.write(plate.withPreviewJpeg(previewBytes), plateFile)
            writeIndex(
                UserPlateIndex(
                    plates = index.plates.map { if (it.id == id) updated else it },
                ),
            )
            updated
        }
    }

    /**
     * Geeft de bronfoto van een eigen plaat terug. Voor foto's van vóór de
     * moeilijkheidsgraad-functie is er geen bronbestand; dan gebruiken we de
     * bibliotheekpreview, zodat ook die foto's opnieuw gemaakt kunnen worden.
     */
    suspend fun sourceBytes(entry: UserPlateEntry): ByteArray? = withContext(Dispatchers.IO) {
        val source = entry.sourceFile
            .takeIf { it.isNotBlank() }
            ?.let { File(directory, it) }
        val file = source?.takeIf { it.isFile } ?: File(directory, entry.previewFile)
        if (file.isFile) file.readBytes() else null
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = readIndex()
            index.plates.firstOrNull { it.id == id }?.let { entry ->
                File(directory, entry.plateFile).delete()
                File(directory, entry.previewFile).delete()
                if (entry.sourceFile.isNotBlank()) {
                    File(directory, entry.sourceFile).delete()
                }
            }
            writeIndex(UserPlateIndex(plates = index.plates.filterNot { it.id == id }))
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.forEach { it.delete() }
            directory.mkdirs()
            writeIndex(UserPlateIndex())
        }
    }

    private fun readIndex(): UserPlateIndex {
        if (!indexFile.isFile) return UserPlateIndex()
        return runCatching {
            json.decodeFromString<UserPlateIndex>(indexFile.readText())
        }.getOrDefault(UserPlateIndex())
    }

    private fun writeIndex(index: UserPlateIndex) {
        directory.mkdirs()
        val temp = File(directory, "index.json.tmp")
        temp.writeText(json.encodeToString(UserPlateIndex.serializer(), index))
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(temp.readText())
            temp.delete()
        }
    }
}
