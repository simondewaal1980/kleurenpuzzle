# Het `.kplate`-formaat (versie 1)

Een `.kplate`-bestand is één compact binair bestand per kleurplaat. Alle getallen
zijn **big-endian**. De payload is gecomprimeerd met zlib (RFC 1950), zodat de
id-map en de paden klein blijven.

## Bestandsindeling

| Bytes | Type | Betekenis |
|---:|---|---|
| 4 | ASCII | magie `KPLT` |
| 1 | u8 | formaatversie (`1`) |
| 1 | u8 | gereserveerd (0) |
| 4 | u32 | lengte van de **uitgepakte** payload |
| rest | zlib | de payload hieronder |

De decoder controleert de magie, de versie, de uitgepakte lengte en de zlib-
checksum. Beschadigde bestanden geven een `PlateFormatException` in plaats van
een crash of een oneindige lus.

## Payload (uitgepakt)

| Veld | Type | Opmerking |
|---|---|---|
| previewLength | u32 | lengte van de preview-JPEG |
| preview | bytes | JPEG-thumbnail, meestal ~100 KB |
| paletteSize | u16 | 1–256; in de praktijk 12–20 |
| palette | u32 × n | ARGB-kleuren |
| width | u16 | plaatbreedte in px |
| height | u16 | plaat hoogte in px |
| idMapWidth | u16 | breedte van de hit-test-map |
| idMapHeight | u16 | hoogte van de hit-test-map |
| regionCount | u16 | 1–5000 |
| regions | regio × n | zie hieronder |
| idMap | u16 × (mw × mh) | 0 = leeg, anders regio-id |

### Regio

| Veld | Type | Betekenis |
|---|---|---|
| id | u16 | 1-based, uniek, komt voor in de id-map |
| colorIndex | u16 | index in het palet |
| pointCount | u16 | ≥ 3 |
| points | u16 × 2 × n | x0, y0, x1, y1, ... (impliciet gesloten pad) |
| centroidX | u16 | x van het zwaartepunt (voor het nummer) |
| centroidY | u16 | y van het zwaartepunt |
| area | u32 | aantal bronpixels (tekenvolgorde, QA) |

De regio's staan in **groot-naar-klein**-volgorde. De renderer tekent ze in die
volgorde, zodat een grote achtergrondregio nooit per ongeluk een kleiner detail
eromheen bedekt. Gaten in een regio worden niet als apart pad opgeslagen; de
tekenvolgorde en het dirty-rect-algoritme vangen dat op.

## Waarom een id-map?

De vectorpaden zijn ideaal om te tekenen en te schalen, maar ongeschikt om snel
te bepalen op welk vlak je hebt getikt (`Path.contains` over honderden paden is
traag). De id-map is een kleine rasterversie van de plaat: één opzoeking per tik.
In dit project heeft de id-map dezelfde resolutie als de plaat; het formaat staat
ook een lagere resolutie toe.

## Wandelen door een bestand

```kotlin
val plate = PlateCodec.read(File("alpen-lautersee.kplate"))
println("${plate.width}x${plate.height}, ${plate.regionCount} vlakken")

val id = plate.regionIdAtNormalized(0.5f, 0.5f)
val regio = plate.regionById(id)
println("Kleur ${regio?.colorIndex?.plus(1)}, pad met ${regio?.pointCount} punten")
```

## Encoder/decoder

De referentie-implementatie staat in:

- `format/src/main/kotlin/nl/kleuren/format/Plate.kt` (model)
- `format/src/main/kotlin/nl/kleuren/format/PlateCodec.kt` (encode/decode)
- `format/src/test/kotlin/nl/kleuren/format/PlateCodecTest.kt` (round-trip en foutgevallen)

De encoder valideert vooraf onder andere:

- afmetingen en aantallen binnen veilige grenzen;
- unieke regio-ids;
- kleurindices binnen het palet;
- geldige puntcoördinaten (0–65535);
- id-mapwaarden die naar bestaande regio's verwijzen.

Zo kan een verkeerd gegenereerd bestand nooit stilletjes een kapotte plaat in de
app opleveren.
