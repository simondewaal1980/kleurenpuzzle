package nl.kleuren.pipeline

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Kleurhulpjes.
 *
 * Kleuren worden overal als ARGB-Int doorgegeven (zoals Android ze ook
 * gebruikt): 0xAARRGGBB. De pipeline negeert alpha, omdat foto's altijd
 * ondoorzichtig zijn.
 */
internal object ColorMath {

    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /**
     * Snelle kleurafstand met gewichten.
     *
     * Het menselijk oog is gevoeliger voor groen dan voor blauw. Daarom wegen
     * groen (4x) en rood (2x) zwaarder dan blauw (3x). Dit is een prima
     * benadering van "hoeveel lijken deze kleuren op elkaar" en veel sneller
     * dan een volledige CIELAB-berekening in de binnenste k-means-lus.
     */
    fun distanceSq(a: Int, b: Int): Int {
        val dr = red(a) - red(b)
        val dg = green(a) - green(b)
        val db = blue(a) - blue(b)
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db
    }

    /**
     * CIELAB-afstand (Delta E 1976). Dit gebruiken we alleen voor het mergen
     * van bijna-identieke paletkleuren: daar telt perceptie echt, en het aantal
     * vergelijkingen is klein (maximaal 20 kleuren).
     */
    fun deltaE(lab1: DoubleArray, lab2: DoubleArray): Double {
        val dl = lab1[0] - lab2[0]
        val da = lab1[1] - lab2[1]
        val db = lab1[2] - lab2[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Zet sRGB om naar CIELAB (D65-witpunt). */
    fun toLab(color: Int): DoubleArray {
        fun linearize(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        val r = linearize(red(color))
        val g = linearize(green(color))
        val b = linearize(blue(color))

        // sRGB -> XYZ (D65)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / 1.00000
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

        fun f(t: Double): Double =
            if (t > 0.008856) cbrt(t) else (7.787 * t + 16.0 / 116.0)

        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(
            116.0 * fy - 16.0,
            500.0 * (fx - fy),
            200.0 * (fy - fz),
        )
    }

    /** Contrast/helderheid, gebruikt voor het kiezen van een leesbare tekstkleur. */
    fun luminance(color: Int): Int =
        (299 * red(color) + 587 * green(color) + 114 * blue(color)) / 1000

    /** Geeft zwart of wit, afhankelijk van wat beter leesbaar is op [background]. */
    fun readableTextColor(background: Int): Int =
        if (luminance(background) > 150) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()

    /**
     * Gemiddelde kleur van een lijst pixels, gebruikt bij het downscalen en
     * het berekenen van een nieuw cluster-centrum.
     */
    fun average(colors: IntArray): Int {
        if (colors.isEmpty()) return 0xFF000000.toInt()
        var r = 0L
        var g = 0L
        var b = 0L
        for (color in colors) {
            r += red(color)
            g += green(color)
            b += blue(color)
        }
        return rgb((r / colors.size).toInt(), (g / colors.size).toInt(), (b / colors.size).toInt())
    }

    fun clampChannel(value: Double): Int = value.toInt().coerceIn(0, 255)
}
