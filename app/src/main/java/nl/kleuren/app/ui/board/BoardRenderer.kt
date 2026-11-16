package nl.kleuren.app.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.BitmapShader
import android.graphics.Shader
import nl.kleuren.format.Plate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * De tekenmotor van het kleurbord.
 *
 * Performance-idee uit het plan:
 *  - De ingekleurde vlakken en de lijnen worden één keer naar bitmaps getekend.
 *    Elk frame hoeft daarna alleen nog twee bitmaps te tonen (goedkoop).
 *  - Bij het vullen van één vlak tekenen we alleen de rechthoek rond dat vlak
 *    opnieuw ("dirty rect"). Alle regio's die daarbinnen vallen worden in de
 *    juiste volgorde opnieuw getekend, zodat gaten en details kloppen.
 *  - Nummers en de hint-highlight worden dynamisch over de bitmaps getekend,
 *    zodat ze scherp blijven bij elke zoomstand.
 *
 * De bitmaps hebben een vaste resolutie (lange zijde ~1800 px). Bij ver
 * inzoomen worden ze dus iets zachter; dat is de bewuste ruil tussen geheugen
 * en scherpte. Een getegelde versie kan later achter dezelfde klasse worden
 * gezet.
 */
class BoardRenderer(
    val plate: Plate,
    paperColor: Int,
    lineColor: Int,
) {
    companion object {
        /** Maximale lange zijde van de bitmaplagen. */
        private const val TARGET_LONG_SIDE = 1800f

        /** Maakt een klein, herhaalbaar papierpatroon (16 x 16 px). */
        fun createPaperPattern(paperColor: Int): Bitmap {
            val size = 16
            val pattern = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(pattern)
            canvas.drawColor(paperColor)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x16564F45
                style = Paint.Style.FILL
            }
            canvas.drawCircle(3f, 3f, 0.9f, dot)
            return pattern
        }
    }

    val renderScale: Float = min(
        2f,
        TARGET_LONG_SIDE / max(plate.width, plate.height).toFloat(),
    )

    val bitmapWidth: Int = (plate.width * renderScale).roundToInt().coerceAtLeast(1)
    val bitmapHeight: Int = (plate.height * renderScale).roundToInt().coerceAtLeast(1)

    private val fillBitmap: Bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.RGB_565)
    private val lineBitmap: Bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

    private val fillCanvas = Canvas(fillBitmap)
    private val lineCanvas = Canvas(lineBitmap)

    private val maxId = plate.regions.maxOfOrNull { it.id } ?: 0
    private val paths: Array<Path?> = arrayOfNulls(maxId + 1)
    private val bounds: Array<RectF?> = arrayOfNulls(maxId + 1)
    private val filled = BooleanArray(maxId + 1)

    private val paperColor = paperColor

    /**
     * Een heel subtiel stippatroon op het oningekleurde papier. Daardoor is
     * een ingekleurd wit vlak (zoals sneeuw of een wolk) tóch zichtbaar: het
     * vlak wordt egaal, terwijl de rest van de plaat korrelig papier blijft.
     */
    private val paperPaint = Paint().apply {
        isAntiAlias = false
        shader = BitmapShader(
            createPaperPattern(paperColor),
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT,
        )
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = lineColor
        alpha = 120
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    init {
        // De canvas wordt één keer geschaald; alle paden blijven in
        // plaat-coördinaten. Dat scheelt rekenwerk en houdt de code leesbaar.
        fillCanvas.scale(renderScale, renderScale)
        lineCanvas.scale(renderScale, renderScale)

        for (region in plate.regions) {
            val path = Path()
            path.moveTo(region.xAt(0).toFloat(), region.yAt(0).toFloat())
            for (i in 1 until region.pointCount) {
                path.lineTo(region.xAt(i).toFloat(), region.yAt(i).toFloat())
            }
            path.close()
            paths[region.id] = path
            val rect = RectF()
            path.computeBounds(rect, true)
            bounds[region.id] = rect
        }

        fillCanvas.drawRect(0f, 0f, plate.width.toFloat(), plate.height.toFloat(), paperPaint)
        drawOutlines()
    }

    /** Tekent de omtrekken één keer op de lijnenlaag. */
    private fun drawOutlines() {
        lineCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        for (region in plate.regions) {
            paths[region.id]?.let { lineCanvas.drawPath(it, linePaint) }
        }
    }

    /**
     * Synchroniseert de bitmap met de gevulde set. Wordt na elke vulactie
     * aangeroepen (en bij het laden van een bestaande voortgang).
     */
    fun setFilled(newFilled: Set<Int>) {
        val changed = ArrayList<Int>(8)
        var fullRedraw = false
        for (region in plate.regions) {
            val shouldBeFilled = newFilled.contains(region.id)
            if (filled[region.id] != shouldBeFilled) {
                filled[region.id] = shouldBeFilled
                changed += region.id
                if (changed.size > 12) fullRedraw = true
            }
        }
        if (changed.isEmpty()) return

        if (fullRedraw) {
            // Bij veel wijzigingen (bijv. een magische vulactie of het laden
            // van bestaande voortgang) is één volledige hertekening sneller.
            redrawAll()
        } else {
            // Alleen de gewijzigde regio's opnieuw tekenen. De methode tekent
            // daarbinnen ook alle andere gevulde regio's die het gebied raken.
            for (regionId in changed) {
                redrawRegionArea(regionId)
            }
        }
    }

    private fun redrawAll() {
        fillCanvas.drawRect(0f, 0f, plate.width.toFloat(), plate.height.toFloat(), paperPaint)
        for (region in plate.regions) {
            if (filled[region.id]) {
                fillPaint.color = plate.palette[region.colorIndex]
                paths[region.id]?.let { fillCanvas.drawPath(it, fillPaint) }
            }
        }
    }

    /**
     * Tekent het gebied rond één regio opnieuw. We tekenen daarbij álle
     * gevulde regio's die het gebied raken, in de opgeslagen volgorde
     * (groot -> klein). Zo blijven details binnen een grote achtergrondregio
     * zichtbaar, ook als die achtergrond later wordt gevuld.
     */
    private fun redrawRegionArea(regionId: Int) {
        val rect = bounds[regionId] ?: return
        val dirty = RectF(rect)
        dirty.inset(-2f, -2f)

        val save = fillCanvas.save()
        fillCanvas.clipRect(dirty)
        fillCanvas.drawRect(dirty, paperPaint)
        for (region in plate.regions) {
            if (!filled[region.id]) continue
            val regionBounds = bounds[region.id] ?: continue
            if (RectF.intersects(regionBounds, dirty)) {
                fillPaint.color = plate.palette[region.colorIndex]
                paths[region.id]?.let { fillCanvas.drawPath(it, fillPaint) }
            }
        }
        fillCanvas.restoreToCount(save)
    }

    /**
     * Maakt één Path met alle regio's van een kleur. Wordt gebruikt voor de
     * hint-modus: zo hoeft de UI niet honderden paden per frame te tekenen.
     */
    fun buildColorPath(colorIndex: Int, onlyUnfilled: Boolean = true): Path {
        val union = Path()
        for (region in plate.regions) {
            if (region.colorIndex != colorIndex) continue
            if (onlyUnfilled && filled[region.id]) continue
            paths[region.id]?.let { union.addPath(it) }
        }
        return union
    }

    fun drawFillLayer(canvas: Canvas, matrix: android.graphics.Matrix, alpha: Int = 255) {
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(fillBitmap, matrix, bitmapPaint)
    }

    fun drawLineLayer(canvas: Canvas, matrix: android.graphics.Matrix, alpha: Int = 255) {
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(lineBitmap, matrix, bitmapPaint)
    }

    /**
     * Rendert de voltooide plaat zonder lijnen en nummers (voor delen).
     * Alleen gevulde vlakken worden getekend; de achtergrond is crème.
     */
    fun createFilledBitmap(maxSide: Int = 1600, background: Int = 0xFFFCF9F2.toInt()): Bitmap {
        val scale = min(1f, maxSide.toFloat() / max(plate.width, plate.height))
        val width = (plate.width * scale).roundToInt().coerceAtLeast(1)
        val height = (plate.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.drawColor(background)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (region in plate.regions) {
            if (!filled[region.id]) continue
            paint.color = plate.palette[region.colorIndex]
            paths[region.id]?.let { canvas.drawPath(it, paint) }
        }
        return bitmap
    }
}
