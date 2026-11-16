package nl.kleuren.format

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Wordt gegooid als een .kplate-bestand beschadigd of van een andere versie is. */
class PlateFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Leest en schrijft het binaire .kplate-formaat (versie 1).
 *
 * Waarom binair en niet JSON? Een plaat heeft honderden regio's met duizenden
 * punten en een id-map van een miljoen pixels. Als tekst zou dat megabytes
 * kosten; binair + deflate (zip-compressie) blijft het rond de 200–500 KB.
 *
 * Bestandsindeling:
 * ```
 *  4 bytes  magie "KPLT"
 *  1 byte   formaatversie (1)
 *  1 byte   gereserveerd (0)
 *  4 bytes  lengte van de uitgepakte payload (beveiliging tegen geheugen-aanvallen)
 *  ...      payload, gecomprimeerd met zlib (Deflater)
 * ```
 *
 * Payload (uitgepakt):
 * ```
 *  4 bytes  lengte preview-JPEG
 *  ...      preview-JPEG
 *  2 bytes  aantal kleuren
 *  ...      per kleur 4 bytes ARGB
 *  2 bytes  breedte van de plaat
 *  2 bytes  hoogte van de plaat
 *  2 bytes  breedte van de id-map
 *  2 bytes  hoogte van de id-map
 *  2 bytes  aantal regio's
 *  ...      per regio: id, kleurindex, aantal punten, punten (x,y), zwaartepunt, oppervlakte
 *  ...      id-map: 2 bytes per pixel (0 = leeg, anders regio-id)
 * ```
 *
 * Alle getallen zijn big-endian (de standaard van DataOutputStream). Shorts
 * worden gebruikt als "unsigned 16-bit"; bij het lezen maskeren we met 0xFFFF.
 */
object PlateCodec {

    private val MAGIC = byteArrayOf('K'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    const val VERSION: Int = 1

    /** Veiligheidslimieten: voorkomen dat een kapot bestand de app laat crashen. */
    private const val MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024
    private const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024
    private const val MAX_DIMENSION = 8192
    private const val MAX_REGIONS = 5000
    private const val MAX_PALETTE = 256
    private const val MAX_POINTS_PER_REGION = 20_000

    // -----------------------------------------------------------------------
    // Schrijven
    // -----------------------------------------------------------------------

    /** Zet een [Plate] om naar bytes. */
    fun encode(plate: Plate): ByteArray {
        validateForWriting(plate)

        // Eerst de payload opbouwen in het geheugen...
        val payloadBytes = ByteArrayOutputStream(1 shl 20).use { payloadBuffer ->
            DataOutputStream(payloadBuffer).use { out ->
                out.writeInt(plate.previewJpeg.size)
                out.write(plate.previewJpeg)

                out.writeShort(plate.palette.size)
                for (color in plate.palette) out.writeInt(color)

                out.writeShort(plate.width)
                out.writeShort(plate.height)
                out.writeShort(plate.idMapWidth)
                out.writeShort(plate.idMapHeight)
                out.writeShort(plate.regions.size)

                for (region in plate.regions) {
                    out.writeShort(region.id)
                    out.writeShort(region.colorIndex)
                    out.writeShort(region.pointCount)
                    for (i in 0 until region.pointCount) {
                        out.writeShort(region.xAt(i))
                        out.writeShort(region.yAt(i))
                    }
                    out.writeShort(region.centroidX)
                    out.writeShort(region.centroidY)
                    out.writeInt(region.area)
                }

                // De id-map is het grootste stuk, maar bestaat uit lange reeksen
                // hetzelfde getal. Deflate comprimeert dat uitstekend.
                for (pixel in plate.idMap) out.writeShort(pixel.toInt() and 0xFFFF)
            }
            payloadBuffer.toByteArray()
        }

        // ...en daarna alles comprimeren en van een header voorzien.
        return ByteArrayOutputStream(payloadBytes.size / 2 + 64).use { fileBuffer ->
            DataOutputStream(BufferedOutputStream(fileBuffer)).use { out ->
                out.write(MAGIC)
                out.writeByte(VERSION)
                out.writeByte(0) // gereserveerd voor toekomstige opties
                out.writeInt(payloadBytes.size)

                // DeflaterOutputStream schrijft een zlib-stream met checksum.
                // Daardoor merkt de decoder automatisch beschadigde bestanden op.
                DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { deflater ->
                    deflater.write(payloadBytes)
                }
            }
            fileBuffer.toByteArray()
        }
    }

    /** Schrijft een plaat atomisch naar schijf (eerst .tmp, dan rename). */
    fun write(plate: Plate, file: File) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(encode(plate))
        // rename is atomisch op hetzelfde bestandssysteem: nooit een half bestand.
        if (!temp.renameTo(file)) {
            file.writeBytes(temp.readBytes())
            temp.delete()
        }
    }

    // -----------------------------------------------------------------------
    // Lezen
    // -----------------------------------------------------------------------

    fun read(file: File): Plate = BufferedInputStream(FileInputStream(file)).use { decode(it) }

    fun decode(bytes: ByteArray): Plate = decode(ByteArrayInputStream(bytes))

    fun decode(input: InputStream): Plate {
        try {
            DataInputStream(BufferedInputStream(input)).use { data ->
                val magic = ByteArray(4)
                data.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw PlateFormatException("Geen .kplate-bestand (magie klopt niet)")
                }
                val version = data.readUnsignedByte()
                if (version != VERSION) {
                    throw PlateFormatException("Onbekende formaatversie $version (app ondersteunt $VERSION)")
                }
                data.readUnsignedByte() // gereserveerde flags
                val uncompressedSize = data.readInt()
                if (uncompressedSize <= 0 || uncompressedSize > MAX_UNCOMPRESSED_BYTES) {
                    throw PlateFormatException("Onlogische payloadlengte: $uncompressedSize")
                }

                val inflated = InflaterInputStream(data).use { it.readBytes() }
                if (inflated.size != uncompressedSize) {
                    throw PlateFormatException(
                        "Payload is ${inflated.size} bytes, header zegt $uncompressedSize",
                    )
                }

                return decodePayload(inflated)
            }
        } catch (e: PlateFormatException) {
            throw e
        } catch (e: Exception) {
            // Leesfouten (EOF, corrupte zlib, ...) vertalen naar één duidelijke fout.
            throw PlateFormatException("Kon kleurplaat niet lezen: ${e.message}", e)
        }
    }

    private fun decodePayload(payload: ByteArray): Plate {
        DataInputStream(ByteArrayInputStream(payload)).use { data ->
            val previewLength = data.readInt()
            if (previewLength < 0 || previewLength > MAX_PREVIEW_BYTES) {
                throw PlateFormatException("Ongeldige previewlengte: $previewLength")
            }
            val preview = ByteArray(previewLength)
            data.readFully(preview)

            val paletteSize = data.readUnsignedShort()
            if (paletteSize !in 1..MAX_PALETTE) {
                throw PlateFormatException("Ongeldig aantal kleuren: $paletteSize")
            }
            val palette = IntArray(paletteSize) { data.readInt() }

            val width = data.readUnsignedShort()
            val height = data.readUnsignedShort()
            val idMapWidth = data.readUnsignedShort()
            val idMapHeight = data.readUnsignedShort()
            if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION ||
                idMapWidth !in 1..MAX_DIMENSION || idMapHeight !in 1..MAX_DIMENSION
            ) {
                throw PlateFormatException(
                    "Ongeldige afmetingen: plaat ${width}x$height, id-map ${idMapWidth}x$idMapHeight",
                )
            }

            val regionCount = data.readUnsignedShort()
            if (regionCount !in 1..MAX_REGIONS) {
                throw PlateFormatException("Ongeldig aantal regio's: $regionCount")
            }

            val regions = ArrayList<PlateRegion>(regionCount)
            val seenIds = HashSet<Int>(regionCount * 2)
            for (index in 0 until regionCount) {
                val id = data.readUnsignedShort()
                val colorIndex = data.readUnsignedShort()
                if (id !in 1..65535 || !seenIds.add(id)) {
                    throw PlateFormatException("Ongeldig of dubbel regio-id: $id")
                }
                if (colorIndex !in palette.indices) {
                    throw PlateFormatException("Regio $id verwijst naar kleur $colorIndex, maar die bestaat niet")
                }
                val pointCount = data.readUnsignedShort()
                if (pointCount !in 3..MAX_POINTS_PER_REGION) {
                    throw PlateFormatException("Regio $id heeft $pointCount punten (minimaal 3)")
                }
                val points = IntArray(pointCount * 2)
                for (p in 0 until pointCount) {
                    points[p * 2] = data.readUnsignedShort()
                    points[p * 2 + 1] = data.readUnsignedShort()
                }
                val centroidX = data.readUnsignedShort()
                val centroidY = data.readUnsignedShort()
                val area = data.readInt()
                if (area < 0) throw PlateFormatException("Regio $id heeft negatieve oppervlakte")
                regions += PlateRegion(id, colorIndex, points, centroidX, centroidY, area)
            }

            val pixelCount = idMapWidth * idMapHeight
            val idMap = ShortArray(pixelCount)
            for (p in 0 until pixelCount) {
                val value = data.readUnsignedShort()
                if (value != 0 && !seenIds.contains(value)) {
                    throw PlateFormatException("id-map verwijst op pixel $p naar onbekend regio-id $value")
                }
                idMap[p] = value.toShort()
            }

            return Plate(
                width = width,
                height = height,
                palette = palette,
                regions = regions,
                idMap = idMap,
                idMapWidth = idMapWidth,
                idMapHeight = idMapHeight,
                previewJpeg = preview,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Validatie vóór het schrijven
    // -----------------------------------------------------------------------

    private fun validateForWriting(plate: Plate) {
        if (plate.width !in 1..MAX_DIMENSION || plate.height !in 1..MAX_DIMENSION) {
            throw PlateFormatException("Plaat is te groot: ${plate.width}x${plate.height}")
        }
        if (plate.idMapWidth !in 1..MAX_DIMENSION || plate.idMapHeight !in 1..MAX_DIMENSION) {
            throw PlateFormatException("id-map is te groot")
        }
        if (plate.palette.size !in 1..MAX_PALETTE) {
            throw PlateFormatException("Aantal kleuren moet 1..$MAX_PALETTE zijn")
        }
        if (plate.regions.size !in 1..MAX_REGIONS) {
            throw PlateFormatException("Aantal regio's moet 1..$MAX_REGIONS zijn")
        }
        if (plate.previewJpeg.size > MAX_PREVIEW_BYTES) {
            throw PlateFormatException("Preview is te groot (${plate.previewJpeg.size} bytes)")
        }
        if (plate.idMap.size != plate.idMapWidth * plate.idMapHeight) {
            throw PlateFormatException("id-map-grootte klopt niet")
        }

        val ids = HashSet<Int>(plate.regions.size * 2)
        for (region in plate.regions) {
            if (!ids.add(region.id)) throw PlateFormatException("Dubbel regio-id ${region.id}")
            if (region.colorIndex !in plate.palette.indices) {
                throw PlateFormatException("Regio ${region.id} heeft ongeldige kleurindex")
            }
            if (region.pointCount !in 3..MAX_POINTS_PER_REGION) {
                throw PlateFormatException("Regio ${region.id} heeft een ongeldig aantal punten")
            }
            for (coordinate in region.points) {
                if (coordinate !in 0..65535) {
                    throw PlateFormatException("Coördinaat $coordinate valt buiten 0..65535")
                }
            }
        }
        for (pixelValue in plate.idMap) {
            val id = pixelValue.toInt() and 0xFFFF
            if (id != 0 && !ids.contains(id)) {
                throw PlateFormatException("id-map bevat onbekend regio-id $id")
            }
        }
    }
}
