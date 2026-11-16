package nl.kleuren.pipeline

import nl.kleuren.format.Plate
import nl.kleuren.format.PlateRegion
import kotlin.math.max
import kotlin.math.min

/**
 * De volledige pijplijn: foto (ARGB-pixels) -> [Plate].
 *
 * Dezelfde code wordt gebruikt door:
 *  - de generator-CLI voor de meegeleverde landschappen;
 *  - de Android-app voor "eigen foto toevoegen".
 *
 * De pipeline is synchroon en heeft geen Android-afhankelijkheden. De app
 * roept hem aan vanuit een coroutine op Dispatchers.Default. Via de
 * [onProgress]-callback kan de UI voortgang tonen en annuleren.
 */
object PhotoToPlate {

    /**
     * @param width breedte van de bronfoto
     * @param height hoogte van de bronfoto
     * @param argb pixels, rij voor rij (0xAARRGGBB)
     * @param options moeilijkheidsgraad en detail
     * @param onProgress voortgang + annulering; geef `false` terug om te stoppen
     */
    fun convert(
        width: Int,
        height: Int,
        argb: IntArray,
        options: ConvertOptions = ConvertOptions(),
        onProgress: (ConversionProgress) -> Boolean = { true },
    ): Plate {
        require(width > 0 && height > 0) { "Foto heeft ongeldige afmetingen" }
        require(argb.size == width * height) {
            "Verwacht ${width * height} pixels, kreeg ${argb.size}"
        }

        fun report(stage: ConversionStage, fraction: Float, message: String) {
            if (!onProgress(ConversionProgress(stage, fraction.coerceIn(0f, 1f), message))) {
                throw ConversionCancelledException()
            }
        }

        report(ConversionStage.PREPARING, 0.01f, "Foto voorbereiden…")

        // 1. Verkleinen. Een foto van 12 megapixel heeft geen 12 miljoen
        //    vlakken nodig; 1200 px langs de lange zijde geeft al een
        //    haarscherpe plaat op een telefoonscherm.
        var raster = Raster(width, height, argb)
        raster = ImageOps.downscale(raster, options.maxSide)
        report(ConversionStage.PREPARING, 0.06f, "Foto verkleinen…")

        // 2. Ruis dempen, randen behouden.
        raster = ImageOps.smoothEdges(raster)
        report(ConversionStage.SMOOTHING, 0.10f, "Ruis dempen…")

        // 3. Kwantiseren + segmenteren. Soms valt het aantal vlakken buiten de
        //    gewenste bandbreedte. Dan proberen we het één keer opnieuw met
        //    iets meer of minder kleuren (het "fallback"-pad uit het plan).
        var bestQuantized: QuantizedImage? = null
        var bestExtraction: ExtractionResult? = null
        var bestDistance = Int.MAX_VALUE
        var colors = options.colorCount
        val attempts = max(1, options.maxColorAttempts)

        for (attempt in 0 until attempts) {
            val windowStart = 0.10f + 0.80f * attempt / attempts
            val windowSize = 0.80f / attempts

            val quantized = KMeans.quantize(
                raster = raster,
                requestedColors = colors,
                randomSeed = options.randomSeed + attempt * 7919L,
                onIteration = { fraction ->
                    onProgress(
                        ConversionProgress(
                            ConversionStage.QUANTIZING,
                            windowStart + windowSize * 0.40f * fraction,
                            "Kleuren kiezen…",
                        ),
                    )
                },
            )

            report(
                ConversionStage.SEGMENTING,
                windowStart + windowSize * 0.40f,
                "Vlakken gladstrijken…",
            )
            val smoothedLabels = ImageOps.smoothLabels(
                labels = quantized.labels,
                width = quantized.width,
                height = quantized.height,
                paletteSize = quantized.palette.size,
            )
            val cleaned = compactPalette(quantized, smoothedLabels)

            val extraction = RegionExtractor.extract(
                quantized = cleaned,
                options = options,
                onProgress = { fraction ->
                    onProgress(
                        ConversionProgress(
                            ConversionStage.TRACING,
                            windowStart + windowSize * (0.45f + 0.50f * fraction),
                            "Vlakken en contouren maken…",
                        ),
                    )
                },
            )

            val distance = distanceToBand(
                extraction.regions.size,
                options.targetMinRegions,
                options.targetMaxRegions,
            )
            if (distance == 0) {
                bestQuantized = cleaned
                bestExtraction = extraction
                break
            }
            if (distance < bestDistance) {
                bestDistance = distance
                bestQuantized = cleaned
                bestExtraction = extraction
            }

            // Te weinig vlakken? Meer kleuren geeft meer detail.
            // Te veel vlakken? Minder kleuren (de extractor capt ook al op 800).
            colors = if (extraction.regions.size < options.targetMinRegions) {
                min(20, colors + 3)
            } else {
                max(6, colors - 2)
            }
        }

        val quantized = bestQuantized ?: error("Geen enkel kwantisatie-resultaat")
        val extraction = bestExtraction ?: error("Geen enkel segmentatie-resultaat")
        report(ConversionStage.BUILDING, 0.94f, "Plaat opbouwen…")

        // 4. Omzetten naar het uiteindelijke plaatformaat.
        val regions = extraction.regions.map { region ->
            PlateRegion(
                id = region.id,
                colorIndex = region.colorIndex,
                points = region.points,
                centroidX = region.centroidX,
                centroidY = region.centroidY,
                area = region.area,
            )
        }

        val plate = Plate(
            width = extraction.width,
            height = extraction.height,
            palette = quantized.palette,
            regions = regions,
            idMap = extraction.idMap,
            // De preview-JPEG wordt door de aanroeper toegevoegd: de generator
            // doet dat met ImageIO, de app met Bitmap.compress. Zo blijft deze
            // module vrij van platform-specifieke code.
            previewJpeg = ByteArray(0),
        )

        report(ConversionStage.DONE, 1f, "Klaar")
        return plate
    }

    private fun distanceToBand(value: Int, minimum: Int, maximum: Int): Int = when {
        value < minimum -> minimum - value
        value > maximum -> value - maximum
        else -> 0
    }

    /**
     * Verwijdert paletkleuren die na het gladstrijken geen enkel pixel meer
     * hebben. Anders zou de app een kleur tonen met "0 vlakken", wat verwarrend
     * is. De kleurvolgorde (donker -> licht, veel -> weinig) blijft behouden.
     */
    private fun compactPalette(quantized: QuantizedImage, labels: IntArray): QuantizedImage {
        val used = BooleanArray(quantized.palette.size)
        for (label in labels) used[label] = true
        if (used.all { it }) {
            return QuantizedImage(quantized.width, quantized.height, labels, quantized.palette)
        }

        val remap = IntArray(quantized.palette.size) { -1 }
        val newPalette = ArrayList<Int>(quantized.palette.size)
        for (index in quantized.palette.indices) {
            if (used[index]) {
                remap[index] = newPalette.size
                newPalette.add(quantized.palette[index])
            }
        }
        val newLabels = IntArray(labels.size) { remap[labels[it]] }
        return QuantizedImage(
            width = quantized.width,
            height = quantized.height,
            labels = newLabels,
            palette = newPalette.toIntArray(),
        )
    }

    /** Alleen voor tests/QA: handige samenvatting van een conversie. */
    fun describe(plate: Plate): String =
        "${plate.width}x${plate.height}, ${plate.regions.size} vlakken, " +
            "${plate.palette.size} kleuren, gemiddelde oppervlakte " +
            "${plate.regions.map { it.area }.average().toInt()} px"
}
