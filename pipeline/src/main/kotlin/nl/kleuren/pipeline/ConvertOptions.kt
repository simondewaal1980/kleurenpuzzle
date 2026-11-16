package nl.kleuren.pipeline

/**
 * Instellingen voor het omzetten van een foto naar een kleurplaat.
 *
 * Alle meegeleverde platen én de "eigen foto"-functie gebruiken dezelfde
 * standaardwaarden. Daardoor is de moeilijkheidsgraad constant, zoals in het
 * plan staat: 12–20 kleuren, 300–800 vlakken en geen frustrerende mini-vlakjes.
 */
data class ConvertOptions(
    /** Lange zijde van de plaat in pixels. Groter = meer detail, meer geheugen. */
    val maxSide: Int = 1200,

    /** Gewenst aantal kleuren. Het plan schrijft 12–20 voor; standaard 14. */
    val colorCount: Int = 14,

    /**
     * Vlakken kleiner dan dit deel van het canvas worden bij een buur getrokken.
     * 0,0004 = 0,04%: bij 1 megapixel is dat ongeveer 400 pixels.
     */
    val minRegionFraction: Float = 0.0004f,

    /** Streefband voor het aantal vlakken (informatief; de app accepteert ook minder). */
    val targetMinRegions: Int = 300,
    val targetMaxRegions: Int = 800,

    /**
     * Hoe agressief de omtrek wordt vereenvoudigd (in pixels). 1.0 verwijdert
     * de "trapjes" van pixelranden, maar houdt bochten natuurlijk.
     */
    val simplifyEpsilon: Float = 1.6f,

    /**
     * Als een foto te veel vlakken oplevert, proberen we het opnieuw met
     * minder kleuren. Dit is het maximale aantal pogingen (inclusief de eerste).
     */
    val maxColorAttempts: Int = 2,

    /** Vaste seed maakt de uitkomst reproduceerbaar (belangrijk voor QA en tests). */
    val randomSeed: Long = 20261116L,
) {
    init {
        require(maxSide in 128..4096) { "maxSide moet 128..4096 zijn" }
        require(colorCount in 4..64) { "colorCount moet 4..64 zijn" }
        require(minRegionFraction in 0f..0.2f) { "minRegionFraction moet 0..0.2 zijn" }
        require(targetMinRegions >= 1 && targetMaxRegions >= targetMinRegions)
    }
}

/** Fases van de conversie, zodat de UI een begrijpelijke voortgang kan tonen. */
enum class ConversionStage {
    PREPARING,
    SMOOTHING,
    QUANTIZING,
    SEGMENTING,
    TRACING,
    BUILDING,
    DONE,
}

/**
 * Voortgangsmelding. [fraction] loopt van 0.0 naar 1.0.
 *
 * De callback in [PhotoToPlate.convert] geeft `true` terug om door te gaan en
 * `false` om te annuleren. Zo kan de UI een annuleerknop aanbieden zonder dat
 * de pipeline iets van coroutines of Android hoeft te weten.
 */
data class ConversionProgress(
    val stage: ConversionStage,
    val fraction: Float,
    val message: String,
)

/** Wordt gegooid als de gebruiker de conversie halverwege annuleert. */
class ConversionCancelledException : RuntimeException("Conversie geannuleerd")
