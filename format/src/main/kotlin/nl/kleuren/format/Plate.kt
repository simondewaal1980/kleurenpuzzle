package nl.kleuren.format

/**
 * Een kleurplaat zoals de app die in het geheugen gebruikt.
 *
 * Belangrijk om te weten:
 *  - Alle coördinaten staan in "plaat-coördinaten": de pixelmaat van de
 *    verwerkte foto (bijvoorbeeld 1000 x 1333). De app schaalt dit naar het
 *    scherm en kan inzoomen zonder kwaliteitsverlies, omdat de vormen als
 *    vectoren (puntlijsten) zijn opgeslagen.
 *  - [palette] bevat ARGB-kleuren (Alpha, Red, Green, Blue in één Int).
 *  - [regions] zijn de inkleurbare vlakken. Elk vlak heeft een id (1-based)
 *    dat ook als waarde in [idMap] voorkomt.
 *  - [idMap] is een kleine "landkaart": per pixel staat welk regio-id daar
 *    ligt. Tikken op het scherm is daardoor een simpele O(1)-lookup in plaats
 *    van dure wiskunde op alle paden.
 *
 * Deze klasse is bewust *onveranderlijk* (immutable): de voortgang (welke
 * vlakken al gevuld zijn) hoort niet in het bestand, maar in de app-state.
 */
class Plate(
    val width: Int,
    val height: Int,
    val palette: IntArray,
    val regions: List<PlateRegion>,
    val idMap: ShortArray,
    val idMapWidth: Int = width,
    val idMapHeight: Int = height,
    val previewJpeg: ByteArray = ByteArray(0),
) {
    init {
        require(width > 0 && height > 0) { "Ongeldige plaatmaat: ${width}x$height" }
        require(palette.isNotEmpty()) { "Een plaat heeft minstens één kleur nodig" }
        require(idMap.size == idMapWidth * idMapHeight) {
            "idMap heeft ${idMap.size} pixels, verwacht ${idMapWidth * idMapHeight}"
        }
    }

    /** Aantal inkleurbare vlakken. */
    val regionCount: Int get() = regions.size

    /** Oppervlakte van het hele canvas in plaat-pixels. */
    val canvasArea: Int get() = width * height

    /**
     * Snelle opzoektabel: regio-id -> regio. Index 0 blijft leeg (id 0
     * betekent "geen regio"). Deze array wordt één keer bij het laden gemaakt.
     */
    private val regionsById: Array<PlateRegion?> = run {
        val maxId = regions.maxOfOrNull { it.id } ?: 0
        val table = arrayOfNulls<PlateRegion>(maxId + 1)
        for (region in regions) table[region.id] = region
        table
    }

    /** Geeft de regio met dit id, of null als het id niet bestaat. */
    fun regionById(id: Int): PlateRegion? =
        if (id in regionsById.indices) regionsById[id] else null

    /**
     * Hit-testing: welk regio-id ligt op plaat-coördinaat (x, y)?
     * Geeft 0 als de coördinaat buiten de plaat of op een lege rand valt.
     */
    fun regionIdAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= idMapWidth || y >= idMapHeight) return 0
        return idMap[y * idMapWidth + x].toInt() and 0xFFFF
    }

    /** Zelfde als [regionIdAt], maar met genormaliseerde coördinaten (0..1). */
    fun regionIdAtNormalized(nx: Float, ny: Float): Int {
        val x = (nx * idMapWidth).toInt().coerceIn(0, idMapWidth - 1)
        val y = (ny * idMapHeight).toInt().coerceIn(0, idMapHeight - 1)
        return regionIdAt(x, y)
    }

    /**
     * Hoeveel procent van de vlakken is gevuld? Wordt gebruikt door de
     * bibliotheek en de voortgangsbalk.
     */
    fun progress(filledRegionIds: Set<Int>): Float =
        if (regions.isEmpty()) 0f
        else filledRegionIds.count { regionById(it) != null }.toFloat() / regions.size

    fun isComplete(filledRegionIds: Set<Int>): Boolean =
        filledRegionIds.count { regionById(it) != null } >= regions.size

    /**
     * Kopie met een preview-JPEG. De pipeline kent geen JPEG-encoder (die is
     * platform-specifiek), dus de generator en de app voegen de preview toe
     * zodra ze die hebben gemaakt.
     */
    fun withPreviewJpeg(jpeg: ByteArray): Plate = Plate(
        width = width,
        height = height,
        palette = palette,
        regions = regions,
        idMap = idMap,
        idMapWidth = idMapWidth,
        idMapHeight = idMapHeight,
        previewJpeg = jpeg,
    )
}

/**
 * Eén inkleurbaar vlak.
 *
 * [points] is een platte lijst: x0, y0, x1, y1, ... De lijst is impliciet
 * gesloten (de laatste punt wordt met de eerste verbonden). De coördinaten
 * zijn gehele getallen in plaat-pixels; dat houdt het bestand compact.
 *
 * [centroidX]/[centroidY] is het zwaartepunt. Daar tekent de app het nummer.
 * [area] is het aantal bronpixels; het wordt gebruikt om grote achtergronden
 * eerst te tekenen (belangrijk voor gaten in regio's).
 */
class PlateRegion(
    val id: Int,
    val colorIndex: Int,
    val points: IntArray,
    val centroidX: Int,
    val centroidY: Int,
    val area: Int,
) {
    init {
        require(id in 1..65535) { "Regio-id moet 1..65535 zijn, was $id" }
        require(points.size >= 6 && points.size % 2 == 0) {
            "Een regio heeft minstens 3 punten (6 ints) nodig"
        }
    }

    /** Aantal punten (niet het aantal ints). */
    val pointCount: Int get() = points.size / 2

    fun xAt(index: Int): Int = points[index * 2]
    fun yAt(index: Int): Int = points[index * 2 + 1]

    // Handmatige equals/hashCode: IntArray vergelijkt standaard op referentie,
    // wat verwarrend is in tests. Zo vergelijken we écht de inhoud.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlateRegion) return false
        return id == other.id &&
            colorIndex == other.colorIndex &&
            points.contentEquals(other.points) &&
            centroidX == other.centroidX &&
            centroidY == other.centroidY &&
            area == other.area
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + colorIndex
        result = 31 * result + points.contentHashCode()
        result = 31 * result + centroidX
        result = 31 * result + centroidY
        result = 31 * result + area
        return result
    }

    override fun toString(): String =
        "PlateRegion(id=$id, kleur=$colorIndex, punten=$pointCount, area=$area)"
}
