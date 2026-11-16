package nl.kleuren.app.ui.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.kleuren.app.data.AppContainer
import nl.kleuren.app.data.SettingsRepository
import nl.kleuren.app.data.UserPlateGenerator
import nl.kleuren.app.ui.components.stageLabel
import nl.kleuren.pipeline.ConversionCancelledException
import nl.kleuren.pipeline.Difficulty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PhotoImportUiState(
    val imageUri: Uri? = null,
    val bitmap: Bitmap? = null,
    val loadingImage: Boolean = false,
    val title: String = "Mijn foto",
    val difficulty: Difficulty = Difficulty.DEFAULT,
    val generating: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "",
    val error: String? = null,
    val generatedPlateId: String? = null,
) {
    val readyToGenerate: Boolean
        get() = bitmap != null && !generating && !loadingImage
}

/**
 * Verwerkt een gekozen foto tot een kleurplaat.
 *
 * De zware berekeningen draaien op [Dispatchers.Default], nooit op de
 * main-thread. De voortgang en annulering lopen via een simpele callback; de
 * pipeline zelf hoeft daardoor niets van Android of coroutines te weten.
 */
class PhotoImportViewModel(
    private val context: Context,
    private val userPlateGenerator: UserPlateGenerator,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        // De instellingen bepalen de beginwaarde; per foto mag de gebruiker
        // daarvan afwijken zonder de standaard te veranderen.
        PhotoImportUiState(difficulty = settingsRepository.settings.value.difficulty),
    )
    val state: StateFlow<PhotoImportUiState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    /** Leest de gekozen foto in, verkleind naar maximaal 2048 px. */
    fun selectImage(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    imageUri = uri,
                    loadingImage = true,
                    error = null,
                    generatedPlateId = null,
                )
            }
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeBitmap(uri) }
                val datedTitle = "Mijn foto " + SimpleDateFormat(
                    "d MMM HH:mm",
                    Locale.getDefault(),
                ).format(Date())
                _state.update {
                    it.copy(
                        bitmap = bitmap,
                        loadingImage = false,
                        // Alleen een automatische titel als de gebruiker er
                        // nog geen eigen titel aan heeft gegeven.
                        title = if (it.title.isBlank() || it.title == "Mijn foto") {
                            datedTitle
                        } else {
                            it.title
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loadingImage = false,
                        error = "De foto kon niet worden gelezen. Probeer een andere foto.",
                    )
                }
            }
        }
    }

    fun setTitle(title: String) {
        _state.update { it.copy(title = title) }
    }

    fun setDifficulty(difficulty: Difficulty) {
        _state.update { it.copy(difficulty = difficulty) }
    }

    fun generate() {
        val current = _state.value
        val bitmap = current.bitmap ?: return
        if (current.generating) return
        cancelRequested = false

        viewModelScope.launch {
            val job = this
            _state.update {
                it.copy(
                    generating = true,
                    progress = 0f,
                    stage = "Voorbereiden…",
                    error = null,
                    generatedPlateId = null,
                )
            }

            try {
                val entry = userPlateGenerator.create(
                    bitmap = bitmap,
                    title = current.title,
                    difficulty = current.difficulty,
                ) { progress ->
                    _state.update {
                        it.copy(
                            generating = true,
                            progress = progress.fraction,
                            stage = progress.stage.stageLabel(),
                        )
                    }
                    !cancelRequested && job.isActive
                }

                _state.update {
                    it.copy(
                        generating = false,
                        progress = 1f,
                        stage = "Klaar",
                        generatedPlateId = entry.id,
                    )
                }
            } catch (cancelled: ConversionCancelledException) {
                _state.update {
                    it.copy(generating = false, progress = 0f, stage = "")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        generating = false,
                        progress = 0f,
                        stage = "",
                        error = "Er ging iets mis bij het maken van de kleurplaat: ${e.message}",
                    )
                }
            }
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    /** Verwijdert het gekozen beeld uit het geheugen (bijv. bij "andere foto"). */
    fun clearImage() {
        _state.update {
            PhotoImportUiState(title = "Mijn foto", difficulty = it.difficulty)
        }
    }

    // -----------------------------------------------------------------------
    // Foto decoderen
    // -----------------------------------------------------------------------

    private fun decodeBitmap(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val decoded = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder past EXIF-rotatie automatisch toe (belangrijk voor
            // foto's die liggend zijn opgeslagen) en kan direct verkleinen.
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                val scale = min(1f, 2048f / max(size.width, size.height))
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (size.width * scale).roundToInt().coerceAtLeast(1),
                        (size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                // SOFTWARE zorgt dat getPixels() altijd werkt (geen GPU-bitmap).
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            // Fallback voor Android 8 en 9.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / sampleSize > 2048) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("Kon de afbeelding niet decoderen")
        }

        return if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PhotoImportViewModel(
                    context = container.appContext,
                    userPlateGenerator = container.userPlateGenerator,
                    settingsRepository = container.settingsRepository,
                )
            }
        }
    }
}
