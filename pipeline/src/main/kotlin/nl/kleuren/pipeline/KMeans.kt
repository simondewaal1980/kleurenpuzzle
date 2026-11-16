package nl.kleuren.pipeline

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Resultaat van kleurkwantisatie: elk pixel heeft een kleurindex, en de
 * bijbehorende paletkleur staat in [palette].
 */
internal class QuantizedImage(
    val width: Int,
    val height: Int,
    val labels: IntArray,
    val palette: IntArray,
) {
    val pixelCount: Int get() = labels.size
}

/**
 * K-means kleurkwantisatie.
 *
 * Idee: we kiezen K "middelpunten" in de kleurenruimte. Elk pixel hoort bij het
 * dichtstbijzijnde middel punt. Daarna schuiven we elk middel punt naar het
 * gemiddelde van al zijn pixels, en herhalen dat een paar keer. Het resultaat
 * is een foto die nog maar uit K kleuren bestaat — precies wat een
 * paint-by-numbers-plaat nodig heeft.
 *
 * We doen dit in twee stappen:
 *  1. Op een steekproef van maximaal ~48.000 pixels: snel genoeg voor een
 *     telefoon, nauwkeurig genoeg voor de kleurkeuze.
 *  2. Daarna wordt elk pixel één keer aan het dichtstbijzijnde paletpunt
 *     toegewezen. Dat is de dure stap (1 megapixel x 14 kleuren), maar hij
 *     gebeurt maar één keer.
 */
internal object KMeans {

    fun quantize(
        raster: Raster,
        requestedColors: Int,
        randomSeed: Long,
        onIteration: (fraction: Float) -> Boolean,
    ): QuantizedImage {
        // --- Stap 1: steekproef -------------------------------------------------
        val pixelCount = raster.pixelCount
        val sampleCount = minOf(pixelCount, 48_000)
        val stride = max(1, pixelCount / sampleCount)
        val actualSamples = (pixelCount + stride - 1) / stride
        val samples = IntArray(actualSamples)
        var sampleIndex = 0
        var pixelIndex = 0
        while (sampleIndex < actualSamples) {
            samples[sampleIndex++] = raster.pixels[pixelIndex]
            pixelIndex += stride
        }

        val random = Random(randomSeed)

        // --- Stap 2: k-means++ initialisatie ------------------------------------
        // Kies het eerste centrum willekeurig. Elk volgend centrum wordt bij
        // voorkeur ver van de al gekozen centra gekozen. Dat geeft veel betere
        // resultaten dan volledig willekeurige startpunten.
        val maxColors = minOf(requestedColors, actualSamples)
        val centers = ArrayList<Int>(maxColors)
        centers += samples[random.nextInt(samples.size)]
        val nearestDistance = LongArray(samples.size) { Long.MAX_VALUE }

        while (centers.size < maxColors) {
            var total = 0.0
            for (i in samples.indices) {
                val d = ColorMath.distanceSq(samples[i], centers.last()).toLong()
                if (d < nearestDistance[i]) nearestDistance[i] = d
                total += nearestDistance[i]
            }
            if (total <= 0.0) break // minder unieke kleuren dan gevraagd

            val target = random.nextDouble() * total
            var cumulative = 0.0
            var chosen = -1
            for (i in samples.indices) {
                cumulative += nearestDistance[i]
                if (cumulative >= target) {
                    chosen = i
                    break
                }
            }
            if (chosen < 0) chosen = samples.size - 1
            centers += samples[chosen]
        }

        // --- Stap 3: Lloyd-iteraties op de steekproef ----------------------------
        val sumsR = LongArray(centers.size)
        val sumsG = LongArray(centers.size)
        val sumsB = LongArray(centers.size)
        val counts = IntArray(centers.size)
        val minDistance = LongArray(samples.size)
        val assignment = IntArray(samples.size) { -1 }

        var iteration = 0
        val maxIterations = 18
        while (iteration < maxIterations) {
            sumsR.fill(0)
            sumsG.fill(0)
            sumsB.fill(0)
            counts.fill(0)

            var changed = false
            for (i in samples.indices) {
                val color = samples[i]
                var best = 0
                var bestDistance = Long.MAX_VALUE
                for (c in centers.indices) {
                    val d = ColorMath.distanceSq(color, centers[c]).toLong()
                    if (d < bestDistance) {
                        bestDistance = d
                        best = c
                    }
                }
                if (assignment[i] != best) {
                    changed = true
                    assignment[i] = best
                }
                counts[best]++
                sumsR[best] += ColorMath.red(color)
                sumsG[best] += ColorMath.green(color)
                sumsB[best] += ColorMath.blue(color)
            }

            // Verplaats elk centrum naar het gemiddelde van zijn pixels.
            for (c in centers.indices) {
                if (counts[c] > 0) {
                    centers[c] = ColorMath.rgb(
                        (sumsR[c].toDouble() / counts[c]).roundToInt(),
                        (sumsG[c].toDouble() / counts[c]).roundToInt(),
                        (sumsB[c].toDouble() / counts[c]).roundToInt(),
                    )
                }
            }

            iteration++
            if (!onIteration(iteration.toFloat() / maxIterations)) {
                throw ConversionCancelledException()
            }
            if (!changed) break
        }

        // --- Stap 4: bijna-identieke kleuren samenvoegen -------------------------
        // Twee kleuren die voor het oog vrijwel gelijk zijn, zijn funest voor een
        // kleurplaat: je ziet het verschil niet en pakt steeds de verkeerde kleur.
        // Het plan eist daarom een minimale Delta E tussen paletkleuren.
        var palette = centers.toMutableList()
        val colorCounts = MutableList(palette.size) { 0 }
        for (a in assignment) colorCounts[a]++

        var merged = true
        while (merged && palette.size > 6) {
            merged = false
            val labs = palette.map { ColorMath.toLab(it) }
            var bestI = -1
            var bestJ = -1
            var bestDelta = MIN_PALETTE_DELTA_E
            for (i in palette.indices) {
                for (j in i + 1 until palette.size) {
                    val delta = ColorMath.deltaE(labs[i], labs[j])
                    if (delta < bestDelta) {
                        bestDelta = delta
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI >= 0) {
                // Voeg j samen met i, gewogen naar het aantal pixels.
                val ci = colorCounts[bestI]
                val cj = colorCounts[bestJ]
                val total = max(1, ci + cj)
                palette[bestI] = ColorMath.rgb(
                    (ColorMath.red(palette[bestI]) * ci + ColorMath.red(palette[bestJ]) * cj) / total,
                    (ColorMath.green(palette[bestI]) * ci + ColorMath.green(palette[bestJ]) * cj) / total,
                    (ColorMath.blue(palette[bestI]) * ci + ColorMath.blue(palette[bestJ]) * cj) / total,
                )
                colorCounts[bestI] = total
                palette.removeAt(bestJ)
                colorCounts.removeAt(bestJ)
                merged = true
            }
        }

        // --- Stap 5: alle pixels definitief toewijzen ----------------------------
        val countsFinal = IntArray(palette.size)
        val labels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val color = raster.pixels[i]
            var best = 0
            var bestDistance = Long.MAX_VALUE
            for (c in palette.indices) {
                val d = ColorMath.distanceSq(color, palette[c]).toLong()
                if (d < bestDistance) {
                    bestDistance = d
                    best = c
                }
            }
            labels[i] = best
            countsFinal[best]++
        }

        // Lege kleuren (kan gebeuren na het mergen) verwijderen en kleuren
        // sorteren van veel naar weinig voorkomend. Kleur 1 in de UI is dan
        // meteen de grootste achtergrondkleur, wat vertrouwd aanvoelt.
        val used = palette.indices.filter { countsFinal[it] > 0 }
            .sortedWith(compareByDescending<Int> { countsFinal[it] }.thenBy { it })
        val finalPalette = IntArray(used.size) { palette[used[it]] }
        val remap = IntArray(palette.size)
        for (newIndex in used.indices) remap[used[newIndex]] = newIndex
        for (i in labels.indices) labels[i] = remap[labels[i]]

        return QuantizedImage(raster.width, raster.height, labels, finalPalette)
    }

    /** Minimale Delta E tussen twee paletkleuren. 12 is duidelijk zichtbaar verschillend. */
    private const val MIN_PALETTE_DELTA_E = 7.0
}
