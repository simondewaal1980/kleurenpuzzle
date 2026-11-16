package nl.kleuren.pipeline

import kotlin.math.max
import kotlin.math.roundToInt

/** Een regio zoals de extractor hem oplevert (nog zonder plaat-id-map). */
internal class ExtractedRegion(
    val id: Int,
    val colorIndex: Int,
    val points: IntArray,
    val centroidX: Int,
    val centroidY: Int,
    val area: Int,
)

internal class ExtractionResult(
    val width: Int,
    val height: Int,
    val regions: List<ExtractedRegion>,
    val idMap: ShortArray,
)

/**
 * Van een kleurgekwantiseerde foto naar losse inkleurbare vlakken.
 *
 * Stappen:
 *  1. **Connected components**: pixels met dezelfde kleur die aan elkaar
 *     grenzen (boven/onder/links/rechts) vormen samen één vlak.
 *  2. **Kleine vlakken samenvoegen**: een vlak van 3 pixels is niet in te
 *     kleuren. Zulke vlakjes gaan op in de buur met de langste gedeelde rand.
 *  3. **Maximum bewaken**: als er meer dan 800 vlakken zijn, smelten we de
 *     kleinste samen tot we binnen de difficulty-bandbreedte zitten.
 *  4. **Contouren**: van elk vlak wordt de buitenrand getraceerd en
 *     vereenvoudigd tot een compact vectorpad.
 *  5. **ID-map**: per pixel opslaan welk vlak er ligt, voor snelle hit-testing.
 */
internal object RegionExtractor {

    fun extract(
        quantized: QuantizedImage,
        options: ConvertOptions,
        onProgress: (Float) -> Unit,
    ): ExtractionResult {
        val width = quantized.width
        val height = quantized.height
        val pixelCount = width * height

        // ---- 1. Connected components ----------------------------------------
        val labeling = labelComponents(quantized.labels, width, height)
        onProgress(0.15f)

        val graph = ComponentGraph(width, height, labeling)
        graph.buildAdjacency()
        onProgress(0.30f)

        // ---- 2. Kleine vlakken samenvoegen ----------------------------------
        val minimumArea = max(16, (pixelCount * options.minRegionFraction).roundToInt())
        graph.mergeSmallRegions(minimumArea)
        onProgress(0.45f)

        // ---- 3. Boven de maximum-bandbreedte terugdringen --------------------
        graph.mergeDownTo(options.targetMaxRegions)
        onProgress(0.55f)

        val roots = graph.roots().sortedBy { graph.firstPixel[it] }
        onProgress(0.60f)

        // ---- 4. Contouren -----------------------------------------------------
        val traced = ArrayList<TracedRegion>(roots.size)
        for ((index, root) in roots.withIndex()) {
            val mask = MaskTest { x, y ->
                if (x < 0 || y < 0 || x >= width || y >= height) {
                    false
                } else {
                    graph.find(graph.pixelComponent[y * width + x]) == root
                }
            }
            val startPixel = graph.firstPixel[root]
            val rawContour = ContourTracer.traceOuterContour(
                width = width,
                height = height,
                startX = startPixel % width,
                startY = startPixel / width,
                mask = mask,
            )
            val simplified = ContourSimplifier.simplifyClosed(rawContour, options.simplifyEpsilon)
            traced += TracedRegion(
                root = root,
                colorIndex = graph.color[root],
                points = simplified,
                area = graph.area[root],
                centroidX = (graph.sumX[root] / graph.area[root]).toInt(),
                centroidY = (graph.sumY[root] / graph.area[root]).toInt(),
            )
            onProgress(0.60f + 0.35f * (index + 1) / roots.size)
        }

        // ---- 5. Volgorde, id's en id-map -------------------------------------
        // Groot naar klein: de achtergrond eerst, details later. Daardoor
        // bedekt een grote regio nooit per ongeluk een kleiner detail erin.
        traced.sortWith(compareByDescending<TracedRegion> { it.area }.thenBy { it.root })

        val regions = ArrayList<ExtractedRegion>(traced.size)
        val idByRoot = HashMap<Int, Int>(traced.size * 2)
        for ((index, region) in traced.withIndex()) {
            val id = index + 1
            idByRoot[region.root] = id
            regions += ExtractedRegion(
                id = id,
                colorIndex = region.colorIndex,
                points = region.points,
                centroidX = region.centroidX.coerceIn(0, width),
                centroidY = region.centroidY.coerceIn(0, height),
                area = region.area,
            )
        }

        val idMap = ShortArray(pixelCount)
        for (pixel in 0 until pixelCount) {
            val root = graph.find(graph.pixelComponent[pixel])
            idMap[pixel] = (idByRoot[root] ?: 0).toShort()
        }
        onProgress(1f)

        return ExtractionResult(width, height, regions, idMap)
    }

    // -----------------------------------------------------------------------
    // Connected components
    // -----------------------------------------------------------------------

    private class Component(val color: Int, val firstPixel: Int) {
        var area: Int = 0
        var sumX: Long = 0
        var sumY: Long = 0
    }

    private class Labeling(
        val components: List<Component>,
        val pixelComponent: IntArray,
    )

    /**
     * Flood-fill (BFS) per kleur. Iteratief met een array als wachtrij, zodat
     * een grote achtergrond geen stack overflow veroorzaakt.
     */
    private fun labelComponents(
        labels: IntArray,
        width: Int,
        height: Int,
    ): Labeling {
        val pixelCount = width * height
        val pixelComponent = IntArray(pixelCount) // wordt later in de graph gezet
        val queue = IntArray(pixelCount)
        val comps = ArrayList<Component>(1024)

        // We hebben pixelComponent ook in de graph nodig. Om een extra array te
        // vermijden vullen we hem hier en geven hem via een wrapper mee.
        var componentId = 0
        for (start in 0 until pixelCount) {
            if (pixelComponent[start] != 0) continue
            componentId++
            val color = labels[start]
            val component = Component(color, start)
            pixelComponent[start] = componentId
            var head = 0
            var tail = 0
            queue[tail++] = start

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                component.area++
                component.sumX += x
                component.sumY += y

                if (x > 0) {
                    val n = index - 1
                    if (pixelComponent[n] == 0 && labels[n] == color) {
                        pixelComponent[n] = componentId
                        queue[tail++] = n
                    }
                }
                if (x + 1 < width) {
                    val n = index + 1
                    if (pixelComponent[n] == 0 && labels[n] == color) {
                        pixelComponent[n] = componentId
                        queue[tail++] = n
                    }
                }
                if (y > 0) {
                    val n = index - width
                    if (pixelComponent[n] == 0 && labels[n] == color) {
                        pixelComponent[n] = componentId
                        queue[tail++] = n
                    }
                }
                if (y + 1 < height) {
                    val n = index + width
                    if (pixelComponent[n] == 0 && labels[n] == color) {
                        pixelComponent[n] = componentId
                        queue[tail++] = n
                    }
                }
            }
            comps += component
        }

        // De pixel->component-array gaat mee in de labeling; de graph gebruikt
        // hem om buren en contouren te bepalen.
        return Labeling(comps, pixelComponent)
    }

    // -----------------------------------------------------------------------
    // Union-find + buren
    // -----------------------------------------------------------------------

    private class ComponentGraph(
        val width: Int,
        val height: Int,
        labeling: Labeling,
    ) {
        val pixelComponent: IntArray = labeling.pixelComponent
        val componentCount: Int = labeling.components.size

        val parent = IntArray(componentCount + 1) { it }
        val area = IntArray(componentCount + 1)
        val sumX = LongArray(componentCount + 1)
        val sumY = LongArray(componentCount + 1)
        val color = IntArray(componentCount + 1)
        val firstPixel = IntArray(componentCount + 1)

        /**
         * Heeft deze component minstens één pixel die aan alle vier kanten
         * door dezelfde component is omringd? Zo niet, dan is het een streep
         * van 1–2 pixels dik (bijv. een wolkband of rietje) en die is met de
         * vinger niet te raken. Zulke strepen voegen we samen met een buur.
         */
        val hasInterior = BooleanArray(componentCount + 1)

        // Voor elke component een map "buur -> aantal gedeelde pixelanden".
        val adjacency = arrayOfNulls<HashMap<Int, Int>>(componentCount + 1)

        init {
            for (index in labeling.components.indices) {
                val id = index + 1
                val component = labeling.components[index]
                area[id] = component.area
                sumX[id] = component.sumX
                sumY[id] = component.sumY
                color[id] = component.color
                firstPixel[id] = component.firstPixel
                adjacency[id] = HashMap(4)
            }

            // Bepaal per component of hij "dik" genoeg is.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val id = pixelComponent[index]
                    if (id == 0) continue
                    val left = x > 0 && pixelComponent[index - 1] == id
                    val right = x + 1 < width && pixelComponent[index + 1] == id
                    val up = y > 0 && pixelComponent[index - width] == id
                    val down = y + 1 < height && pixelComponent[index + width] == id
                    if (left && right && up && down) hasInterior[id] = true
                }
            }
        }

        /** Zoekt de representant van een component (met padcompressie). */
        fun find(value: Int): Int {
            var root = value
            while (parent[root] != root) root = parent[root]
            var current = value
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        /** Bouwt de buren-relaties op basis van gedeelde pixelanden. */
        fun buildAdjacency() {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val a = pixelComponent[index]
                    if (x + 1 < width) {
                        val b = pixelComponent[index + 1]
                        if (a != b) addContact(a, b)
                    }
                    if (y + 1 < height) {
                        val b = pixelComponent[index + width]
                        if (a != b) addContact(a, b)
                    }
                }
            }
        }

        private fun addContact(a: Int, b: Int) {
            if (a == 0 || b == 0) return
            adjacency[a]!!.merge(b, 1, Int::plus)
            adjacency[b]!!.merge(a, 1, Int::plus)
        }

        /** Voegt [b] samen met [a]; de grootste van de twee wordt de baas. */
        fun union(a: Int, b: Int): Int {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA == rootB) return rootA

            val target = if (area[rootA] >= area[rootB]) rootA else rootB
            val source = if (target == rootA) rootB else rootA

            parent[source] = target
            area[target] += area[source]
            sumX[target] += sumX[source]
            sumY[target] += sumY[source]
            firstPixel[target] = minOf(firstPixel[target], firstPixel[source])
            hasInterior[target] = hasInterior[target] || hasInterior[source]
            // De kleur van de grootste regio blijft behouden.

            // Buren van de bron toevoegen aan de doelmap. Buren die intussen
            // zelf zijn samengevoegd, worden via find() naar hun root vertaald.
            val targetMap = adjacency[target]!!
            adjacency[source]?.forEach { (neighbor, count) ->
                val resolved = find(neighbor)
                if (resolved != target) {
                    targetMap.merge(resolved, count, Int::plus)
                }
            }
            adjacency[source] = null
            area[source] = 0
            return target
        }

        /** De buur met de langste gedeelde rand (bij gelijke stand: de grootste). */
        fun bestNeighbor(root: Int): Int? {
            val map = adjacency[root] ?: return null
            val totals = HashMap<Int, Int>()
            for ((neighbor, count) in map) {
                val resolved = find(neighbor)
                if (resolved != root && area[resolved] > 0) {
                    totals.merge(resolved, count, Int::plus)
                }
            }
            return totals.maxWithOrNull(
                compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { area[it.key] },
            )?.key
        }

        fun roots(): List<Int> {
            val result = ArrayList<Int>(componentCount)
            for (id in 1..componentCount) {
                if (parent[id] == id && area[id] > 0) result += id
            }
            return result
        }

        /**
         * Voegt alle regio's kleiner dan [minimumArea] samen met een buur.
         * We herhalen dit tot er niets meer verandert: een klein eilandje kan
         * namelijk in een ander klein eilandje opgaan en daarna alsnog te
         * klein zijn.
         */
        fun mergeSmallRegions(minimumArea: Int) {
            var changed = true
            var guard = 0
            while (changed && guard++ < 100) {
                changed = false
                // Grootste kleintjes eerst: dan houden we zoveel mogelijk
                // echte detailvlakken intact.
                val smallOnes = roots()
                    .filter { area[it] < minimumArea || !hasInterior[it] }
                    .sortedByDescending { area[it] }
                for (root in smallOnes) {
                    val current = find(root)
                    if (area[current] >= minimumArea && hasInterior[current]) continue
                    val target = bestNeighbor(current) ?: continue
                    union(current, target)
                    changed = true
                }
            }
        }

        /** Smelt de kleinste regio's tot het aantal onder [maximum] zit. */
        fun mergeDownTo(maximum: Int) {
            var guard = 0
            while (guard++ < 100_000) {
                val all = roots()
                if (all.size <= maximum) return
                val smallest = all.minByOrNull { area[it] } ?: return
                val target = bestNeighbor(smallest) ?: return
                union(smallest, target)
            }
        }
    }

    private class TracedRegion(
        val root: Int,
        val colorIndex: Int,
        val points: IntArray,
        val area: Int,
        val centroidX: Int,
        val centroidY: Int,
    )
}
