package nl.kleuren.app.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.kleuren.app.R
import nl.kleuren.app.data.AppContainer
import nl.kleuren.app.data.PlateRepository
import nl.kleuren.app.data.PlateSummary
import nl.kleuren.app.ui.components.DifficultyPicker
import nl.kleuren.pipeline.Difficulty
import kotlin.math.roundToInt

/**
 * Het startscherm: een rustige bibliotheek met drie tabbladen.
 *
 * De UI is bewust een "domme" laag: hij toont [LibraryUiState] en stuurt
 * gebruikersacties door naar de ViewModel. Alle logica (voortgang, sorteren,
 * verwijderen, opnieuw genereren) zit in de ViewModel en repositories.
 */
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenPlate: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddPhoto: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // De header regelt zelf de statusbalk. Zo voorkomen we dubbele
        // systeem-insets en houdt de grid genoeg ruimte onderaan voor de FAB.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LibraryHeader(
                selectedTab = state.tab,
                onSelectTab = viewModel::selectTab,
                onOpenSettings = onOpenSettings,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPhoto,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Foto toevoegen") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::refresh,
                )
                else -> when (state.tab) {
                    LibraryTab.LANDSCAPES -> LandscapesTab(
                        packs = state.packs,
                        plateRepository = container.plateRepository,
                        onOpenPlate = onOpenPlate,
                        onGoToPhotos = onAddPhoto,
                    )
                    LibraryTab.CONTINUE -> ContinueTab(
                        items = state.continueItems,
                        plateRepository = container.plateRepository,
                        onOpenPlate = onOpenPlate,
                        onBrowseLandscapes = { viewModel.selectTab(LibraryTab.LANDSCAPES) },
                    )
                    LibraryTab.MY_PHOTOS -> MyPhotosTab(
                        items = state.myPhotos,
                        plateRepository = container.plateRepository,
                        defaultDifficulty = settings.difficulty,
                        onOpenPlate = onOpenPlate,
                        onAddPhoto = onAddPhoto,
                        onDelete = viewModel::deleteUserPlate,
                        onRegenerate = viewModel::regenerateUserPlate,
                    )
                }
            }
        }
    }

    if (state.regeneratingPlateId != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Foto opnieuw maken") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { state.regenerationProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "${state.regenerationStage} " +
                            "${(state.regenerationProgress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun LibraryHeader(
    selectedTab: LibraryTab,
    onSelectTab: (LibraryTab) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(top = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Kleuren",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Kleur op je eigen tempo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Instellingen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Op smalle toestellen (zoals de Galaxy A54, 384 dp) passen drie
        // tabs met icoon én tekst niet bij een grotere systeemletter. We laten
        // de iconen dan weg, zodat de volledige labels leesbaar blijven.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val showIcons = maxWidth >= 400.dp
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
                        icon = if (showIcons) {
                            {
                                Icon(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

private val LibraryTab.iconRes: Int
    get() = when (this) {
        LibraryTab.LANDSCAPES -> R.drawable.ic_landscape
        LibraryTab.CONTINUE -> R.drawable.ic_continue
        LibraryTab.MY_PHOTOS -> R.drawable.ic_photos
    }

// ---------------------------------------------------------------------------
// Tabbladen
// ---------------------------------------------------------------------------

@Composable
private fun LandscapesTab(
    packs: List<PackUi>,
    plateRepository: PlateRepository,
    onOpenPlate: (String) -> Unit,
    onGoToPhotos: () -> Unit,
) {
    if (packs.isEmpty()) {
        EmptyState(
            painter = painterResource(R.drawable.ic_landscape),
            title = "Nog geen landschappen",
            body = "De meegeleverde platen zijn niet gevonden. " +
                "Genereer de content met de generator-CLI en bouw de app opnieuw.",
            actionLabel = "Foto toevoegen",
            onAction = onGoToPhotos,
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        packs.forEach { pack ->
            item(
                key = "pack-${pack.id}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Column(modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                    Text(
                        text = pack.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (pack.description.isNotBlank()) {
                        Text(
                            text = pack.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(
                items = pack.plates,
                key = { it.summary.id },
            ) { item ->
                PlateCard(
                    item = item,
                    plateRepository = plateRepository,
                    onClick = { onOpenPlate(item.summary.id) },
                )
            }
        }
    }
}

@Composable
private fun ContinueTab(
    items: List<PlateItem>,
    plateRepository: PlateRepository,
    onOpenPlate: (String) -> Unit,
    onBrowseLandscapes: () -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(
            painter = painterResource(R.drawable.ic_continue),
            title = "Nog niets onderweg",
            body = "Plaatsen waar je al aan begon, verschijnen hier. " +
                "Kies een landschap om te beginnen.",
            actionLabel = "Bekijk landschappen",
            onAction = onBrowseLandscapes,
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Verder gaan",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(items, key = { it.summary.id }) { item ->
            ContinueCard(
                item = item,
                plateRepository = plateRepository,
                onClick = { onOpenPlate(item.summary.id) },
            )
        }
    }
}

@Composable
private fun MyPhotosTab(
    items: List<PlateItem>,
    plateRepository: PlateRepository,
    defaultDifficulty: Difficulty,
    onOpenPlate: (String) -> Unit,
    onAddPhoto: () -> Unit,
    onDelete: (String) -> Unit,
    onRegenerate: (String, Difficulty) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(
            painter = painterResource(R.drawable.ic_photos),
            title = "Nog geen eigen foto's",
            body = "Kies een foto van je toestel. De app maakt er een rustige " +
                "kleurplaat van. Je foto blijft lokaal op dit toestel.",
            actionLabel = "Foto toevoegen",
            onAction = onAddPhoto,
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    text = "Mijn foto's",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Lokaal op dit toestel. Via het menu op een kaart pas je de " +
                        "moeilijkheid aan of verwijder je de foto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(items, key = { it.summary.id }) { item ->
            PlateCard(
                item = item,
                plateRepository = plateRepository,
                defaultDifficulty = defaultDifficulty,
                onClick = { onOpenPlate(item.summary.id) },
                onDelete = { onDelete(item.summary.id) },
                onRegenerate = { difficulty -> onRegenerate(item.summary.id, difficulty) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Kaarten
// ---------------------------------------------------------------------------

@Composable
private fun PlateCard(
    item: PlateItem,
    plateRepository: PlateRepository,
    defaultDifficulty: Difficulty = Difficulty.DEFAULT,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onRegenerate: ((Difficulty) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showDifficultyDialog by remember { mutableStateOf(false) }
    var chosenDifficulty by remember(item.summary.id) {
        mutableStateOf(item.summary.difficulty ?: defaultDifficulty)
    }

    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            PlateThumbnail(
                summary = item.summary,
                plateRepository = plateRepository,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            )

            if (item.complete) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Voltooid",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(3.dp).size(20.dp),
                    )
                }
            }

            if (onDelete != null) {
                Box(modifier = Modifier.align(Alignment.TopStart)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        modifier = Modifier.padding(6.dp),
                    ) {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Opties voor ${item.summary.title}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onRegenerate != null) {
                            DropdownMenuItem(
                                text = { Text("Moeilijkheid aanpassen") },
                                onClick = {
                                    menuOpen = false
                                    chosenDifficulty = item.summary.difficulty ?: defaultDifficulty
                                    showDifficultyDialog = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Verwijderen") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.summary.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.summary.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            ProgressLine(progress = item.progress, complete = item.complete)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Foto verwijderen?") },
            text = {
                Text("De kleurplaat en je voortgang worden van dit toestel verwijderd.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete?.invoke()
                    },
                ) { Text("Verwijderen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annuleren") }
            },
        )
    }

    if (showDifficultyDialog && onRegenerate != null) {
        AlertDialog(
            onDismissRequest = { showDifficultyDialog = false },
            title = { Text("Moeilijkheid aanpassen") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "De foto wordt opnieuw gemaakt met de gekozen " +
                            "moeilijkheid. Je voortgang van deze foto wordt gewist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    DifficultyPicker(
                        selected = chosenDifficulty,
                        onSelect = { chosenDifficulty = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDifficultyDialog = false
                        onRegenerate(chosenDifficulty)
                    },
                ) { Text("Opnieuw maken") }
            },
            dismissButton = {
                TextButton(onClick = { showDifficultyDialog = false }) { Text("Annuleren") }
            },
        )
    }
}

@Composable
private fun ContinueCard(
    item: PlateItem,
    plateRepository: PlateRepository,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlateThumbnail(
                summary = item.summary,
                plateRepository = plateRepository,
                modifier = Modifier
                    .width(122.dp)
                    .height(92.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.summary.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                ProgressLine(progress = item.progress, complete = item.complete)
            }
        }
    }
}

@Composable
private fun ProgressLine(progress: Float, complete: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape),
            color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (complete) "Klaar" else "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Laadt de preview-JPEG uit de repository. `produceState` start een coroutine
 * die stopt zodra de composable uit de compositie verdwijnt.
 */
@Composable
fun PlateThumbnail(
    summary: PlateSummary,
    plateRepository: PlateRepository,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, summary.id) {
        value = plateRepository.preview(summary)
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = summary.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_landscape),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Laden, fouten en lege schermen
// ---------------------------------------------------------------------------

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Bibliotheek laden…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Er ging iets mis",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onRetry) { Text("Opnieuw proberen") }
        }
    }
}

@Composable
private fun EmptyState(
    painter: Painter,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(92.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
