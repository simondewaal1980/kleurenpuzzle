package nl.kleuren.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * De index van eigen foto's is een json-bestand op het toestel. Deze tests
 * bewaken dat een index van vóór de moeilijkheidsgraad-functie (zonder
 * bronbestand en niveau) zonder problemen blijft werken.
 */
class UserPlateIndexTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `oude index zonder sourceFile en difficulty blijft leesbaar`() {
        val old = """
            {
              "version": 1,
              "plates": [
                {
                  "id": "user-1",
                  "title": "Mijn foto",
                  "plateFile": "user-1.kplate",
                  "previewFile": "user-1.jpg",
                  "regionCount": 300,
                  "paletteSize": 14,
                  "createdAt": 123
                }
              ]
            }
        """.trimIndent()

        val entry = json.decodeFromString<UserPlateIndex>(old).plates.single()

        assertEquals("", entry.sourceFile)
        assertEquals("", entry.difficulty)
        assertEquals(0L, entry.updatedAt)
    }

    @Test
    fun `sourceFile en difficulty overleven opslaan en laden`() {
        val index = UserPlateIndex(
            plates = listOf(
                UserPlateEntry(
                    id = "user-2",
                    title = "Mijn foto",
                    plateFile = "user-2.kplate",
                    previewFile = "user-2.jpg",
                    regionCount = 420,
                    paletteSize = 17,
                    createdAt = 456,
                    sourceFile = "user-2-source.jpg",
                    difficulty = "HARD",
                    updatedAt = 789,
                ),
            ),
        )

        val encoded = json.encodeToString(UserPlateIndex.serializer(), index)
        val decoded = json.decodeFromString<UserPlateIndex>(encoded)

        assertEquals("user-2-source.jpg", decoded.plates.single().sourceFile)
        assertEquals("HARD", decoded.plates.single().difficulty)
        assertEquals(789L, decoded.plates.single().updatedAt)
    }
}
