package nl.kleuren.app.ui.board

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.kleuren.format.Plate
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deelt een voltooide plaat als PNG.
 *
 * We maken een nieuwe bitmap zonder lijnen en nummers: dat is het "kunstwerk"
 * dat de gebruiker wil bewaren of versturen. De bitmap wordt in de cache van
 * de app gezet en via een FileProvider gedeeld, dus zonder opslagtoestemming.
 */
suspend fun sharePlate(
    context: Context,
    plate: Plate,
    filled: Set<Int>,
    title: String,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val bitmap = renderCompletedPlate(plate, filled)
        val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeName = title.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "kleuren" }
        val file = File(shareDirectory, "$safeName.png")
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        withContext(Dispatchers.Main) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Kleurplaat delen"))
        }
        true
    } catch (e: Exception) {
        Log.e("SharePlate", "Delen mislukt", e)
        false
    }
}

/**
 * Tekent de plaat op een schone bitmap: alleen gevulde kleurvlakken.
 * De volgorde is dezelfde als op het bord (groot naar klein).
 */
private fun renderCompletedPlate(plate: Plate, filled: Set<Int>): Bitmap {
    val maxSide = 1600
    val scale = min(1f, maxSide.toFloat() / max(plate.width, plate.height))
    val width = (plate.width * scale).roundToInt().coerceAtLeast(1)
    val height = (plate.height * scale).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(scale, scale)
    canvas.drawColor(0xFFFCF9F2.toInt())

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    for (region in plate.regions) {
        if (!filled.contains(region.id)) continue
        val path = Path()
        path.moveTo(region.xAt(0).toFloat(), region.yAt(0).toFloat())
        for (i in 1 until region.pointCount) {
            path.lineTo(region.xAt(i).toFloat(), region.yAt(i).toFloat())
        }
        path.close()
        paint.color = plate.palette[region.colorIndex]
        canvas.drawPath(path, paint)
    }
    return bitmap
}
