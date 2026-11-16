package nl.kleuren.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Unit tests voor het .kplate-formaat.
 *
 * Draaien met:
 *   ./gradlew :format:test
 *
 * Deze tests hebben géén Android nodig: ze draaien gewoon op de JVM. Dat is
 * precies de reden dat :format een aparte pure-Kotlin-module is.
 */
class PlateCodecTest {

    /** Kleine, geldige testplaat van 4x4 plaat-pixels. */
    private fun samplePlate(): Plate {
        val palette = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFFCCBBAA.toInt())
        val regions = listOf(
            PlateRegion(
                id = 1,
                colorIndex = 0,
                points = intArrayOf(0, 0, 4, 0, 4, 2, 0, 2),
                centroidX = 2,
                centroidY = 1,
                area = 8,
            ),
            PlateRegion(
                id = 2,
                colorIndex = 1,
                points = intArrayOf(0, 2, 2, 2, 2, 4, 0, 4),
                centroidX = 1,
                centroidY = 3,
                area = 4,
            ),
            PlateRegion(
                id = 3,
                colorIndex = 2,
                points = intArrayOf(2, 2, 4, 2, 4, 4, 2, 4),
                centroidX = 3,
                centroidY = 3,
                area = 4,
            ),
        )
        val idMap = ShortArray(16) { index ->
            val x = index % 4
            val y = index / 4
            when {
                y < 2 -> 1
                x < 2 -> 2
                else -> 3
            }.toShort()
        }
        return Plate(
            width = 4,
            height = 4,
            palette = palette,
            regions = regions,
            idMap = idMap,
            previewJpeg = byteArrayOf(1, 2, 3, 4, 5),
        )
    }

    @Test
    fun `encode en decode geven dezelfde plaat terug`() {
        val original = samplePlate()
        val decoded = PlateCodec.decode(PlateCodec.encode(original))

        assertEquals(original.width, decoded.width)
        assertEquals(original.height, decoded.height)
        assertArrayEquals(original.palette, decoded.palette)
        assertArrayEquals(original.idMap, decoded.idMap)
        assertArrayEquals(original.previewJpeg, decoded.previewJpeg)
        assertEquals(original.regions, decoded.regions)
    }

    @Test
    fun `schrijven en lezen via een bestand werkt`() {
        val file = File.createTempFile("test", ".kplate")
        try {
            PlateCodec.write(samplePlate(), file)
            val decoded = PlateCodec.read(file)
            assertEquals(samplePlate().regions, decoded.regions)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `hit-testing gebruikt de id-map`() {
        val plate = samplePlate()
        assertEquals(1, plate.regionIdAt(0, 0))
        assertEquals(2, plate.regionIdAt(0, 3))
        assertEquals(3, plate.regionIdAt(3, 3))
        assertEquals(0, plate.regionIdAt(-1, 0))
        assertEquals(0, plate.regionIdAt(4, 4))
        assertEquals(3, plate.regionIdAtNormalized(0.99f, 0.99f))
    }

    @Test
    fun `voortgang telt alleen bestaande regio's`() {
        val plate = samplePlate()
        assertFalse(plate.isComplete(emptySet()))
        assertEquals(0f, plate.progress(emptySet()), 0.0001f)
        assertEquals(1f, plate.progress(setOf(1, 2, 3, 99)), 0.0001f)
        assertTrue(plate.isComplete(setOf(1, 2, 3, 99)))
    }

    @Test(expected = PlateFormatException::class)
    fun `verkeerde magie wordt geweigerd`() {
        PlateCodec.decode(ByteArray(32) { 7 })
    }

    @Test
    fun `beschadigde data geeft een nette fout`() {
        val bytes = PlateCodec.encode(samplePlate())
        // Gooi de laatste 10 bytes weg: de zlib-checksum zal niet meer kloppen.
        val truncated = bytes.copyOfRange(0, bytes.size - 10)
        try {
            PlateCodec.decode(truncated)
            throw AssertionError("Verwachtte een PlateFormatException")
        } catch (expected: PlateFormatException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `id-map met onbekend id wordt geweigerd`() {
        val broken = Plate(
            width = 2,
            height = 2,
            palette = intArrayOf(0xFF000000.toInt()),
            regions = listOf(
                PlateRegion(1, 0, intArrayOf(0, 0, 2, 0, 2, 2, 0, 2), 1, 1, 4),
            ),
            idMap = shortArrayOf(1, 1, 1, 9),
        )
        try {
            PlateCodec.encode(broken)
            throw AssertionError("Verwachtte een PlateFormatException")
        } catch (expected: PlateFormatException) {
            // goed
        }
    }

    @Test
    fun `grote compressibele id-map blijft compact`() {
        // 512x512 pixels, allemaal dezelfde waarde: deflate moet dit tot
        // enkele kilobytes terugbrengen. Zo blijft een plaat klein genoeg.
        val size = 512
        val plate = Plate(
            width = size,
            height = size,
            palette = intArrayOf(0xFFAABBCC.toInt()),
            regions = listOf(
                PlateRegion(1, 0, intArrayOf(0, 0, size, 0, size, size, 0, size), size / 2, size / 2, size * size),
            ),
            idMap = ShortArray(size * size) { 1 },
        )
        val bytes = PlateCodec.encode(plate)
        assertTrue("Bestand is ${bytes.size} bytes", bytes.size < 20_000)
        assertEquals(1, PlateCodec.decode(bytes).regionCount)
    }

    @Test
    fun `random coördinaten overleven de round-trip`() {
        val random = Random(42)
        repeat(20) {
            val id = it + 1
            val pointCount = 3 + random.nextInt(50)
            val points = IntArray(pointCount * 2) { random.nextInt(0, 2000) }
            val region = PlateRegion(id, 0, points, random.nextInt(2000), random.nextInt(2000), random.nextInt(1, 100_000))
            assertEquals(region, PlateCodec.decode(PlateCodec.encode(
                Plate(100, 100, intArrayOf(0xFF000000.toInt()), listOf(region), ShortArray(10_000) { if (it < 100) id.toShort() else 0 }),
            )).regions.single())
        }
    }
}
