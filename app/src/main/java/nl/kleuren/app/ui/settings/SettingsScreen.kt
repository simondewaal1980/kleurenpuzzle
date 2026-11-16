package nl.kleuren.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.kleuren.app.data.AppContainer
import nl.kleuren.app.data.ThemeMode
import nl.kleuren.app.ui.components.DifficultyPicker

private enum class ConfirmAction { PROGRESS, PHOTOS, EVERYTHING }

/**
 * Instellingen: weergave, interactie en gegevensbeheer.
 *
 * Elke schakelaar schrijft direct naar [SettingsRepository]; er is geen
 * "opslaan"-knop nodig. Destructieve acties vragen eerst om bevestiging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by container.plateRepository.state.collectAsStateWithLifecycle()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    var showCredits by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Instellingen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsSection(title = "Weergave") {
                Text(
                    text = "Thema",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SwitchRow(
                    title = "Nummers tonen",
                    description = "Toon het kleurnummer in elk vlak. Uit = rustiger beeld.",
                    checked = state.settings.showNumbers,
                    onCheckedChange = viewModel::setShowNumbers,
                )
                SwitchRow(
                    title = "Hint-modus",
                    description = "Licht alle vlakken van de gekozen kleur zachtjes op.",
                    checked = state.settings.hintsEnabled,
                    onCheckedChange = viewModel::setHints,
                )
                SwitchRow(
                    title = "Kleurblind-modus",
                    description = "Voegt een vorm toe aan elk kleurnummer in het palet.",
                    checked = state.settings.colorBlindMode,
                    onCheckedChange = viewModel::setColorBlind,
                )
            }

            Spacer(Modifier.height(20.dp))

            SettingsSection(title = "Moeilijkheidsgraad") {
                Text(
                    text = "Standaard voor nieuwe kleurplaten van je eigen foto's. " +
                        "Bestaande eigen foto's pas je aan via het menu op de kaart. " +
                        "De meegeleverde landschappen houden hun vaste moeilijkheid.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                DifficultyPicker(
                    selected = state.settings.difficulty,
                    onSelect = viewModel::setDifficulty,
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(20.dp))

            SettingsSection(title = "Interactie") {
                SwitchRow(
                    title = "Trilfeedback",
                    description = "Een zachte tik bij het inkleuren en bij een fout.",
                    checked = state.settings.hapticsEnabled,
                    onCheckedChange = viewModel::setHaptics,
                )
                SwitchRow(
                    title = "Magisch vullen",
                    description = "Houd een kleur in het palet ingedrukt om alle bijbehorende " +
                        "vlakken in één keer te vullen.",
                    checked = state.settings.magicFillEnabled,
                    onCheckedChange = viewModel::setMagic,
                )
            }

            Spacer(Modifier.height(20.dp))

            SettingsSection(title = "Gegevens") {
                Text(
                    text = "Voortgang wordt lokaal op dit toestel bewaard. " +
                        "Er is geen account en er wordt niets geüpload.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                OutlinedButton(
                    onClick = { confirmAction = ConfirmAction.PROGRESS },
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) { Text("Voortgang wissen") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmAction = ConfirmAction.PHOTOS },
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) { Text("Mijn foto's verwijderen") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmAction = ConfirmAction.EVERYTHING },
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 0.dp),
                ) { Text("Alle gegevens wissen") }
                Spacer(Modifier.height(12.dp))
                if (state.busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("Bezig…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            SettingsSection(title = "Over") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Kleuren 0.1.0",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Een rustige paint-by-numbers-app met eigen foto's en " +
                            "Europese landschappen. Gebouwd met Kotlin en Jetpack Compose.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { showCredits = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("Fotocredits")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    confirmAction?.let { action ->
        val (title, body) = when (action) {
            ConfirmAction.PROGRESS -> "Voortgang wissen?" to
                "Alle ingekleurde vlakken worden leeggemaakt. Je eigen foto's blijven bestaan."
            ConfirmAction.PHOTOS -> "Mijn foto's verwijderen?" to
                "Alle zelfgemaakte kleurplaten en hun voortgang worden verwijderd. " +
                "De meegeleverde landschappen blijven staan."
            ConfirmAction.EVERYTHING -> "Alles wissen?" to
                "Zowel de voortgang als je eigen foto's worden verwijderd. Dit kan niet ongedaan " +
                    "worden gemaakt."
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAction = null
                        when (action) {
                            ConfirmAction.PROGRESS -> viewModel.clearProgress()
                            ConfirmAction.PHOTOS -> viewModel.clearMyPhotos()
                            ConfirmAction.EVERYTHING -> viewModel.clearEverything()
                        }
                    },
                ) { Text("Verwijderen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Annuleren") }
            },
        )
    }

    if (showCredits) {
        val credits = library.packs
            .flatMap { it.plates }
            .filter { it.credit.isNotBlank() && !it.isUserPlate }
        AlertDialog(
            onDismissRequest = { showCredits = false },
            title = { Text("Fotocredits") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Alle bronfoto's zijn CC0 (publiek domein), via Wikimedia Commons.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    credits.forEach { plate ->
                        Text(
                            text = plate.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${plate.credit} · ${plate.license}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (plate.sourceUrl.isNotBlank()) {
                            Text(
                                text = plate.sourceUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF557485),
                                modifier = Modifier.clickable {
                                    runCatching { uriHandler.openUri(plate.sourceUrl) }
                                },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCredits = false }) { Text("Sluiten") }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "Systeem"
        ThemeMode.LIGHT -> "Licht"
        ThemeMode.DARK -> "Donker"
    }
