package nl.kleuren.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import nl.kleuren.format.Plate
import nl.kleuren.format.PlateCodec
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
 *   myplates/<id>.kplate      de kleurplaat
 *   myplates/<id>.jpg         preview voor de bibliotheek
 *   myplates/index.json       lijst met titels en bestandsnamen
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
     */
    suspend fun save(
        plate: Plate,
        title: String,
        previewJpeg: ByteArray,
    ): UserPlateEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.mkdirs()
            val id = "user-${System.currentTimeMillis()}"
            val plateFile = File(directory, "$id.kplate")
            val previewFile = File(directory, "$id.jpg")
            val entry = UserPlateEntry(
                id = id,
                title = title.ifBlank { "Mijn foto" },
                plateFile = plateFile.name,
                previewFile = previewFile.name,
                regionCount = plate.regionCount,
                paletteSize = plate.palette.size,
                createdAt = System.currentTimeMillis(),
            )

            try {
                PlateCodec.write(plate.withPreviewJpeg(previewJpeg), plateFile)
                previewFile.writeBytes(previewJpeg)
                val index = readIndex()
                writeIndex(UserPlateIndex(plates = index.plates + entry))
                entry
            } catch (e: Exception) {
                // Laat geen half opgeslagen foto achter.
                plateFile.delete()
                previewFile.delete()
                throw e
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = readIndex()
            index.plates.firstOrNull { it.id == id }?.let { entry ->
                File(directory, entry.plateFile).delete()
                File(directory, entry.previewFile).delete()
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
