package nl.kleuren.app.ui.components

import nl.kleuren.pipeline.ConversionStage

/** Nederlandse labels voor de fases van de fotopijplijn. */
fun ConversionStage.stageLabel(): String = when (this) {
    ConversionStage.PREPARING -> "Foto voorbereiden…"
    ConversionStage.SMOOTHING -> "Ruis dempen…"
    ConversionStage.QUANTIZING -> "Kleuren kiezen…"
    ConversionStage.SEGMENTING -> "Vlakken zoeken…"
    ConversionStage.TRACING -> "Contouren tekenen…"
    ConversionStage.BUILDING -> "Plaat opbouwen…"
    ConversionStage.DONE -> "Klaar"
}
