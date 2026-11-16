package nl.kleuren.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.kleuren.format.Plate
import nl.kleuren.pipeline.ConversionProgress
import nl.kleuren.pipeline.Difficulty
import nl.kleuren.pipeline.PhotoToPlate
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maakt kleurplaten van eigen foto's en kan bestaande platen opnieuw maken
 * met een andere moeilijkheidsgraad.
 *
 * Zowel de importflow als "Moeilijkheid aanpassen" in de bibliotheek gebruiken
 * deze klasse. Voor eigen foto's komt de bron uit de interne opslag; voor
 * meegeleverde landschappen uit een bronfoto in de assets.
 */
class PlateGenerator(
    context: Context,
    private val userPlateStore: UserPlateStore,
    private val landscapeVariantStore: LandscapeVariantStore,
    private val plateRepository: PlateRepository,
) {
    private val appContext = context.applicationContext

    /**
     * Genereert een nieuwe eigen plaat en bewaart de bronfoto. De bitmap blijft
     * eigendom van de aanroeper; wij recyclen hem niet.
     */
    suspend fun create(
        bitmap: Bitmap,
        title: String,
        difficulty: Difficulty,
        onProgress: (ConversionProgress) -> Boolean = { true },
    ): UserPlateEntry {
        val plate = withContext(Dispatchers.Default) {
            generate(bitmap, difficulty, onProgress)
        }
        val preview = withContext(Dispatchers.IO) {
            encodeJpeg(bitmap, PREVIEW_MAX_SIDE, PREVIEW_QUALITY)
        }
        val source = withContext(Dispatchers.IO) {
            encodeJpeg(bitmap, SOURCE_MAX_SIDE, SOURCE_QUALITY)
        }

        val entry = userPlateStore.save(
            plate = plate.withPreviewJpeg(preview),
            title = title,
            previewJpeg = preview,
            sourceJpeg = source,
            difficulty = difficulty,
        )
        plateRepository.invalidatePlate(entry.id)
        plateRepository.invalidatePreview(entry.id)
        plateRepository.refresh()
        return entry
    }

    /**
     * Maakt een bestaande eigen foto opnieuw met [difficulty].
     *
     * De titel, aanmaakdatum en bibliotheekpreview blijven behouden. De
     * voortgang wordt door de aanroeper gewist, omdat de regio-ids veranderen.
     */
    suspend fun regenerate(
        entry: UserPlateEntry,
        difficulty: Difficulty,
        onProgress: (ConversionProgress) -> Boolean = { true },
    ): UserPlateEntry {
        val source = userPlateStore.sourceBytes(entry)
            ?: error("De bronfoto van deze kleurplaat is niet gevonden.")
        val bitmap = withContext(Dispatchers.IO) { decodeSource(source) }

        try {
            val plate = withContext(Dispatchers.Default) {
                generate(bitmap, difficulty, onProgress)
            }
            val updated = userPlateStore.replace(entry.id, plate, difficulty)
                ?: error("De kleurplaat is niet meer gevonden.")
            plateRepository.invalidatePlate(entry.id)
            plateRepository.invalidatePreview(entry.id)
            plateRepository.refresh()
            return updated
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Maakt een meegeleverd landschap opnieuw met [difficulty]. De variant
     * wordt lokaal bewaard; de originele asset blijft onaangeroerd.
     */
    suspend fun regenerateLandscape(
        summary: PlateSummary,
        difficulty: Difficulty,
        onProgress: (ConversionProgress) -> Boolean = { true },
    ): LandscapeVariantEntry {
        val sourceAsset = summary.sourceAsset
            ?: error("De bronfoto van dit landschap is niet meegeleverd.")
        val source = withContext(Dispatchers.IO) {
            appContext.assets.open(sourceAsset).use { it.readBytes() }
        }
        val bitmap = withContext(Dispatchers.IO) { decodeSource(source) }

        try {
            val plate = withContext(Dispatchers.Default) {
                generate(bitmap, difficulty, onProgress)
            }
            val entry = landscapeVariantStore.save(summary.id, plate, difficulty)
            plateRepository.invalidatePlate(summary.id)
            plateRepository.refresh()
            return entry
        } finally {
            bitmap.recycle()
        }
    }

    /** Verwijdert de aangepaste variant van een landschap. */
    suspend fun restoreLandscape(plateId: String) {
        landscapeVariantStore.delete(plateId)
        plateRepository.invalidatePlate(plateId)
        plateRepository.refresh()
    }

    private fun generate(
        bitmap: Bitmap,
        difficulty: Difficulty,
        onProgress: (ConversionProgress) -> Boolean,
    ): Plate {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return PhotoToPlate.convert(
            width = width,
            height = height,
            argb = pixels,
            options = difficulty.toConvertOptions(
                maxSide = PIPELINE_MAX_SIDE,
                maxColorAttempts = PIPELINE_ATTEMPTS,
                randomSeed = System.currentTimeMillis(),
            ),
            onProgress = onProgress,
        )
    }

    private fun decodeSource(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "De bronfoto kon niet worden gelezen."
        }

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > DECODE_MAX_SIDE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("De bronfoto kon niet worden gedecodeerd.")
        return if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    /** Schaalt en comprimeert een bitmap naar JPEG. */
    private fun encodeJpeg(bitmap: Bitmap, maxSide: Int, quality: Int): ByteArray {
        val scale = min(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return ByteArrayOutputStream(SOURCE_BYTES_HINT).use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (scaled !== bitmap) scaled.recycle()
            output.toByteArray()
        }
    }

    private companion object {
        const val PIPELINE_MAX_SIDE = 1200
        const val PIPELINE_ATTEMPTS = 2
        const val DECODE_MAX_SIDE = 2048
        const val PREVIEW_MAX_SIDE = 560
        const val PREVIEW_QUALITY = 86
        const val SOURCE_MAX_SIDE = 1600
        const val SOURCE_QUALITY = 90
        const val SOURCE_BYTES_HINT = 512 * 1024
    }
}
