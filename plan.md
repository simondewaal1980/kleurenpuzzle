# Plan: Number Painting App "Kleuren" (Android)

## 1. Samenvatting

Een native Android "paint by number"-app voor volwassenen, gericht op ontspanning. Kleurplaten hebben een **constante, rustige moeilijkheidsgraad** (vast aantal kleuren en regio's per plaat). Thema van de meegeleverde content: **Europese landschappen** (geen kinderplaten). Gebruikers kunnen **eigen foto's importeren** en laten omzetten naar een kleurplaat.

- **Doelapparaat (performance-baseline):** Samsung Galaxy A54 5G — Exynos 1380 (4×A78 2.4 GHz + 4×A55), Mali-G68 MP5, 6/8 GB RAM, 1080×2340 120 Hz.
- **Testapparaat (ontwikkeling):** OnePlus Nord 4 — Snapdragon 7+ Gen 3, 8+ GB RAM, 1240×2772 120 Hz.
- De app moet vloeiend (60 fps) draaien op de A54; de Nord 4 is sneller en dient als dagelijks dev-toestel. Optimalisaties worden altijd op de A54 geverifieerd.

## 2. Kernkeuzes technologie

| Keuze | Beslissing | Motivatie |
|---|---|---|
| Platform | Native Android, **Kotlin** | Beste performance/footprint op midrange (A54); geen Unity/game-engine nodig (te zwaar) |
| UI | **Jetpack Compose** (Material 3) | Modern, snel te bouwen; kleurbord zelf is custom Canvas |
| Rendering kleurbord | Android **Skia Canvas** + hardware acceleratie, met een gecachete bitmap-laag voor ingekleurde regio's | ~300–800 regio's is prima Canvas-werk; alleen overlay (nummers, selectie) wordt dynamisch getekend |
| Architectuur | MVVM + unidirectionele dataflow, packages per feature | Simpel te onderhouden, goed testbaar |
| Opslag | **Room** (voortgang, eigen platen), **DataStore** (instellingen), assets (gebundelde packs) | Progress = kleine bitsets, platen = compact binair formaat |
| Min/target SDK | minSdk 26 (Android 8.0), targetSdk nieuwste stable | Dekt A54 en Nord 4 ruim af |
| Foto's kiezen | Android **Photo Picker** (`ActivityResultContracts.PickVisualMedia`) | Geen opslag-permissies nodig |
| Achtergrondwerk | Coroutines (segmentatie eigen foto), WorkManager optioneel voor zware taken | Foto → plaat duurt enkele seconden, met voortgangs-UI |

## 3. Het plaatformaat en de difficulty-constant

### 3.1 Vaste moeilijkheid
Alle kleurplaten worden gegenereerd met **zelfde pijplijn-parameters**, zodat de moeilijkheid constant blijft:
- Vast palet per plaat: **12–20 kleuren**.
- Aantal regio's: **300–800** (afhankelijk van detail, maar binnen vaste bandbreedte).
- Minimale regio-oppervlakte: regio's kleiner dan ~0,05% van het canvas worden samengevoegd met een buurregio (voomt gefrustreerde mini-tikjes).
- Nummers altijd zichtbaar (optioneel uitschakelbaar), geen tijdsdruk, geen lives/energy-systeem.

### 3.2 Bestandsformaat per kleurplaat
Compact binair formaat (of protobuf), per plaat ~200–500 KB:
- **Header**: versie, afmetingen, paletgrootte.
- **Palet**: ARGB-kleuren + kleurnummers.
- **Regio's**: lijst van `{ vereenvoudigd vectorpad (gekwantiseerde coördinaten), kleurIndex, nummer, centroid (voor nummerlabel), oppervlakte }`.
- **ID-map**: verkleinde lossless bitmap (bijv. 1024 px breed) met regio-ID per pixel → snelle, robuuste **hit-testing** bij tikken.
- **Preview**: WebP-thumbnail van het origineel/voorbeeld.
- **Voortgang**: bitset van ingekleurde regio-IDs (in Room), plus undo-stack in geheugen.

## 4. Segmentatiepijplijn (foto → kleurplaat)

Twee uitvoeringen van **hetzelfde algoritme**, zodat moeilijkheid overal consistent is:

1. **Offline tool (desktop, Kotlin/JVM-CLI in dezelfde repo)** — voor de meegeleverde Europese-landschappen-content:
   - Foto downscalen naar ~1600–2048 px.
   - Kleurkwantisatie: **k-means** naar vaste K (12–20), optioneel met lichte bilaterale filtering om ruis te dempen.
   - Labelmap → regio-extractie (connected components per kleur), kleine regio's mergen.
   - Contouren extraheren (Marching Squares) → **path-simplificatie** (Douglas–Peucker) voor compacte vectorpaden.
   - Nummerlabels plaatsen op centroiden; export naar plaatformaat + preview.
   - Handmatige QA-steekproef: elke plaat wordt proef-ingekleurd.

2. **On-device (Kotlin, in de app)** — voor "eigen foto toevoegen":
   - Identieke stappen, draaiend op `Dispatchers.Default` met coroutines.
   - Doel: < 5 s voor een 12 MP-foto op A54 (Nord 4 ruimschoots sneller).
   - UX: foto kiezen → voorbeeld met instelbaar kleuraantal (standaard = vaste difficulty) → genereren met voortgangsbalk → plaat verschijnt onder "Mijn foto's".
   - Fallback: bij zeer complexe foto's automatisch iets grovere kwantisatie om binnen de regio-bandbreedte (300–800) te blijven.

## 5. App-structuur en schermen

```
app/
  ui/            → Compose-schermen + kleurbord-view
  board/         → render-engine (Canvas, bitmap-laag, zoom/pan, hit-testing)
  pipeline/      → segmentatie/kwantisatie (on-device)
  data/          → Room, repositories, pack-loader, plaatformaat (decoder)
  domain/        → modellen (Pack, Picture, Palette, Region, Progress)
tools/
  generator/     → offline CLI-pijplijn voor content
```

**Schermen:**
1. **Home / Bibliotheek**: tabs — *Landschappen* (gebundelde packs per regio, bijv. "Alpen", "Middellandse Zee", "Noord-Europa"), *Verder gaan* (WIP's), *Mijn foto's*. Kaartweergave met thumbnail, voortgangspercentage.
2. **Kleurbord** (hart van de app):
   - Pinch-zoom & pan (matrix-transformatie; bij diepe zoom tiled渲染 van de bitmap-laag).
   - Onderin: paletbalk met kleur + nummer; geselecteerde kleur accentueert bijbehorende regio's subtiel (optionele "hint"-modus).
   - Tik op regio = vullen met geselecteerde kleur (alleen als kleur klopt; anders zachte feedback, geen straf).
   - Long-press/"Magic": alle regio's van de huidige kleur in één keer vullen (opt-in, houdt ontspanning maar versnelt saaie vlakken).
   - Undo (onbeperkt binnen sessie), voortgangs-%, autosave bij elke actie.
   - Zoom-naar-regio bij selectie uit palet (optioneel).
   - Voltooien: rustige reveal-animatie (plaat zonder nummers/lijnen), delen als PNG.
3. **Foto toevoegen**: Photo Picker → voorbeeld/generate-scherm → resultaat in "Mijn foto's".
4. **Instellingen**: nummers aan/uit, hint-modus, geluid/haptics, donkere modus, kleurblind-modus (symbolen/patronen bij nummers), data wissen.

**Ontspannings-UX-principes:** geen timers, geen reclame tijdens het kleuren, geen energy-systeem, gedempte kleuren UI, optionele zachte achtergrondgeluiden, haptische tick bij vullen.

## 6. Performance-plan (A54 als baseline)

- **Static/dynamic splitsing**: ingekleurde regio's + lijnen renderen naar een offscreen **bitmap-laag** (per zoom-niveau, LRU-cache van max 2 niveaus); per frame wordt alleen de laag getekend + dynamische overlay (nummers, highlight, selectie).
- **Vullen = dirty-rect**: alleen het getroffen gebied van de bitmap-laag opnieuw tekenen (`Canvas.clipRect`), geen full redraw.
- **Bitmapgeheugen budget**: canvas-laag max ~1500×1500 px per plaat (≈ 9 MB ARGB_8888, of RGB_565 voor de vullagen ≈ 4,5 MB); totaal app-geheugen < 200 MB.
- **Hit-testing** via ID-map (O(1) lookup), niet via Path.contains per regio.
- **Nummers**: als tekst-atlas/voorgerenderd per regio bij eerste draw; bij uitzoomen onder een drempel verdwijnen nummers (minder overdraw + rustiger beeld).
- **120 Hz**: Compose + Choreographer; render-thread nooit blokkeren > 8 ms; genereren van platen altijd off-main-thread.
- **Meting**: Android Studio Profiler + **Perfetto** (frame-timing) op de A54; budget: p95 frame time < 16 ms tijdens kleuren, cold start < 2 s, plaat openen < 500 ms.

## 7. Content: Europese landschappen

- Start met **12–15 platen** in 3 packs: bijv. *Alpen & Meren*, *Mediterraan* (Cinque Terre, Provence-lavendel, Griekse eilanden), *Noord & West* (Noorse fjorden, Schotse hooglanden, Ierse kliffen, Toscane).
- Bronnen: eigen foto's, of stock met duidelijke licentie (Unsplash/Pexels-licentie controleren), of aangekochte foto's. Geen AI-gegenereerde beelden tenzij bewust gekozen.
- Elke plaat: origineel fotomateriaal ≥ 3000 px, goede vlakverdeling (lucht/water/bergen als rustige grote vlakken + middengebied met detail) — past bij de ontspannings-doelstelling.
- Extra packs later via downloadbaar content (bijv. remote pack-manifest + download), zodat de APK klein blijft (doel: < 40 MB met startcontent).

## 8. Mijlpalen

| Fase | Inhoud | Resultaat |
|---|---|---|
| **0 – Setup** (± 3 d) | Project scaffold, Compose, CI (build + tests), lint, basisthema | Draaiende lege app op Nord 4 |
| **1 – Render-prototype** (± 1,5 w) | Plaatformaat-decoder, Canvas-render-engine, bitmap-laag, zoom/pan, tikken-vullen, undo; één hardcoded testplaat | Speelbare plaat, 60 fps op A54 |
| **2 – Pijplijn offline + content** (± 2 w) | Generator-CLI, eerste 6 platen, QA-proces | Echte landschappen speelbaar |
| **3 – App-UI** (± 2 w) | Bibliotheek, voortgang (Room), instellingen, voltooien/delen, hints | Complete kern-app |
| **4 – Eigen foto's** (± 1,5 w) | On-device segmentatie, Photo Picker-flow, "Mijn foto's" | Foto → kleurplaat werkt op A54 |
| **5 – Polish** (± 1,5 w) | Geluid/haptics, kleurblind-modus, donkere modus, reveal-animatie, lege-state/onboarding | Release-kandidaat |
| **6 – Optimalisatie & release** (± 1 w) | Perfetto-profilering op A54, geheugen-checks, Play Console (closed test → productie), privacybeleid | Publicatie |

Totaal: ~9–10 weken (part-time langer).

## 9. Teststrategie

- **Unit**: kwantisatie/merging, plaatformaat encode/decode, voortgang/undo-logica.
- **Instrumented (Compose UI tests)**: tik → regio vult, paletselectie, zoom.
- **Device-matrix**: OnePlus Nord 4 (daily driver), Samsung A54 (performance-gate); optioneel Firebase Test Lab voor breedte.
- **Performance-gates per release**: p95 frame < 16 ms, geen ANR's, geheugen < 200 MB, cold start < 2 s — gemeten op de A54.
- **Content-QA**: elke plaat handmatig deels inkleuren; controleren op mini-regio's, onleesbare nummers, kleur-ambiguïteit (twee bijna identieke kleuren in palet verbieden: ΔE-minimum tussen paletkleuren).

## 10. Risico's & mitigatie

| Risico | Mitigatie |
|---|---|
| Canvas-performance onvoldoende op Mali-G68 (A54) bij grote platen | Bitmap-laag + dirty rects (fase 1 direct meten op A54); noodgeval: OpenGL ES-renderpad achter dezelfde interface |
| Segmentatie lelijke resultaten bij complexe gebruikersfoto's | Voorbeeld + regenereren met grovere instelling; vaste difficulty-bandbreedte afdwingen; kleine-regio-merge |
| Geheugen bij diepe zoom | Getegelde lagen, LRU met max 2 zoomniveaus, RGB_565 voor vullagen |
| Content-licenties | Alleen eigen/licentievrije foto's; bronadministratie per plaat bijhouden |
| On-device generatie blokkeert UI | Volledig op Default-dispatcher met voortgangs-UI en annuleermogelijkheid |

## 11. Buiten scope (v1)

- Social features, leaderboards, dagelijkse uitdagingen.
- Monetisatie (IAP/abonnement) — architectuur houdt er wel rekening mee (packs zijn al gescheiden eenheden).
- iOS-versie.
- Cloud-sync van voortgang (kan later via account + backup).

## 12. Eerste concrete stappen

1. Android-project aanmaken (Kotlin, Compose, minSdk 26) + CI-workflow.
2. Plaatformaat v1 specificeren en decoder bouwen met unit tests.
3. Render-prototype: hardcoded testplaat (simple landschap) met vullen/zoom/undo → direct profileren op A54.
4. Generator-CLI opzetten en eerste 3 landschapsplaten produceren.
