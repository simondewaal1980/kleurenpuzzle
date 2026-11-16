package nl.kleuren.pipeline

import kotlin.math.max

/**
 * Interface om te vragen "hoort deze pixel bij de regio die ik teken?".
 * Een interface (in plaats van een lambda) voorkomt dat er voor elke pixel
 * een object wordt gemaakt; dit is de heetste lus van de omtrekdetectie.
 */
internal fun interface MaskTest {
    fun isFilled(x: Int, y: Int): Boolean
}

/**
 * Omtrekdetectie en -vereenvoudiging.
 *
 * We lopen als het ware met een potlood over de buitenrand van een regio,
 * met de gevulde pixels aan de rechterkant. Bij elk roosterpunt proberen we
 * eerst rechtsaf te slaan, dan rechtdoor en dan linksaf. Dat levert precies
 * de buitencontour op, ook bij holle vormen (gaten in de regio worden
 * genegeerd; die worden door de tekenvolgorde van grote naar kleine regio's
 * alsnog correct bedekt).
 */
internal object ContourTracer {

    private const val EAST = 0
    private const val SOUTH = 1
    private const val WEST = 2
    private const val NORTH = 3

    /**
     * Geeft de buitencontour van een regio als platte lijst x0,y0,x1,y1,...
     * De lijst is niet gesloten; de tekenaar verbindt het laatste punt met het
     * eerste.
     */
    fun traceOuterContour(
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        mask: MaskTest,
    ): IntArray {
        val points = ArrayList<Int>(64)
        var x = startX
        var y = startY
        var direction = EAST
        points.add(x)
        points.add(y)

        val maxSteps = width.toLong() * height * 4 + 16
        var steps = 0L

        while (true) {
            // Kies de volgende rand: rechtsaf heeft voorkeur, dan rechtdoor,
            // dan linksaf. Zo blijven we zo dicht mogelijk bij de regio.
            var chosen = -1
            for (turn in intArrayOf(1, 0, 3)) { // 1 = rechtsom, 0 = rechtdoor, 3 = linksom
                val candidate = (direction + turn) % 4
                if (isBoundary(x, y, candidate, mask)) {
                    chosen = candidate
                    break
                }
            }
            if (chosen < 0) {
                throw IllegalStateException("Kon contour niet vervolgen op ($x,$y)")
            }
            direction = chosen

            when (direction) {
                EAST -> x++
                SOUTH -> y++
                WEST -> x--
                NORTH -> y--
            }

            if (x == startX && y == startY) break // rondje is klaar

            points.add(x)
            points.add(y)

            steps++
            if (steps > maxSteps) {
                throw IllegalStateException("Contourdetectie raakte niet rond (oneindige lus?)")
            }
        }

        return points.toIntArray()
    }

    /**
     * Is de rand in deze richting écht een grens?
     * Rechts van de rand moet een gevulde pixel liggen, links een lege.
     *
     * De coördinaten hieronder zijn de 2x2-pixels rond het roosterpunt.
     */
    private fun isBoundary(x: Int, y: Int, direction: Int, mask: MaskTest): Boolean {
        val rightFilled = when (direction) {
            EAST -> mask.isFilled(x, y)            // zuid
            SOUTH -> mask.isFilled(x - 1, y)       // west
            WEST -> mask.isFilled(x - 1, y - 1)    // noord
            else -> mask.isFilled(x, y - 1)        // noord
        }
        if (!rightFilled) return false

        val leftFilled = when (direction) {
            EAST -> mask.isFilled(x, y - 1)
            SOUTH -> mask.isFilled(x, y)
            WEST -> mask.isFilled(x - 1, y)
            else -> mask.isFilled(x - 1, y - 1)
        }
        return !leftFilled
    }
}

/**
 * Douglas–Peucker-vereenvoudiging.
 *
 * Een pixelcontour bestaat uit honderden kleine "trapjes". Die zijn lelijk en
 * kosten opslag. Dit algoritme houdt alleen de punten die echt nodig zijn om
 * de vorm binnen [epsilon] pixels te benaderen.
 */
internal object ContourSimplifier {

    fun simplifyClosed(points: IntArray, epsilon: Float): IntArray {
        val count = points.size / 2
        if (count <= 4) return points

        // Kies het punt dat het verst van punt 0 ligt. Daarmee splitsen we de
        // gesloten lus in twee open lijnen, waarop het standaardalgoritme werkt.
        val x0 = points[0]
        val y0 = points[1]
        var split = 1
        var bestDistance = -1.0
        for (i in 1 until count) {
            val dx = (points[i * 2] - x0).toDouble()
            val dy = (points[i * 2 + 1] - y0).toDouble()
            val distance = dx * dx + dy * dy
            if (distance > bestDistance) {
                bestDistance = distance
                split = i
            }
        }
        if (bestDistance <= 0.0) return points

        val first = IntArray((split + 1) * 2)
        System.arraycopy(points, 0, first, 0, first.size)

        // Tweede deel loopt van split tot het einde en sluit terug naar punt 0.
        val secondCount = count - split + 1
        val second = IntArray(secondCount * 2)
        System.arraycopy(points, split * 2, second, 0, (count - split) * 2)
        second[second.size - 2] = x0
        second[second.size - 1] = y0

        val simplifiedFirst = simplifyOpen(first, epsilon)
        val simplifiedSecond = simplifyOpen(second, epsilon)

        val firstPoints = simplifiedFirst.size / 2
        val secondPoints = simplifiedSecond.size / 2

        // Combineer: eerste deel helemaal, tweede deel zonder zijn eerste punt
        // (dat is hetzelfde als het laatste punt van deel 1) en zonder de
        // terugkeer naar punt 0 (de tekenaar sluit de vorm zelf).
        val outputPoints = firstPoints + secondPoints - 2
        val output = IntArray(outputPoints * 2)
        System.arraycopy(simplifiedFirst, 0, output, 0, simplifiedFirst.size)
        System.arraycopy(
            simplifiedSecond,
            2,
            output,
            simplifiedFirst.size,
            (secondPoints - 2) * 2,
        )

        if (outputPoints < 3) return boundingBox(points)
        return output
    }

    /** Vereenvoudigt een open lijn (eerste en laatste punt blijven staan). */
    private fun simplifyOpen(points: IntArray, epsilon: Float): IntArray {
        val count = points.size / 2
        if (count <= 2) return points

        val keep = BooleanArray(count)
        keep[0] = true
        keep[count - 1] = true

        val stack = IntArray(count * 2)
        var stackSize = 0
        stack[stackSize++] = 0
        stack[stackSize++] = count - 1

        val epsilonSquared = epsilon.toDouble() * epsilon

        while (stackSize > 0) {
            val end = stack[--stackSize]
            val start = stack[--stackSize]
            if (end <= start + 1) continue

            val ax = points[start * 2].toDouble()
            val ay = points[start * 2 + 1].toDouble()
            val bx = points[end * 2].toDouble()
            val by = points[end * 2 + 1].toDouble()

            var farthest = -1
            var maxDistance = epsilonSquared
            for (i in start + 1 until end) {
                val distance = distanceToSegmentSquared(
                    points[i * 2].toDouble(),
                    points[i * 2 + 1].toDouble(),
                    ax, ay, bx, by,
                )
                if (distance > maxDistance) {
                    maxDistance = distance
                    farthest = i
                }
            }

            if (farthest >= 0) {
                keep[farthest] = true
                stack[stackSize++] = start
                stack[stackSize++] = farthest
                stack[stackSize++] = farthest
                stack[stackSize++] = end
            }
        }

        var kept = 0
        for (value in keep) if (value) kept++
        val output = IntArray(kept * 2)
        var outIndex = 0
        for (i in 0 until count) {
            if (keep[i]) {
                output[outIndex++] = points[i * 2]
                output[outIndex++] = points[i * 2 + 1]
            }
        }
        return output
    }

    private fun distanceToSegmentSquared(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) {
            val ex = px - ax
            val ey = py - ay
            return ex * ex + ey * ey
        }
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val ex = px - cx
        val ey = py - cy
        return ex * ex + ey * ey
    }

    /** Noodoplossing als vereenvoudiging te veel punten zou weghalen. */
    private fun boundingBox(points: IntArray): IntArray {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (i in 0 until points.size / 2) {
            minX = kotlin.math.min(minX, points[i * 2])
            maxX = max(maxX, points[i * 2])
            minY = kotlin.math.min(minY, points[i * 2 + 1])
            maxY = max(maxY, points[i * 2 + 1])
        }
        return intArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY)
    }
}
