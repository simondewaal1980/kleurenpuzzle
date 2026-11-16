package nl.kleuren.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test: start de echte app en open een kleurplaat.
 *
 * Draaien op een emulator/toestel:
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * De test wacht tot de bibliotheek asynchroon is geladen. Daarna opent hij de
 * eerste plaat en controleert of het bord en de paletbalk zichtbaar zijn.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /**
     * Maak de test onafhankelijk van wat er al op het toestel staat. Zonder
     * deze stap kan een eerdere handmatige speelsessie de test beïnvloeden
     * (bijvoorbeeld doordat kleur 1 al klaar is).
     */
    @Before
    fun resetAppData() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<KleurenApp>()
        app.container.progressRepository.clearAll()
        app.container.plateRepository.clearUserPlates()
    }

    @Test
    fun bibliotheekOpentEnPlaatIsSpeelbaar() {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Lautersee").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithText("Lautersee").performClick()

        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTag("boardCanvas").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithTag("boardCanvas").assertIsDisplayed()
        rule.onNodeWithText("vlakken te gaan", substring = true).assertIsDisplayed()
    }

    @Test
    fun inzoomenMetTweeVingersToontZoomIndicator() {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Lautersee").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Lautersee").performClick()

        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTag("boardCanvas").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithTag("boardCanvas").performTouchInput {
            val c = center
            val start0 = c + Offset(-40f, 0f)
            val end0 = c + Offset(-320f, 0f)
            val start1 = c + Offset(40f, 0f)
            val end1 = c + Offset(320f, 0f)

            // Twee vingers neerzetten en langzaam uit elkaar bewegen.
            down(0, start0)
            down(1, start1)
            repeat(12) { step ->
                val t = (step + 1) / 12f
                moveTo(0, start0 + (end0 - start0) * t)
                moveTo(1, start1 + (end1 - start1) * t)
                advanceEventTime(16)
            }
            up(0)
            up(1)
        }

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("zoomIndicator").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("zoomIndicator").assertIsDisplayed()

        // Eén tik op de indicator past de plaat weer op het scherm.
        rule.onNodeWithTag("zoomIndicator").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("zoomIndicator").fetchSemanticsNodes().isEmpty()
        }
    }
}
