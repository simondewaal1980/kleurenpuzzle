package nl.kleuren.app.ui.photo

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.kleuren.app.R
import nl.kleuren.app.data.AppContainer
import nl.kleuren.app.ui.components.DifficultyPicker
import kotlin.math.roundToInt

/**
 * Scherm om een eigen foto toe te voegen.
 *
 * De flow is bewust kort: foto kiezen -> eventueel titel/moeilijkheidsgraad
 * aanpassen -> genereren met voortgang -> direct het bord in. Dankzij de Photo
 * Picker zijn er geen opslag- of cameratoestemmingen nodig.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoImportScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onGenerated: (String) -> Unit,
    viewModel: PhotoImportViewModel = viewModel(factory = PhotoImportViewModel.factory(container)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.selectImage(uri)
    }

    fun pickPhoto() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(state.generatedPlateId) {
        val plateId = state.generatedPlateId
        if (plateId != null) {
            // Geef het beeld vrij voordat we naar het bord gaan.
            viewModel.clearImage()
            onGenerated(plateId)
        }
    }

    fun close() {
        if (state.generating) viewModel.cancel()
        viewModel.clearImage()
        onBack()
    }

    // Het eigen scherm wint van de navigatie-BackHandler in AppRoot.
    BackHandler(enabled = true) { close() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Foto toevoegen") },
                navigationIcon = {
                    IconButton(onClick = ::close) {
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Van foto naar kleurplaat",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Kies een landschap, dier of ander beeld met rustige vlakken. " +
                    "De app verdeelt het zelf in kleuren en genummerde vlakken.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            val bitmap = state.bitmap
            if (bitmap != null) {
                PhotoPreview(
                    bitmap = bitmap,
                    generating = state.generating,
                    progress = state.progress,
                    stage = state.stage,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.Start) {
                    TextButton(
                        onClick = { viewModel.clearImage(); pickPhoto() },
                        enabled = !state.generating,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_photos),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Andere foto")
                    }
                }
            } else {
                PickPhotoCard(
                    loading = state.loadingImage,
                    onPick = ::pickPhoto,
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(14.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            if (bitmap != null && !state.generating) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("Titel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Moeilijkheidsgraad",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "De beginwaarde komt uit de instellingen. Meer kleuren en kleinere " +
                        "vlakken geven meer detail en meer werk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                DifficultyPicker(
                    selected = state.difficulty,
                    onSelect = viewModel::setDifficulty,
                )

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::generate,
                    enabled = state.readyToGenerate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_magic),
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Maak kleurplaat")
                }
            }

            if (state.generating) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${state.stage}  ${(state.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = viewModel::cancel) { Text("Annuleren") }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Je foto wordt alleen op dit toestel verwerkt en niet geüpload. " +
                    "De kleurplaat wordt lokaal opgeslagen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PickPhotoCard(loading: Boolean, onPick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_photos),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Kies een foto",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Je opent de foto-kiezer van Android. De app krijgt alleen " +
                    "toegang tot de foto die je zelf selecteert.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onPick, enabled = !loading) {
                Text("Foto kiezen")
            }
        }
    }
}

@Composable
private fun PhotoPreview(
    bitmap: Bitmap,
    generating: Boolean,
    progress: Float,
    stage: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 340.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Gekozen foto",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (generating) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(text = stage, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${(progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
