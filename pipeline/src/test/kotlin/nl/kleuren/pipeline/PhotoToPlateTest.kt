package nl.kleuren.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests voor de fotopijplijn. Ze gebruiken een zelfgemaakte "landschap"-foto,
 * zodat de tests snel en volledig deterministisch zijn.
 */
class PhotoToPlateTest {

    private fun landscapeWidth(): Int = 320
    private fun landscapeHeight(): Int = 240

    private fun syntheticLandscape(): IntArray {
        val width = landscapeWidth()
        val height = landscapeHeight()
        val pixels = IntArray(width * height)

        fun color(r: Int, g: Int, b: Int) = ColorMath.rgb(r, g, b)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val horizon = height * 0.55
                val sunX = width * 0.72
                val sunY = height * 0.28
                val sunRadius = height * 0.09
                val distanceToSun = Math.hypot(x - sunX, y - sunY)

                pixels[index] = when {
                    distanceToSun < sunRadius -> color(250, 214, 128)
                    y < horizon -> {
                        // Rustige lucht met een lichte gradient.
                        val t = y / horizon
                        color(
                            (120 + 80 * (1 - t)).toInt(),
                            (165 + 60 * (1 - t)).toInt(),
                            (205 + 40 * (1 - t)).toInt(),
                        )
                    }
                    // Twee bergtoppen (een driehoek per top).
                    y > horizon - abs(x - width * 0.30) * 0.55 && y < horizon + 30 ->
                        color(94, 108, 122)
                    y > horizon - abs(x - width * 0.68) * 0.40 && y < horizon + 30 ->
                        color(72, 86, 96)
                    // Groene voorgrond met een paar donkere "bomen".
                    y > horizon + 20 && (x / 17 + y / 23) % 7 == 0 -> color(34, 74, 48)
                    else -> color(74, 118, 66)
                }
            }
        }
        return pixels
    }

    private fun options(colors: Int = 8) = ConvertOptions(
        maxSide = 300,
        colorCount = colors,
        targetMinRegions = 5,
        targetMaxRegions = 120,
        maxColorAttempts = 2,
        randomSeed = 12345L,
    )

    @Test
    fun `landschap wordt een geldige kleurplaat`() {
        val plate = PhotoToPlate.convert(
            landscapeWidth(),
            landscapeHeight(),
            syntheticLandscape(),
            options(),
        )

        assertEquals(300, plate.width)
        assertEquals(225, plate.height)
        assertTrue("Palet te groot: ${plate.palette.size}", plate.palette.size in 1..8)
        assertTrue("Te weinig regio's: ${plate.regions.size}", plate.regions.size >= 3)
        assertTrue("Te veel regio's: ${plate.regions.size}", plate.regions.size <= 120)

        // Elk pixel moet bij precies één regio horen.
        assertTrue(plate.idMap.none { it.toInt() == 0 })

        // De oppervlaktes samen zijn de hele plaat.
        assertEquals(plate.canvasArea, plate.regions.sumOf { it.area })

        for (region in plate.regions) {
            assertTrue("Regio ${region.id} heeft te weinig punten", region.pointCount >= 3)
            assertTrue(region.points.all { it in 0..65535 })
            assertTrue(region.colorIndex in plate.palette.indices)
            assertTrue(region.centroidX in 0..plate.width)
            assertTrue(region.centroidY in 0..plate.height)
        }
    }

    @Test
    fun `zelfde invoer geeft dezelfde plaat`() {
        val first = PhotoToPlate.convert(
            landscapeWidth(), landscapeHeight(), syntheticLandscape(), options(),
        )
        val second = PhotoToPlate.convert(
            landscapeWidth(), landscapeHeight(), syntheticLandscape(), options(),
        )

        assertEquals(first.palette.toList(), second.palette.toList())
        assertEquals(first.regions.size, second.regions.size)
        first.regions.zip(second.regions).forEach { (a, b) ->
            assertEquals(a.points.toList(), b.points.toList())
            assertEquals(a.colorIndex, b.colorIndex)
        }
    }

    @Test
    fun `grote foto wordt verkleind met behoud van verhouding`() {
        val width = 800
        val height = 400
        val pixels = IntArray(width * height) { ColorMath.rgb(20, 100, 180) }

        val plate = PhotoToPlate.convert(
            width,
            height,
            pixels,
            options().copy(maxSide = 200, colorCount = 4),
        )
        assertEquals(200, plate.width)
        assertEquals(100, plate.height)
    }

    @Test
    fun `piepkleine stipjes worden samengevoegd`() {
        val width = 200
        val height = 200
        val pixels = IntArray(width * height) { ColorMath.rgb(240, 240, 235) }
        // Een raster van 2x2-zwarte stipjes: die mogen geen eigen inkleurvlak
        // worden, want dat is niet te raken met een vinger.
        for (y in 10 until height step 20) {
            for (x in 10 until width step 20) {
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        pixels[(y + dy) * width + x + dx] = ColorMath.rgb(20, 20, 20)
                    }
                }
            }
        }

        val plate = PhotoToPlate.convert(
            width,
            height,
            pixels,
            ConvertOptions(
                maxSide = 200,
                colorCount = 4,
                minRegionFraction = 0.001f, // 0,1% = 40 pixels
                targetMinRegions = 1,
                targetMaxRegions = 10,
                maxColorAttempts = 1,
            ),
        )

        assertEquals(1, plate.regions.size)
        assertEquals(plate.canvasArea, plate.regions.single().area)
    }

    @Test(expected = ConversionCancelledException::class)
    fun `annuleren stopt de conversie`() {
        PhotoToPlate.convert(
            landscapeWidth(),
            landscapeHeight(),
            syntheticLandscape(),
            options(),
        ) { progress ->
            progress.stage != ConversionStage.SMOOTHING
        }
    }

    @Test
    fun `kleurverschil tussen paletkleuren is meetbaar`() {
        val plate = PhotoToPlate.convert(
            landscapeWidth(),
            landscapeHeight(),
            syntheticLandscape(),
            options(colors = 12),
        )
        val labs = plate.palette.map { ColorMath.toLab(it) }
        for (i in labs.indices) {
            for (j in i + 1 until labs.size) {
                val delta = ColorMath.deltaE(labs[i], labs[j])
                assertTrue("Kleuren $i en $j lijken te veel op elkaar (dE=$delta)", delta > 5.0)
            }
        }
    }

    @Test
    fun `omtrekvereenvoudiging houdt een cirkel overeind`() {
        // Een cirkel met 720 trapjes moet na vereenvoudiging een compacte,
        // herkenbare vorm blijven (geen 720 punten, wel genoeg voor een boog).
        val points = ArrayList<Int>()
        val radius = 80.0
        val steps = 720
        for (i in 0 until steps) {
            val angle = i * 2.0 * Math.PI / steps
            points += (100 + radius * kotlin.math.cos(angle)).toInt()
            points += (100 + radius * kotlin.math.sin(angle)).toInt()
        }
        val simplified = ContourSimplifier.simplifyClosed(points.toIntArray(), 1.5f)
        assertTrue("Te veel punten: ${simplified.size / 2}", simplified.size / 2 <= 24)
        assertTrue("Te weinig punten: ${simplified.size / 2}", simplified.size / 2 >= 8)
    }
}
