package nl.kleuren.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Bewaart welke vlakken de gebruiker al heeft ingekleurd.
 *
 * Waarom geen Room? Voortgang is per plaat een klein setje getallen (hooguit
 * 800 regio-ids). Een binair bestandje per plaat is dan simpeler, sneller en
 * heeft geen database of code-generatie nodig. De opslag is wel zo ontworpen
 * dat hij later zonder wijzigingen aan de UI door Room vervangen kan worden:
 * de rest van de app ziet alleen [progress] en [lastOpened].
 *
 * Bestandsformaat (progress/<plaat-id>.bin):
 * ```
 *   4 bytes  magie "KPRG"
 *   1 byte   versie (1)
 *   4 bytes  aantal ids
 *   4 bytes  per id (gesorteerd)
 * ```
 */
class ProgressRepository(context: Context) {

    private val directory = File(context.filesDir, "progress")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val operations = Channel<ProgressOperation>(Channel.UNLIMITED)

    private val _progress = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
    val progress: StateFlow<Map<String, Set<Int>>> = _progress.asStateFlow()

    private val _lastOpened = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastOpened: StateFlow<Map<String, Long>> = _lastOpened.asStateFlow()

    init {
        // Eerst alle bestaande voortgang inlezen...
        scope.launch {
            loadAll()
            ready.complete(Unit)
        }
        // ...en daarna de schrijfopdrachten één voor één afhandelen. Zo kan een
        // snelle reeks tikken nooit elkaars bestand overschrijven.
        scope.launch {
            ready.await()
            for (operation in operations) {
                writeMutex.withLock {
                    when (operation) {
                        is ProgressOperation.Save -> writeFile(operation.id, operation.filled)
                        is ProgressOperation.Delete -> deleteFile(operation.id)
                        is ProgressOperation.Clear -> clearFiles()
                    }
                }
                operation.acknowledge?.complete(Unit)
            }
        }
    }

    /** Wacht tot de bestaande voortgang is ingelezen. */
    suspend fun awaitReady() {
        ready.await()
    }

    suspend fun filledRegions(plateId: String): Set<Int> {
        ready.await()
        return _progress.value[plateId].orEmpty()
    }

    /**
     * Werkt de voortgang direct bij in het geheugen (zodat de UI meteen
     * reageert) en zet een schrijfopdracht in de wachtrij.
     */
    fun update(plateId: String, filled: Set<Int>, touch: Boolean = true) {
        val normalized = filled.filterTo(LinkedHashSet()) { it > 0 }
        _progress.update { it + (plateId to normalized) }
        if (touch) {
            _lastOpened.update { it + (plateId to System.currentTimeMillis()) }
        }
        operations.trySend(ProgressOperation.Save(plateId, normalized))
    }

    /** Markeert een plaat als "laatst geopend" zonder de vulling te wijzigen. */
    fun markOpened(plateId: String) {
        _lastOpened.update { it + (plateId to System.currentTimeMillis()) }
        _progress.value[plateId]?.let { operations.trySend(ProgressOperation.Save(plateId, it)) }
    }

    suspend fun delete(plateId: String) {
        ready.await()
        val ack = CompletableDeferred<Unit>()
        operations.send(ProgressOperation.Delete(plateId, ack))
        ack.await()
        _progress.update { it - plateId }
        _lastOpened.update { it - plateId }
    }

    suspend fun clearAll() {
        ready.await()
        val ack = CompletableDeferred<Unit>()
        operations.send(ProgressOperation.Clear(ack))
        ack.await()
        _progress.value = emptyMap()
        _lastOpened.value = emptyMap()
    }

    // -----------------------------------------------------------------------
    // Bestands-IO (alleen vanaf de IO-dispatcher)
    // -----------------------------------------------------------------------

    private fun loadAll() {
        directory.mkdirs()
        val loaded = HashMap<String, Set<Int>>()
        val opened = HashMap<String, Long>()
        for (file in directory.listFiles().orEmpty()) {
            if (!file.name.endsWith(".bin")) continue
            val id = file.name.removeSuffix(".bin")
            val filled = runCatching { readFile(file) }.getOrNull()
            if (filled == null) {
                Log.w(TAG, "Voortgangsbestand overgeslagen (beschadigd): ${file.name}")
                continue
            }
            loaded[id] = filled
            opened[id] = file.lastModified()
        }
        _progress.value = loaded
        _lastOpened.value = opened
    }

    private fun readFile(file: File): Set<Int> {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC) throw IllegalStateException("Geen voortgangsbestand")
            val version = input.readUnsignedByte()
            if (version != VERSION) throw IllegalStateException("Onbekende versie $version")
            val count = input.readInt()
            if (count < 0 || count > 100_000) throw IllegalStateException("Ongeldig aantal ids")
            return buildSet(count) {
                repeat(count) {
                    val id = input.readInt()
                    if (id > 0) add(id)
                }
            }
        }
    }

    private fun writeFile(plateId: String, filled: Set<Int>) {
        directory.mkdirs()
        val target = fileFor(plateId)
        val temp = File(directory, target.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(VERSION)
                output.writeInt(filled.size)
                for (id in filled.sorted()) output.writeInt(id)
            }
            if (!temp.renameTo(target)) {
                target.writeBytes(temp.readBytes())
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Kon voortgang niet opslaan voor $plateId", e)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun deleteFile(plateId: String) {
        fileFor(plateId).delete()
    }

    private fun clearFiles() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(plateId: String): File {
        // Plaat-ids komen uit ons eigen manifest, maar we sanitizen ze toch:
        // nooit een bestandspad laten bepalen door externe data.
        val safe = plateId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(directory, "$safe.bin")
    }

    private sealed interface ProgressOperation {
        val acknowledge: CompletableDeferred<Unit>?

        data class Save(val id: String, val filled: Set<Int>) : ProgressOperation {
            override val acknowledge: CompletableDeferred<Unit>? = null
        }

        data class Delete(val id: String, override val acknowledge: CompletableDeferred<Unit>) : ProgressOperation
        data class Clear(override val acknowledge: CompletableDeferred<Unit>) : ProgressOperation
    }

    private companion object {
        const val TAG = "ProgressRepository"
        const val MAGIC = 0x4B505247 // "KPRG"
        const val VERSION = 1
    }
}
