package nl.kleuren.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De vijf moeilijkheidsniveaus zijn een gebruikerskeuze, maar moeten wel
 * allemaal geldige pijplijnparameters opleveren. Deze tests bewaken bovendien
 * dat "Normaal" exact de vaste moeilijkheid blijft die de content gebruikt.
 */
class DifficultyTest {

    @Test
    fun `er zijn vijf moeilijkheidsniveaus`() {
        assertEquals(5, Difficulty.entries.size)
    }

    @Test
    fun `normaal is de standaard en gelijk aan de vaste moeilijkheid`() {
        assertEquals(Difficulty.MEDIUM, Difficulty.DEFAULT)
        assertEquals(ConvertOptions(), Difficulty.DEFAULT.toConvertOptions())
    }

    @Test
    fun `niveaus lopen op van makkelijk naar moeilijk`() {
        val options = Difficulty.entries.map { it.toConvertOptions() }

        options.zipWithNext().forEach { (easier, harder) ->
            assertTrue(
                "Meer kleuren verwacht: ${easier.colorCount} -> ${harder.colorCount}",
                harder.colorCount >= easier.colorCount,
            )
            assertTrue(
                "Meer vlakken verwacht: ${easier.targetMinRegions} -> " +
                    "${harder.targetMinRegions}",
                harder.targetMinRegions >= easier.targetMinRegions,
            )
            assertTrue(
                "Kleinere vlakken verwacht: ${easier.minRegionFraction} -> " +
                    "${harder.minRegionFraction}",
                harder.minRegionFraction <= easier.minRegionFraction,
            )
            assertTrue(
                "Fijnere contouren verwacht: ${easier.simplifyEpsilon} -> " +
                    "${harder.simplifyEpsilon}",
                harder.simplifyEpsilon <= easier.simplifyEpsilon,
            )
        }
    }

    @Test
    fun `opgeslagen waarde valt terug op de standaard`() {
        assertEquals(Difficulty.HARD, Difficulty.fromStorage("HARD"))
        assertEquals(Difficulty.DEFAULT, Difficulty.fromStorage("onbekend"))
        assertEquals(Difficulty.DEFAULT, Difficulty.fromStorage(null))
    }
}
