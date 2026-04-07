package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterfaceNavigationCodeActionIntegrationTest {

    private val bridges = mutableListOf<CompilerBridge>()

    @AfterEach
    fun tearDown() {
        bridges.asReversed().forEach { it.shutdown() }
        bridges.clear()
    }

    @Test
    fun `codeActions offers one navigation action per concrete interface implementation`() {
        val workspace = Files.createTempDirectory("interface-nav-impls")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        writeSource(
            sourceRoot.resolve("com/example/Service.kt"),
            """
            package com.example

            interface Service
            """.trimIndent() + "\n",
        )
        val primaryPath = sourceRoot.resolve("com/example/PrimaryService.kt")
        writeSource(
            primaryPath,
            """
            package com.example

            class PrimaryService : Service
            """.trimIndent() + "\n",
        )
        val fallbackPath = sourceRoot.resolve("com/example/FallbackService.kt")
        writeSource(
            fallbackPath,
            """
            package com.example

            object FallbackService : Service
            """.trimIndent() + "\n",
        )
        writeSource(
            sourceRoot.resolve("com/example/BaseService.kt"),
            """
            package com.example

            abstract class BaseService : Service
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge
            .codeActions(sourceRoot.resolve("com/example/Service.kt").toUri().toString(), line = 3, character = 11)
            .getAsJsonArray("actions")
            .navigationActions()

        assertEquals(
            listOf(
                "Go to Implementation: FallbackService",
                "Go to Implementation: PrimaryService",
            ),
            actions.map { it.get("title").asString }.sorted(),
        )
        assertEquals(
            listOf(
                fallbackPath.toUri().toString(),
                primaryPath.toUri().toString(),
            ),
            actions.map { it.getAsJsonObject("command").firstArgument().get("targetUri").asString }.sorted(),
        )
    }

    @Test
    fun `codeActions offers one navigation action per directly implemented interface`() {
        val workspace = Files.createTempDirectory("interface-nav-parents")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val auditablePath = sourceRoot.resolve("com/example/Auditable.kt")
        writeSource(
            auditablePath,
            """
            package com.example

            interface Auditable
            """.trimIndent() + "\n",
        )
        val monitorablePath = sourceRoot.resolve("com/example/Monitorable.kt")
        writeSource(
            monitorablePath,
            """
            package com.example

            interface Monitorable
            """.trimIndent() + "\n",
        )
        writeSource(
            sourceRoot.resolve("com/example/Worker.kt"),
            """
            package com.example

            class Worker : Auditable, Monitorable
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge
            .codeActions(sourceRoot.resolve("com/example/Worker.kt").toUri().toString(), line = 3, character = 7)
            .getAsJsonArray("actions")
            .navigationActions()

        assertEquals(
            listOf(
                "Go to Interface: Auditable",
                "Go to Interface: Monitorable",
            ),
            actions.map { it.get("title").asString }.sorted(),
        )
        assertEquals(
            listOf(
                auditablePath.toUri().toString(),
                monitorablePath.toUri().toString(),
            ),
            actions.map { it.getAsJsonObject("command").firstArgument().get("targetUri").asString }.sorted(),
        )
    }

    @Test
    fun `codeActions skips interface navigation when no concrete target is resolved`() {
        val workspace = Files.createTempDirectory("interface-nav-none")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        writeSource(
            sourceRoot.resolve("com/example/Service.kt"),
            """
            package com.example

            interface Service
            """.trimIndent() + "\n",
        )
        writeSource(
            sourceRoot.resolve("com/example/BaseService.kt"),
            """
            package com.example

            abstract class BaseService : Service
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge
            .codeActions(sourceRoot.resolve("com/example/Service.kt").toUri().toString(), line = 3, character = 11)
            .getAsJsonArray("actions")
            .navigationActions()

        val titles = actions.map { it.get("title").asString }
        assertTrue(actions.isEmpty(), "expected no interface navigation actions, got: $titles")
    }

    private fun createBridge(workspace: Path, sourceRoot: Path): CompilerBridge {
        return CompilerBridge().also { bridge ->
            bridges += bridge
            bridge.initialize(
                projectRoot = workspace.toString(),
                classpath = emptyList(),
                compilerFlags = emptyList(),
                jdkHome = "",
                sourceRoots = listOf(sourceRoot.toString()),
            )
        }
    }

    private fun writeSource(path: Path, text: String) {
        Files.createDirectories(path.parent)
        path.writeText(text)
    }

    private fun JsonArray.navigationActions(): List<JsonObject> =
        (0 until size())
            .map { get(it).asJsonObject }
            .filter { it.getAsJsonObject("command")?.get("command")?.asString == AnalyzerCommands.OPEN_TARGET }

    private fun JsonObject.firstArgument(): JsonObject =
        getAsJsonArray("arguments").first().asJsonObject
}
