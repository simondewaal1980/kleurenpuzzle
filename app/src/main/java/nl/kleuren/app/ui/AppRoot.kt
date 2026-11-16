package nl.kleuren.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import nl.kleuren.app.data.AppContainer
import nl.kleuren.app.ui.board.BoardScreen
import nl.kleuren.app.ui.library.LibraryScreen
import nl.kleuren.app.ui.photo.PhotoImportScreen
import nl.kleuren.app.ui.settings.SettingsScreen

/**
 * Navigatie tussen de vier schermen.
 *
 * We gebruiken bewust een kleine, zelfgemaakte stack in plaats van een
 * navigatiebibliotheek. Voor vier schermen is dat transparant en leerzaam:
 * je ziet precies wat "terug" doet. De routes worden als één string bewaard,
 * zodat ze een schermdraai en procesherstel overleven.
 */
@Composable
fun AppRoot(container: AppContainer) {
    var routesCsv by rememberSaveable { mutableStateOf(ROUTE_LIBRARY) }
    val routes = routesCsv.split(ROUTE_SEPARATOR).filter { it.isNotBlank() }
    val current = routes.lastOrNull() ?: ROUTE_LIBRARY

    fun replaceStack(newRoutes: List<String>) {
        routesCsv = newRoutes.joinToString(ROUTE_SEPARATOR)
    }

    fun navigate(route: String) {
        replaceStack(routes + route)
    }

    fun goBack() {
        if (routes.size > 1) replaceStack(routes.dropLast(1))
    }

    BackHandler(enabled = routes.size > 1) { goBack() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            current == ROUTE_LIBRARY -> LibraryScreen(
                container = container,
                onOpenPlate = { plateId -> navigate("board/$plateId") },
                onOpenSettings = { navigate(ROUTE_SETTINGS) },
                onAddPhoto = { navigate(ROUTE_IMPORT) },
            )

            current == ROUTE_SETTINGS -> SettingsScreen(
                container = container,
                onBack = ::goBack,
            )

            current == ROUTE_IMPORT -> PhotoImportScreen(
                container = container,
                onBack = ::goBack,
                onGenerated = { plateId ->
                    // Vervang het importscherm door het bord: "terug" gaat
                    // daarna direct naar de bibliotheek, niet naar het formulier.
                    replaceStack(routes.dropLast(1) + "board/$plateId")
                },
            )

            current.startsWith("board/") -> {
                val plateId = current.removePrefix("board/")
                // key() zorgt ervoor dat zoom/pan en het bord zelf opnieuw
                // worden opgebouwd als je een andere plaat opent.
                key(plateId) {
                    BoardScreen(
                        container = container,
                        plateId = plateId,
                        onBack = ::goBack,
                    )
                }
            }

            else -> LibraryScreen(
                container = container,
                onOpenPlate = { plateId -> navigate("board/$plateId") },
                onOpenSettings = { navigate(ROUTE_SETTINGS) },
                onAddPhoto = { navigate(ROUTE_IMPORT) },
            )
        }
    }
}

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_IMPORT = "import"

// Een control-teken dat nooit in een route voorkomt, als scheidingsteken.
private const val ROUTE_SEPARATOR = "\u001F"
