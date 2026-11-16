package nl.kleuren.pipeline

/**
 * Vijf moeilijkheidsgraden voor een kleurplaat.
 *
 * De meegeleverde landschappen gebruiken [MEDIUM] als vaste waarde, zoals in
 * plan.md staat. Voor "eigen foto toevoegen" kiest de gebruiker één van de
 * vijf niveaus. Elk niveau vertaalt zich naar dezelfde [ConvertOptions], zodat
 * de app en de generator precies dezelfde pijplijn blijven gebruiken.
 *
 * Van makkelijk naar moeilijk:
 *  - meer kleuren;
 *  - meer vlakken;
 *  - kleinere vlakken toegestaan (fijner detail);
 *  - scherpere, minder sterk vereenvoudigde contouren.
 */
enum class Difficulty(
    /** Naam zoals die in de UI verschijnt. */
    val label: String,

    /** Korte uitleg voor de keuzelijst. */
    val description: String,

    val colorCount: Int,
    val targetMinRegions: Int,
    val targetMaxRegions: Int,
    val minRegionFraction: Float,
    val simplifyEpsilon: Float,
) {
    VERY_EASY(
        label = "Zeer makkelijk",
        description = "Weinig kleuren en grote, rustige vlakken.",
        colorCount = 12,
        targetMinRegions = 150,
        targetMaxRegions = 300,
        minRegionFraction = 0.0012f,
        simplifyEpsilon = 2.4f,
    ),
    EASY(
        label = "Makkelijk",
        description = "Iets meer detail, nog steeds ruime vlakken.",
        colorCount = 13,
        targetMinRegions = 220,
        targetMaxRegions = 450,
        minRegionFraction = 0.0008f,
        simplifyEpsilon = 2.0f,
    ),
    MEDIUM(
        label = "Normaal",
        description = "De vaste moeilijkheid van de meegeleverde landschappen.",
        colorCount = 14,
        targetMinRegions = 300,
        targetMaxRegions = 800,
        minRegionFraction = 0.0004f,
        simplifyEpsilon = 1.6f,
    ),
    HARD(
        label = "Moeilijk",
        description = "Meer kleuren en kleinere vlakken om in te kleuren.",
        colorCount = 17,
        targetMinRegions = 450,
        targetMaxRegions = 800,
        minRegionFraction = 0.0003f,
        simplifyEpsilon = 1.4f,
    ),
    VERY_HARD(
        label = "Zeer moeilijk",
        description = "Maximaal detail: veel kleuren en veel kleine vlakken.",
        colorCount = 20,
        targetMinRegions = 600,
        targetMaxRegions = 800,
        minRegionFraction = 0.0002f,
        simplifyEpsilon = 1.2f,
    ),
    ;

    /**
     * Vertaalt dit niveau naar de parameters van de fotopijplijn.
     *
     * [maxSide] en [maxColorAttempts] zijn geen onderdeel van de moeilijkheid:
     * die bepalen respectievelijk de resolutie en de wachttijd. De standaard-
     * waarden zorgen dat [MEDIUM] exact overeenkomt met [ConvertOptions].
     */
    fun toConvertOptions(
        maxSide: Int = 1200,
        maxColorAttempts: Int = 2,
        randomSeed: Long = 20261116L,
    ): ConvertOptions = ConvertOptions(
        maxSide = maxSide,
        colorCount = colorCount,
        minRegionFraction = minRegionFraction,
        targetMinRegions = targetMinRegions,
        targetMaxRegions = targetMaxRegions,
        simplifyEpsilon = simplifyEpsilon,
        maxColorAttempts = maxColorAttempts,
        randomSeed = randomSeed,
    )

    companion object {
        /** Standaard: gelijk aan de vaste moeilijkheid uit plan.md. */
        val DEFAULT: Difficulty = MEDIUM

        /**
         * Leest een eerder opgeslagen naam. Onbekende of ontbrekende waarden
         * vallen terug op [DEFAULT], zodat een oude installatie blijft werken.
         */
        fun fromStorage(value: String?): Difficulty =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
