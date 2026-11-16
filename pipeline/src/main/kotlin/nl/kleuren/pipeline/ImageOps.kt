package nl.kleuren.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Een eenvoudige afbeelding in het geheugen: breedte, hoogte en één ARGB-int
 * per pixel. We gebruiken bewust geen Android Bitmap of AWT BufferedImage,
 * zodat dezelfde code overal draait.
 */
internal class Raster(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    val pixelCount: Int get() = width * height
}

internal object ImageOps {

    /**
     * Verkleint een foto met een "box filter": elk doel-pixel is het gemiddelde
     * van het bijbehorende blok bronpixels. Dat is simpel, snel en voorkomt
     * aliasfouten (rafelige randen) beter dan "pak elke zoveelste pixel".
     *
     * [maxSide] is de maximale lengte van de langste zijde. De verhouding
     * blijft behouden.
     */
    fun downscale(source: Raster, maxSide: Int): Raster {
        val longest = max(source.width, source.height)
        if (longest <= maxSide) return source

        val scale = maxSide.toDouble() / longest
        val targetWidth = max(2, (source.width * scale).toInt())
        val targetHeight = max(2, (source.height * scale).toInt())
        val output = IntArray(targetWidth * targetHeight)

        for (ty in 0 until targetHeight) {
            // Bronrijen die bij deze doelrij horen. Door integer-deling
            // overlappen de blokken elkaar niet en samen dekken ze alles.
            val y0 = ty * source.height / targetHeight
            val y1 = max(y0 + 1, (ty + 1) * source.height / targetHeight)

            for (tx in 0 until targetWidth) {
                val x0 = tx * source.width / targetWidth
                val x1 = max(x0 + 1, (tx + 1) * source.width / targetWidth)

                var r = 0L
                var g = 0L
                var b = 0L
                var count = 0
                for (y in y0 until y1) {
                    var index = y * source.width + x0
                    for (x in x0 until x1) {
                        val color = source.pixels[index++]
                        r += ColorMath.red(color)
                        g += ColorMath.green(color)
                        b += ColorMath.blue(color)
                        count++
                    }
                }
                output[ty * targetWidth + tx] = ColorMath.rgb(
                    (r / count).toInt(),
                    (g / count).toInt(),
                    (b / count).toInt(),
                )
            }
        }
        return Raster(targetWidth, targetHeight, output)
    }

    /**
     * Lichte ruisonderdrukking die randen heel laat.
     *
     * Voor elk pixel wordt gekeken naar de 3x3-buren. Alleen buren die qua
     * kleur sterk lijken op het middenpixel tellen mee in het gemiddelde.
     * Daardoor verdwijnen fotoruïs en JPEG-artefacten, maar blijven contouren
     * van bergen, bomen en gebouwen scherp.
     *
     * Dit is een vereenvoudigde "bilaterale filter" uit het plan; hij is veel
     * goedkoper en voor het doel (grote rustige kleurvlakken) ruim voldoende.
     */
    fun smoothEdges(source: Raster, threshold: Int = 40): Raster {
        val width = source.width
        val height = source.height
        val output = IntArray(source.pixels.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val center = source.pixels[y * width + x]
                val cr = ColorMath.red(center)
                val cg = ColorMath.green(center)
                val cb = ColorMath.blue(center)

                var r = cr.toLong()
                var g = cg.toLong()
                var b = cb.toLong()
                var count = 1

                val yStart = max(0, y - 1)
                val yEnd = min(height - 1, y + 1)
                val xStart = max(0, x - 1)
                val xEnd = min(width - 1, x + 1)

                for (ny in yStart..yEnd) {
                    var index = ny * width + xStart
                    for (nx in xStart..xEnd) {
                        val neighbor = source.pixels[index++]
                        if (neighbor != center) {
                            val diff = abs(ColorMath.red(neighbor) - cr) +
                                abs(ColorMath.green(neighbor) - cg) +
                                abs(ColorMath.blue(neighbor) - cb)
                            if (diff <= threshold) {
                                r += ColorMath.red(neighbor)
                                g += ColorMath.green(neighbor)
                                b += ColorMath.blue(neighbor)
                                count++
                            }
                        }
                    }
                }
                output[y * width + x] = ColorMath.rgb(
                    (r / count).toInt(),
                    (g / count).toInt(),
                    (b / count).toInt(),
                )
            }
        }
        return Raster(width, height, output)
    }

    /**
     * "Mode filter" op de kleurlabels: elk pixel krijgt de meest voorkomende
     * kleur in zijn 3x3-buurt.
     *
     * Waarom? K-means verdeelt een foto in kleurbanen, maar laat daarbij
     * losse spikkels en kartelige randen achter. Voor een kleurplaat willen we
     * juist rustige, aaneengesloten vlakken. Deze filter strijkt die ruis
     * glad, zonder de grote vormen van de foto aan te tasten.
     *
     * Bij een gelijke stand wint de kleur van het middenpixel, zodat een
     * bestaande rand niet zomaar verschuift.
     */
    fun smoothLabels(
        labels: IntArray,
        width: Int,
        height: Int,
        paletteSize: Int,
    ): IntArray {
        val output = IntArray(labels.size)
        val counts = IntArray(paletteSize)

        for (y in 0 until height) {
            val yStart = max(0, y - 1)
            val yEnd = min(height - 1, y + 1)
            for (x in 0 until width) {
                counts.fill(0)
                val xStart = max(0, x - 1)
                val xEnd = min(width - 1, x + 1)
                for (ny in yStart..yEnd) {
                    var index = ny * width + xStart
                    for (nx in xStart..xEnd) {
                        counts[labels[index++]]++
                    }
                }

                val centerLabel = labels[y * width + x]
                var bestLabel = centerLabel
                var bestCount = counts[centerLabel]
                for (label in counts.indices) {
                    if (counts[label] > bestCount) {
                        bestCount = counts[label]
                        bestLabel = label
                    }
                }
                output[y * width + x] = bestLabel
            }
        }
        return output
    }
}
