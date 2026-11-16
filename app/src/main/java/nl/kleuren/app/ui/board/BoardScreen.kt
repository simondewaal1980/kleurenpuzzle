package nl.kleuren.app.ui.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.kleuren.app.R
import nl.kleuren.app.data.AppContainer
import nl.kleuren.format.Plate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Het hart van de app: het kleurbord.
 *
 * Opbouw van boven naar beneden:
 *  - topbalk met voortgang en acties (undo, hint, magisch vullen, menu);
 *  - het tekenvlak (zoom/pan/tikken);
 *  - de paletbalk met alle kleuren en hun resterende vlakken.
 *
 * Bij voltooiing faden de lijnen en nummers rustig weg en verschijnt een
 * kaart met "delen". Er is geen timer en geen straf: een verkeerde kleur geeft
 * alleen een vriendelijke hint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    container: AppContainer,
    plateId: String,
    onBack: () -> Unit,
    viewModel: BoardViewModel = viewModel(
        key = "board-$plateId",
        factory = BoardViewModel.factory(container, plateId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewport = remember(state.plateId) { BoardViewport() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Meldingen (verkeerde kleur, undo, ...) verschijnen als snackbar en
    // worden daarna uit de state gehaald, zodat ze niet opnieuw verschijnen.
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    // De reveal-animatie: lijnen en nummers verdwijnen in 1,6 seconde.
    val reveal by animateFloatAsState(
        targetValue = if (state.completed) 0f else 1f,
        animationSpec = tween(durationMillis = if (state.completed) 1600 else 0),
        label = "reveal",
    )

    var showCompletionCard by remember { mutableStateOf(false) }
    LaunchedEffect(state.completed) {
        if (state.completed) {
            delay(1500)
            showCompletionCard = true
        } else {
            showCompletionCard = false
        }
    }

    fun hapticFor(outcome: FillOutcome) {
        if (!state.hapticsEnabled) return
        when (outcome) {
            FillOutcome.FILLED -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            FillOutcome.COMPLETED -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            FillOutcome.WRONG_COLOR,
            FillOutcome.ALREADY_FILLED,
            FillOutcome.NO_PLATE,
            -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun share() {
        val plate = state.plate ?: return
        scope.launch {
            val ok = sharePlate(context, plate, state.filled, state.title)
            if (!ok) snackbarHostState.showSnackbar("Delen is niet gelukt.")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BoardTopBar(
                title = state.title,
                subtitle = if (state.plate == null) {
                    state.subtitle
                } else {
                    "${state.filledCount} van ${state.totalRegions} · " +
                        "${(state.progress * 100).roundToInt()}%"
                },
                progress = state.progress,
                undoEnabled = state.undoDepth > 0,
                magicEnabled = state.magicFillEnabled && !state.completed,
                hintsEnabled = state.hintsEnabled,
                showNumbers = state.showNumbers,
                completed = state.completed,
                onBack = onBack,
                onUndo = {
                    if (viewModel.undo() && state.hapticsEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onMagic = {
                    val count = viewModel.magicFill()
                    if (count > 0 && state.hapticsEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onToggleHints = viewModel::toggleHints,
                onToggleNumbers = viewModel::toggleNumbers,
                onShare = ::share,
            )
        },
        bottomBar = {
            val plate = state.plate
            if (plate != null && !state.completed) {
                PaletteBar(
                    plate = plate,
                    filled = state.filled,
                    selectedColor = state.selectedColor,
                    colorBlindMode = state.colorBlindMode,
                    magicEnabled = state.magicFillEnabled,
                    onSelectColor = viewModel::selectColor,
                    onMagicFill = { colorIndex ->
                        viewModel.selectColor(colorIndex)
                        val count = viewModel.magicFill()
                        if (count > 0 && state.hapticsEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> BoardError(
                    message = state.error.orEmpty(),
                    onBack = onBack,
                )
                state.plate != null -> {
                    val plate = state.plate!!
                    BoardCanvas(
                        plate = plate,
                        filled = state.filled,
                        selectedColor = state.selectedColor,
                        showNumbers = state.showNumbers,
                        hintsEnabled = state.hintsEnabled,
                        revealProgress = reveal,
                        viewport = viewport,
                        onTapRegion = { regionId ->
                            hapticFor(viewModel.fill(regionId))
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("boardCanvas")
                            .semantics {
                                contentDescription =
                                    "Kleurbord. Tik op een genummerd vlak om het te kleuren."
                            },
                    )

                    // Handige uitweg als je per ongeluk ver hebt ingezoomd.
                    AnimatedVisibility(
                        visible = viewport.zoom > 1.05f,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                    ) {
                        Surface(
                            onClick = viewport::reset,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 3.dp,
                            modifier = Modifier
                                .testTag("zoomIndicator")
                                .semantics {
                                    contentDescription =
                                        "Ingezoomd. Tik om de hele plaat te tonen."
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = String.format(
                                        Locale.getDefault(),
                                        "%.1f×",
                                        viewport.zoom,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Pas aan",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // + en − voor toegankelijk zoomniveau; pinch werkt ook.
                    val plateForZoom = state.plate
                    if (plateForZoom != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 3.dp,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 10.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { viewport.zoomBy(1.45f, plateForZoom) },
                                    enabled = viewport.zoom < 5.99f,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .testTag("zoomIn")
                                        .semantics { contentDescription = "Inzoomen" },
                                ) {
                                    Text(
                                        text = "+",
                                        fontSize = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.width(22.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                )
                                IconButton(
                                    onClick = { viewport.zoomBy(1f / 1.45f, plateForZoom) },
                                    enabled = viewport.zoom > 1.01f,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .testTag("zoomOut")
                                        .semantics { contentDescription = "Uitzoomen" },
                                ) {
                                    Text(
                                        text = "−",
                                        fontSize = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showCompletionCard,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(20.dp),
                    ) {
                        CompletionCard(
                            title = "Mooi gedaan",
                            onShare = ::share,
                            onBack = onBack,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardTopBar(
    title: String,
    subtitle: String,
    progress: Float,
    undoEnabled: Boolean,
    magicEnabled: Boolean,
    hintsEnabled: Boolean,
    showNumbers: Boolean,
    completed: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onMagic: () -> Unit,
    onToggleHints: () -> Unit,
    onToggleNumbers: () -> Unit,
    onShare: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = title.ifBlank { "Kleuren" },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                }
            },
            actions = {
                IconButton(onClick = onUndo, enabled = undoEnabled) {
                    Icon(
                        painter = painterResource(R.drawable.ic_undo),
                        contentDescription = "Ongedaan maken",
                        tint = if (undoEnabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        },
                    )
                }
                IconButton(onClick = onMagic, enabled = magicEnabled) {
                    Icon(
                        painter = painterResource(R.drawable.ic_magic),
                        contentDescription = "Alle vlakken van deze kleur vullen",
                        tint = if (magicEnabled) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        },
                    )
                }
                IconButton(onClick = onToggleHints) {
                    Icon(
                        painter = painterResource(R.drawable.ic_hint),
                        contentDescription = if (hintsEnabled) "Hint uitzetten" else "Hint aanzetten",
                        tint = if (hintsEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Meer opties")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(if (showNumbers) "Nummers verbergen" else "Nummers tonen")
                            },
                            onClick = {
                                onToggleNumbers()
                                menuOpen = false
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delen als PNG") },
                            enabled = completed,
                            onClick = {
                                menuOpen = false
                                onShare()
                            },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
    }
}

// ---------------------------------------------------------------------------
// Paletbalk
// ---------------------------------------------------------------------------

@Composable
private fun PaletteBar(
    plate: Plate,
    filled: Set<Int>,
    selectedColor: Int,
    colorBlindMode: Boolean,
    magicEnabled: Boolean,
    onSelectColor: (Int) -> Unit,
    onMagicFill: (Int) -> Unit,
) {
    val remainingPerColor = remember(plate, filled) {
        val counts = IntArray(plate.palette.size)
        for (region in plate.regions) {
            if (!filled.contains(region.id)) counts[region.colorIndex]++
        }
        counts
    }
    val selectedRemaining = remainingPerColor.getOrElse(selectedColor) { 0 }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(plate.palette[selectedColor]))
                        .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            append("Kleur ${selectedColor + 1} van ${plate.palette.size}")
                            if (colorBlindMode) append("  ${colorSymbol(selectedColor)}")
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (selectedRemaining == 0) {
                            "Deze kleur is klaar"
                        } else {
                            "$selectedRemaining ${if (selectedRemaining == 1) "vlak" else "vlakken"} te gaan"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (magicEnabled && selectedRemaining > 1) {
                    TextButton(onClick = { onMagicFill(selectedColor) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_magic),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Alles")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(plate.palette.indices.toList()) { index ->
                    PaletteSwatch(
                        colorIndex = index,
                        color = Color(plate.palette[index]),
                        remaining = remainingPerColor[index],
                        selected = index == selectedColor,
                        colorBlindMode = colorBlindMode,
                        onClick = { onSelectColor(index) },
                        onLongClick = { if (magicEnabled) onMagicFill(index) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteSwatch(
    colorIndex: Int,
    color: Color,
    remaining: Int,
    selected: Boolean,
    colorBlindMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val darkText = color.luminance() > 0.55f
    val textColor = if (darkText) Color(0xFF1F231D) else Color.White
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .semantics {
                contentDescription = "Kleur ${colorIndex + 1}, " +
                    if (remaining == 0) "klaar" else "$remaining vlakken over"
            },
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(color)
                .border(if (selected) 3.dp else 1.dp, borderColor, CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${colorIndex + 1}",
                color = textColor,
                fontSize = 18.sp,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
            if (colorBlindMode) {
                Text(
                    text = colorSymbol(colorIndex),
                    color = textColor.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                )
            }
            if (remaining == 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (remaining == 0) "klaar" else "$remaining",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Kleurblind-modus: elk kleurnummer krijgt een vorm als tweede codering.
 * Nummers zijn al het belangrijkste hulpmiddel; deze symbolen helpen daarnaast
 * bij het onderscheiden van kleuren in het palet.
 */
private fun colorSymbol(index: Int): String {
    val symbols = listOf("●", "▲", "■", "◆", "★", "✚", "◐", "▼", "✦", "◉", "▣", "✱")
    return symbols[index % symbols.size]
}

// ---------------------------------------------------------------------------
// Voltooiing en fouten
// ---------------------------------------------------------------------------

@Composable
private fun CompletionCard(
    title: String,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "De lijnen en nummers zijn verdwenen. Je kleurplaat is klaar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onShare) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("Delen als PNG")
                }
                TextButton(onClick = onBack) { Text("Terug") }
            }
        }
    }
}

@Composable
private fun BoardError(message: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Deze plaat kon niet worden geopend",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onBack) { Text("Terug naar de bibliotheek") }
        }
    }
}
