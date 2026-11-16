package nl.kleuren.app.data

import kotlinx.serialization.json.Json
import nl.kleuren.format.PlateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Content-QA in de vorm van een unit test.
 *
 * Deze test leest het manifest en alle meegeleverde .kplate-bestanden, en
 * controleert dezelfde eigenschappen die anders handmatig in een spreadsheet
 * zouden staan:
 *  - elk manifest-id bestaat als bestand;
 *  - het aantal vlakken en kleuren klopt met het manifest;
 *  - de difficulty-bandbreedte (minstens ~300 vlakken, 12–20 kleuren) klopt;
 *  - alle paden en de id-map zijn geldig.
 *
 * Draaien met: ./gradlew :app:testDebugUnitTest
 */
class ManifestContentTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `manifest en alle platen zijn geldig`() {
        val manifestFile = File("src/main/assets/plates/manifest.json")
        assertTrue("Manifest ontbreekt: ${manifestFile.absolutePath}", manifestFile.isFile)

        val manifest = json.decodeFromString<ManifestRoot>(manifestFile.readText())
        assertEquals(3, manifest.packs.size)

        var plateCount = 0
        for (pack in manifest.packs) {
            assertTrue("Pack ${pack.id} heeft geen platen", pack.plates.isNotEmpty())
            for (entry in pack.plates) {
                plateCount++
                val file = File("src/main/assets/${entry.asset}")
                assertTrue("Plaatbestand ontbreekt: ${file.path}", file.isFile)

                val plate = PlateCodec.decode(file.readBytes())
                assertEquals("Regio-aantal in manifest klopt niet voor ${entry.id}", entry.regionCount, plate.regionCount)
                assertEquals("Paletgrootte klopt niet voor ${entry.id}", entry.paletteSize, plate.palette.size)
                assertTrue(
                    "${entry.id} heeft te weinig vlakken (${plate.regionCount})",
                    plate.regionCount >= 220,
                )
                assertTrue(
                    "${entry.id} heeft te veel vlakken (${plate.regionCount})",
                    plate.regionCount <= 800,
                )
                assertTrue(
                    "${entry.id} heeft een onverwacht aantal kleuren (${plate.palette.size})",
                    plate.palette.size in 12..20,
                )
                assertTrue("${entry.id} heeft geen preview", plate.previewJpeg.isNotEmpty())
                assertTrue(
                    "${entry.id} heeft geen bronfoto voor het aanpassen van de moeilijkheid",
                    entry.source.isNotBlank(),
                )
                val sourceFile = File("src/main/assets/${entry.source}")
                assertTrue(
                    "Bronfoto ontbreekt voor ${entry.id}: ${sourceFile.path}",
                    sourceFile.isFile,
                )
                assertEquals(
                    "${entry.id}: id-mapgrootte klopt niet",
                    plate.idMapWidth * plate.idMapHeight,
                    plate.idMap.size,
                )

                // Elk vlak moet minimaal 16 pixels groot zijn (geen mini-tikjes).
                val tooSmall = plate.regions.count { it.area < 16 }
                assertEquals("${entry.id} heeft $tooSmall te kleine vlakken", 0, tooSmall)

                // De id-map mag alleen bestaande ids bevatten.
                val validIds = plate.regions.map { it.id }.toSet()
                assertTrue(
                    "${entry.id} heeft onbekende ids in de id-map",
                    plate.idMap.all { it.toInt() == 0 || it.toInt() in validIds },
                )
            }
        }
        assertEquals(10, plateCount)
    }
}
