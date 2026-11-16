package nl.kleuren.app.ui.board

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.kleuren.format.Plate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f

/**
 * Zoom- en panpositie van het bord. Bewust een apart klasje: de UI kan zo
 * ook buiten het Canvas om een "pas aan"-knop tonen, en de state overleeft
 * recomposities.
 */
@Stable
class BoardViewport {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    // Het Canvas vult deze waarden in; de zoomknoppen buiten het Canvas
    // kunnen daardoor de pan netjes binnen de plaat houden.
    var viewWidth by mutableFloatStateOf(0f)
    var viewHeight by mutableFloatStateOf(0f)

    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    /** Zoomt rond het midden van het scherm (voor de + en − knoppen). */
    fun zoomBy(factor: Float, plate: Plate) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (viewWidth <= 0f || viewHeight <= 0f) {
            zoom = newZoom
            return
        }
        val fit = min(viewWidth / plate.width, viewHeight / plate.height)
        val newScale = fit * newZoom
        val maxPanX = max(0f, (plate.width * newScale - viewWidth) / 2f)
        val maxPanY = max(0f, (plate.height * newScale - viewHeight) / 2f)
        val ratio = newZoom / zoom
        panX = (panX * ratio).coerceIn(-maxPanX, maxPanX)
        panY = (panY * ratio).coerceIn(-maxPanY, maxPanY)
        zoom = newZoom
        if (zoom <= MIN_ZOOM + 0.001f) {
            panX = 0f
            panY = 0f
        }
    }
}

/**
 * Het interactieve tekenvlak: zoom, pan, tikken en nummers.
 *
 * We tekenen bewust met een Android [Canvas] en [Matrix] in plaats van alles
 * met Compose-graphics. Redenen:
 *  - de bitmaplagen uit [BoardRenderer] zijn Android-bitmaps;
 *  - dezelfde matrix gebruiken voor hit-testing en tekenen voorkomt
 *    afrondingsverschillen;
 *  - tekst tekenen op een native canvas is snel en houdt de cijfers scherp.
 */
@Composable
fun BoardCanvas(
    plate: Plate,
    filled: Set<Int>,
    selectedColor: Int,
    showNumbers: Boolean,
    hintsEnabled: Boolean,
    revealProgress: Float,
    viewport: BoardViewport,
    onTapRegion: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = androidx.compose.material3.MaterialTheme.colorScheme.background
    val isDark = background.luminance() < 0.5f

    // Het canvas blijft altijd licht, ook in donker thema. Kleuren uit de
    // foto komen zo betrouwbaar over en de plaat blijft rustig leesbaar.
    // Alleen de app eromheen wisselt van thema.
    val paper = Color(0xFFFBF7EE)
    val line = Color(0xFF5D564C)
    val ink = Color(0xFF33382F)

    val renderer = remember(plate, paper, line) {
        BoardRenderer(
            plate = plate,
            paperColor = paper.toArgb(),
            lineColor = line.toArgb(),
        )
    }

    // Zorg dat de bitmaplaag de bestaande voortgang toont. Dit gebeurt na de
    // eerste compositie; daarna bij elke vulactie.
    LaunchedEffect(renderer, filled) {
        renderer.setFilled(filled)
    }

    // Hint: alle nog lege vlakken van de geselecteerde kleur in één Path.
    val hintPath = remember(renderer, selectedColor, filled, hintsEnabled) {
        if (hintsEnabled) renderer.buildColorPath(selectedColor) else null
    }

    val density = LocalDensity.current
    val numberSizePx = with(density) { 11.sp.toPx() }
    val haloWidthPx = with(density) { 3.dp.toPx() }

    val numberPaint = remember(numberSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = numberSizePx
        }
    }
    val haloPaint = remember(numberSizePx, haloWidthPx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = numberSizePx
            style = Paint.Style.STROKE
            strokeWidth = haloWidthPx
            strokeJoin = Paint.Join.ROUND
        }
    }
    val hintPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }
    val hintOutlinePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
    }
    val plateBorderPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
    }
    val numberSpacingPx = with(density) { 24.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Eén vinger, korte tik: vlak inkleuren.
            .onSizeChanged {
                viewport.viewWidth = it.width.toFloat()
                viewport.viewHeight = it.height.toFloat()
            }
            .pointerInput(plate) {
                detectTapGestures { offset ->
                    val viewWidth = size.width.toFloat()
                    val viewHeight = size.height.toFloat()
                    if (viewWidth <= 0f || viewHeight <= 0f) return@detectTapGestures
                    val fit = min(viewWidth / plate.width, viewHeight / plate.height)
                    val scale = fit * viewport.zoom
                    val contentWidth = plate.width * scale
                    val contentHeight = plate.height * scale
                    val left = (viewWidth - contentWidth) / 2f + viewport.panX
                    val top = (viewHeight - contentHeight) / 2f + viewport.panY
                    val nx = (offset.x - left) / contentWidth
                    val ny = (offset.y - top) / contentHeight
                    if (nx in 0f..1f && ny in 0f..1f) {
                        val id = plate.regionIdAtNormalized(nx, ny)
                        if (id > 0) onTapRegion(id)
                    }
                }
            }
            // Twee vingers: in- en uitzoomen en verschuiven.
            .pointerInput(plate) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val viewWidth = size.width.toFloat()
                    val viewHeight = size.height.toFloat()
                    if (viewWidth <= 0f || viewHeight <= 0f) return@detectTransformGestures

                    val oldZoom = viewport.zoom
                    val newZoom = (oldZoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val fit = min(viewWidth / plate.width, viewHeight / plate.height)
                    val oldScale = fit * oldZoom
                    val newScale = fit * newZoom
                    val centerX = viewWidth / 2f
                    val centerY = viewHeight / 2f
                    val plateCenterX = plate.width / 2f
                    val plateCenterY = plate.height / 2f

                    // Het punt onder de vingers moet op zijn plaats blijven.
                    val focusPlateX = (centroid.x - centerX - viewport.panX) / oldScale + plateCenterX
                    val focusPlateY = (centroid.y - centerY - viewport.panY) / oldScale + plateCenterY

                    var candidatePanX = centroid.x - centerX -
                        (focusPlateX - plateCenterX) * newScale + pan.x
                    var candidatePanY = centroid.y - centerY -
                        (focusPlateY - plateCenterY) * newScale + pan.y

                    if (newZoom <= MIN_ZOOM + 0.001f) {
                        candidatePanX = 0f
                        candidatePanY = 0f
                    } else {
                        val maxPanX = max(0f, (plate.width * newScale - viewWidth) / 2f)
                        val maxPanY = max(0f, (plate.height * newScale - viewHeight) / 2f)
                        candidatePanX = candidatePanX.coerceIn(-maxPanX, maxPanX)
                        candidatePanY = candidatePanY.coerceIn(-maxPanY, maxPanY)
                    }

                    viewport.zoom = newZoom
                    viewport.panX = candidatePanX
                    viewport.panY = candidatePanY
                }
            },
    ) {
        val viewWidth = size.width
        val viewHeight = size.height
        if (viewWidth <= 0f || viewHeight <= 0f) return@Canvas

        val fit = min(viewWidth / plate.width, viewHeight / plate.height)
        val scale = fit * viewport.zoom
        val contentWidth = plate.width * scale
        val contentHeight = plate.height * scale
        val left = (viewWidth - contentWidth) / 2f + viewport.panX
        val top = (viewHeight - contentHeight) / 2f + viewport.panY

        drawRect(color = background, size = size)

        // Matrix: plaat-coördinaten -> schermcoördinaten.
        val plateMatrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
        // Matrix: bitmap-coördinaten -> schermcoördinaten.
        val bitmapMatrix = Matrix().apply {
            postScale(scale / renderer.renderScale, scale / renderer.renderScale)
            postTranslate(left, top)
        }

        // Een subtiele rand maakt duidelijk waar de plaat ophoudt; zonder
        // rand lijkt de crème papierkleur op de achtergrond van het scherm.
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            plateBorderPaint.color = if (isDark) 0x40FFFFFF else 0x33000000
            native.drawRect(
                left - 1f,
                top - 1f,
                left + contentWidth + 1f,
                top + contentHeight + 1f,
                plateBorderPaint,
            )
            renderer.drawFillLayer(native, bitmapMatrix)
            renderer.drawLineLayer(native, bitmapMatrix, alpha = (255 * revealProgress).roundToInt())

            if (hintPath != null && revealProgress > 0.05f) {
                val selected = plate.palette.getOrNull(selectedColor) ?: return@drawIntoCanvas
                hintPaint.color = selected
                hintPaint.alpha = 70
                hintOutlinePaint.color = 0x66FFFFFF
                native.save()
                native.concat(plateMatrix)
                native.drawPath(hintPath, hintPaint)
                native.drawPath(hintPath, hintOutlinePaint)
                native.restore()
            }

            if (showNumbers && revealProgress > 0.03f) {
                // Alleen zichtbare nummers tekenen. Bij ver uitzoomen zijn
                // kleine vlakken onleesbaar; die nummers verschijnen pas als
                // je inzoomt. Dat houdt het beeld rustig en snel.
                val visiblePlateRect = RectF(0f, 0f, viewWidth, viewHeight)
                val inverse = Matrix()
                if (plateMatrix.invert(inverse)) inverse.mapRect(visiblePlateRect)

                haloPaint.color = paper.toArgb()
                haloPaint.alpha = 220
                numberPaint.color = ink.toArgb()
                numberPaint.alpha = (210 * revealProgress).toInt()
                haloPaint.alpha = (220 * revealProgress).toInt()

                val point = FloatArray(2)
                val drawnPositions = ArrayList<FloatArray>(128)
                for (region in plate.regions) {
                    if (filled.contains(region.id)) continue
                    if (region.centroidX < visiblePlateRect.left - 30f ||
                        region.centroidX > visiblePlateRect.right + 30f ||
                        region.centroidY < visiblePlateRect.top - 30f ||
                        region.centroidY > visiblePlateRect.bottom + 30f
                    ) {
                        continue
                    }
                    // Geschatte schermoppervlakte van dit vlak. Te kleine
                    // nummers zouden alleen maar ruis opleveren; ze verschijnen
                    // zodra je inzoomt.
                    val screenArea = region.area * scale * scale
                    if (screenArea < 220f) continue

                    point[0] = region.centroidX.toFloat()
                    point[1] = region.centroidY.toFloat()
                    plateMatrix.mapPoints(point)

                    // Voorkom dat nummers van kleine buurvlakken over elkaar
                    // heen worden getekend. Bij diepere zoom staan ze verder
                    // uit elkaar en verschijnen ze alsnog.
                    val overlaps = drawnPositions.any { other ->
                        val dx = other[0] - point[0]
                        val dy = other[1] - point[1]
                        dx * dx + dy * dy < numberSpacingPx * numberSpacingPx
                    }
                    if (overlaps) continue
                    drawnPositions += floatArrayOf(point[0], point[1])

                    val label = (region.colorIndex + 1).toString()
                    native.drawText(label, point[0], point[1] + numberSizePx * 0.35f, haloPaint)
                    native.drawText(label, point[0], point[1] + numberSizePx * 0.35f, numberPaint)
                }
            }
        }
    }
}
