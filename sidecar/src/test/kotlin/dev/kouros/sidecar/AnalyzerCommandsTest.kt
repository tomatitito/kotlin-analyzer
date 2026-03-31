package dev.kouros.sidecar

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerCommandsTest {

    @Test
    fun `mirrored command ids and keys match protocol contract`() {
        val contract = JsonParser.parseString(
            Files.readString(Path.of("../protocol/analyzer-commands.json"))
        ).asJsonObject
        val commands = contract.getAsJsonObject("commands")

        assertEquals(
            listOf(
                AnalyzerCommands.OPEN_TEST_TARGET,
                AnalyzerCommands.CREATE_AND_OPEN_TEST_TARGET,
            ),
            listOf(
                commands.getAsJsonObject("openTestTarget").get("id").asString,
                commands.getAsJsonObject("createAndOpenTestTarget").get("id").asString,
            ),
        )

        val openArgs = commands
            .getAsJsonObject("openTestTarget")
            .getAsJsonObject("arguments")
            .keySet()
        assertEquals(setOf(AnalyzerCommands.ARG_TARGET_URI, AnalyzerCommands.ARG_SELECTION), openArgs)

        val createArgs = commands
            .getAsJsonObject("createAndOpenTestTarget")
            .getAsJsonObject("arguments")
            .keySet()
        assertEquals(
            setOf(
                AnalyzerCommands.ARG_TARGET_URI,
                AnalyzerCommands.ARG_TARGET_PATH,
                AnalyzerCommands.ARG_INITIAL_CONTENTS,
                AnalyzerCommands.ARG_SELECTION,
            ),
            createArgs,
        )

        val selection = commands
            .getAsJsonObject("openTestTarget")
            .getAsJsonObject("arguments")
            .getAsJsonObject(AnalyzerCommands.ARG_SELECTION)
        val properties = selection.getAsJsonObject("properties")
        assertTrue(properties.has(AnalyzerCommands.ARG_START_LINE))
        assertTrue(properties.has(AnalyzerCommands.ARG_START_CHARACTER))
        assertTrue(properties.has(AnalyzerCommands.ARG_END_LINE))
        assertTrue(properties.has(AnalyzerCommands.ARG_END_CHARACTER))
    }
}
