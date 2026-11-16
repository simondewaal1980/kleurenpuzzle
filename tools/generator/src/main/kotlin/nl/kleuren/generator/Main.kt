package nl.kleuren.generator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import nl.kleuren.format.PlateCodec
import nl.kleuren.pipeline.ConversionProgress
import nl.kleuren.pipeline.ConvertOptions
import nl.kleuren.pipeline.PhotoToPlate
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * Desktop-CLI die de meegeleverde landschappen omzet naar .kplate-bestanden.
 *
 * Gebruik (vanuit de projectmap):
 *   ./gradlew :tools:generator:run --args="content/content.json app/src/main/assets"
 *
 * De CLI:
 *  1. leest content/content.json (packs, titels en bronfoto's);
 *  2. draait exact dezelfde :pipeline als de app;
 *  3. schrijft .kplate-bestanden + preview-JPEG's naar de assets;
 *  4. schrijft een manifest dat de app inleest;
 *  5. werkt content/CREDITS.md bij met de fotolicenties.
 *
 * Omdat de generator dezelfde code gebruikt als de app, is de moeilijkheid
 * van meegeleverde platen en "eigen foto's" identiek.
 */

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Gebruik: generator <content.json> [output-map]")
        println("Voorbeeld: generator content/content.json app/src/main/assets")
        exitProcess(2)
    }

    val configFile = File(args[0]).absoluteFile
    if (!configFile.isFile) {
        System.err.println("Configuratiebestand niet gevonden: $configFile")
        exitProcess(2)
    }
    val outputDir = File(args.getOrElse(1) { "app/src/main/assets" }).absoluteFile
    val contentDir = configFile.parentFile
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val config = json.decodeFromString<ContentConfig>(configFile.readText())
    val platesDir = File(outputDir, "plates")
    val previewDir = File(outputDir, "previews")
    val sourceDir = File(outputDir, "sources")
    platesDir.mkdirs()
    previewDir.mkdirs()
    sourceDir.mkdirs()

    println("Kleuren-generator")
    println("Config: ${configFile.path}")
    println("Uitvoer: ${outputDir.path}")
    println()

    val usedIds = HashSet<String>()
    var totalBytes = 0L
    val manifestPacks = ArrayList<ManifestPack>()

    for (pack in config.packs) {
        println("Pack: ${pack.title} (${pack.plates.size} platen)")
        val manifestPlates = ArrayList<ManifestPlate>()

        for (plateConfig in pack.plates) {
            if (!usedIds.add(plateConfig.id)) {
                error("Dubbel plaat-id: ${plateConfig.id}")
            }

            val sourceFile = File(contentDir, plateConfig.source)
            if (!sourceFile.isFile) {
                error("Bronfoto niet gevonden: ${sourceFile.path}")
            }

            print("  ${plateConfig.id} … ")
            val started = System.currentTimeMillis()
            val image = ImageIO.read(sourceFile)
                ?: error("Kon afbeelding niet lezen: ${sourceFile.path}")
            val width = image.width
            val height = image.height
            val pixels = IntArray(width * height)
            image.getRGB(0, 0, width, height, pixels, 0, width)

            val options = ConvertOptions(
                maxSide = 1200,
                colorCount = plateConfig.colors,
                targetMinRegions = 300,
                targetMaxRegions = 800,
                // Drie pogingen: de generator mag best wat langer draaien om
                // precies binnen de difficulty-bandbreedte te landen.
                maxColorAttempts = 3,
                randomSeed = plateConfig.id.hashCode().toLong(),
            )

            var lastPercent = -1
            val plate = PhotoToPlate.convert(
                width = width,
                height = height,
                argb = pixels,
                options = options,
            ) { progress: ConversionProgress ->
                val percent = (progress.fraction * 100).roundToInt()
                if (percent != lastPercent && percent % 10 == 0) {
                    lastPercent = percent
                    print("$percent% ")
                }
                true
            }

            val preview = encodeJpeg(image, maxSide = 560, quality = 0.85f)
            val source = encodeJpeg(image, maxSide = 1200, quality = 0.88f)
            val finishedPlate = plate.withPreviewJpeg(preview)
            val plateFile = File(platesDir, "${plateConfig.id}.kplate")
            PlateCodec.write(finishedPlate, plateFile)
            File(previewDir, "${plateConfig.id}.jpg").writeBytes(preview)
            File(sourceDir, "${plateConfig.id}.jpg").writeBytes(source)

            val seconds = (System.currentTimeMillis() - started) / 1000.0
            totalBytes += plateFile.length()
            println(
                "OK  ${plate.width}x${plate.height}, ${plate.regions.size} vlakken, " +
                    "${plate.palette.size} kleuren, ${plateFile.length() / 1024} KB, " +
                    "%.1f s".format(seconds),
            )

            if (plate.regions.size < options.targetMinRegions) {
                println(
                    "     Let op: minder dan ${options.targetMinRegions} vlakken. " +
                        "Dit is een rustige plaat; controleer visueel of hij niet te simpel is.",
                )
            }
            if (plate.regions.size > options.targetMaxRegions) {
                println("     Let op: meer dan ${options.targetMaxRegions} vlakken!")
            }

            manifestPlates += ManifestPlate(
                id = plateConfig.id,
                title = plateConfig.title,
                subtitle = plateConfig.subtitle,
                asset = "plates/${plateConfig.id}.kplate",
                preview = "previews/${plateConfig.id}.jpg",
                regionCount = plate.regions.size,
                paletteSize = plate.palette.size,
                credit = plateConfig.credit,
                license = plateConfig.license,
                sourceUrl = plateConfig.sourceUrl,
                source = "sources/${plateConfig.id}.jpg",
            )
        }
        manifestPacks += ManifestPack(
            id = pack.id,
            title = pack.title,
            description = pack.description,
            plates = manifestPlates,
        )
        println()
    }

    // Manifest voor de app.
    val manifest = buildJsonObject {
        put("version", 1)
        put("generatedAt", Instant.now().toString())
        putJsonArray("packs") {
            for (pack in manifestPacks) {
                add(buildJsonObject {
                    put("id", pack.id)
                    put("title", pack.title)
                    put("description", pack.description)
                    putJsonArray("plates") {
                        for (plate in pack.plates) {
                            add(buildJsonObject {
                                put("id", plate.id)
                                put("title", plate.title)
                                put("subtitle", plate.subtitle)
                                put("asset", plate.asset)
                                put("preview", plate.preview)
                                put("regionCount", plate.regionCount)
                                put("paletteSize", plate.paletteSize)
                                put("credit", plate.credit)
                                put("license", plate.license)
                                put("sourceUrl", plate.sourceUrl)
                                put("source", plate.source)
                            })
                        }
                    }
                })
            }
        }
    }

    val manifestFile = File(platesDir, "manifest.json")
    manifestFile.writeText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), manifest))
    writeCredits(File(contentDir, "CREDITS.md"), config)

    println("Klaar: ${usedIds.size} platen, ${totalBytes / 1024} KB aan .kplate-bestanden.")
    println("Manifest: ${manifestFile.path}")
    println("Credits:  ${File(contentDir, "CREDITS.md").path}")
}

/**
 * Maakt een verkleinde JPEG met [maxSide] als langste zijde.
 * [quality] 0..1: hoger = mooier en groter. Wordt gebruikt voor zowel de
 * bibliotheekpreview (560 px) als de bronfoto in de assets (1200 px, gelijk
 * aan de maximale zijde van de pijplijn).
 */
private fun encodeJpeg(image: BufferedImage, maxSide: Int, quality: Float): ByteArray {
    val scale = minOf(1.0, maxSide.toDouble() / maxOf(image.width, image.height))
    val targetWidth = (image.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (image.height * scale).roundToInt().coerceAtLeast(1)
    val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null)
    graphics.dispose()

    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val parameters = writer.defaultWriteParam
    parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
    parameters.compressionQuality = quality
    return ByteArrayOutputStream(256 * 1024).use { buffer ->
        ImageIO.createImageOutputStream(buffer).use { output ->
            writer.output = output
            writer.write(null, IIOImage(target, null, null), parameters)
        }
        writer.dispose()
        buffer.toByteArray()
    }
}

/** Schrijft een eenvoudig, controleerbaar overzicht van alle fotobronnen. */
private fun writeCredits(file: File, config: ContentConfig) {
    val builder = StringBuilder()
    builder.appendLine("# Fotocredits")
    builder.appendLine()
    builder.appendLine("Alle bronfoto's in deze repository zijn **CC0** (publiek domein) en")
    builder.appendLine("gedownload via Wikimedia Commons. CC0 betekent dat ze vrij gebruikt,")
    builder.appendLine("aangepast en verspreid mogen worden, ook commercieel, zonder naamsvermelding.")
    builder.appendLine("We vermelden de makers toch, uit respectvolle bronadministratie.")
    builder.appendLine()
    builder.appendLine("| Plaat | Pack | Foto | Licentie | Bron |")
    builder.appendLine("|---|---|---|---|---|")
    for (pack in config.packs) {
        for (plate in pack.plates) {
            builder.appendLine(
                "| ${plate.title} | ${pack.title} | ${plate.credit} | ${plate.license} | ${plate.sourceUrl} |",
            )
        }
    }
    builder.appendLine()
    builder.appendLine("De gegenereerde kleurplaten zijn afgeleide werken van deze foto's.")
    file.writeText(builder.toString())
}

// ---------------------------------------------------------------------------
// JSON-modellen voor content/content.json
// ---------------------------------------------------------------------------

@Serializable
private data class ContentConfig(
    val packs: List<PackConfig>,
)

@Serializable
private data class PackConfig(
    val id: String,
    val title: String,
    val description: String = "",
    val plates: List<PlateConfig>,
)

@Serializable
private data class PlateConfig(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val source: String,
    val credit: String = "",
    val license: String = "CC0",
    val sourceUrl: String = "",
    val colors: Int = 14,
)

// Alleen gebruikt om het manifest op te bouwen.
private data class ManifestPack(
    val id: String,
    val title: String,
    val description: String,
    val plates: List<ManifestPlate>,
)

private data class ManifestPlate(
    val id: String,
    val title: String,
    val subtitle: String,
    val asset: String,
    val preview: String,
    val regionCount: Int,
    val paletteSize: Int,
    val credit: String,
    val license: String,
    val sourceUrl: String,
    val source: String,
)
