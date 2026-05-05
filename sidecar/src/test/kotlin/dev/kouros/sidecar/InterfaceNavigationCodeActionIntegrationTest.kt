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

    @Test
    fun `implementation resolves concrete interface member implementations`() {
        val workspace = Files.createTempDirectory("interface-method-impls")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        writeSource(
            sourceRoot.resolve("com/example/Service.kt"),
            """
            package com.example

            interface Service {
                fun fetch(id: String): String
            }
            """.trimIndent() + "\n",
        )
        val primaryPath = sourceRoot.resolve("com/example/PrimaryService.kt")
        writeSource(
            primaryPath,
            """
            package com.example

            class PrimaryService : Service {
                override fun fetch(id: String): String = id
            }
            """.trimIndent() + "\n",
        )
        val fallbackPath = sourceRoot.resolve("com/example/FallbackService.kt")
        writeSource(
            fallbackPath,
            """
            package com.example

            object FallbackService : Service {
                override fun fetch(id: String): String = id.reversed()
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val locations = bridge
            .implementation(sourceRoot.resolve("com/example/Service.kt").toUri().toString(), line = 4, character = 8)
            .getAsJsonArray("locations")

        assertEquals(2, locations.size(), "expected two concrete method implementations")
        assertEquals(
            listOf(
                fallbackPath.toUri().toString(),
                primaryPath.toUri().toString(),
            ),
            locations.map { it.asJsonObject.get("uri").asString }.sorted(),
        )
        assertEquals(
            listOf(4, 4),
            locations.map { it.asJsonObject.get("line").asInt }.sorted(),
        )
    }

    @Test
    fun `implementation returns workspace URIs after shadow rebuild`() {
        val workspace = Files.createTempDirectory("interface-shadow-impls")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val servicePath = sourceRoot.resolve("com/example/Service.kt")
        writeSource(
            servicePath,
            """
            package com.example

            interface Service
            """.trimIndent() + "\n",
        )
        val primaryPath = sourceRoot.resolve("com/example/PrimaryService.kt")
        val primaryText = """
            package com.example

            class PrimaryService : Service
            """.trimIndent() + "\n"
        writeSource(primaryPath, primaryText)

        val bridge = createBridge(workspace, sourceRoot)
        bridge.updateFile(primaryPath.toUri().toString(), primaryText.replace(": Service", ": Service /* open buffer */"))

        val locations = bridge
            .implementation(servicePath.toUri().toString(), line = 3, character = 11)
            .getAsJsonArray("locations")

        assertEquals(1, locations.size(), "expected one concrete implementation")
        val uri = locations[0].asJsonObject.get("uri").asString
        assertEquals(primaryPath.toUri().toString(), uri)
        assertTrue(!uri.contains("/shadow/"), "implementation URI must not contain shadow root path: $uri")
    }

    @Test
    fun `definition returns workspace URI after shadow rebuild`() {
        val workspace = Files.createTempDirectory("interface-shadow-definition")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val servicePath = sourceRoot.resolve("com/example/Service.kt")
        writeSource(
            servicePath,
            """
            package com.example

            interface Service
            """.trimIndent() + "\n",
        )
        val primaryPath = sourceRoot.resolve("com/example/PrimaryService.kt")
        val primaryText = """
            package com.example

            class PrimaryService : Service
            """.trimIndent() + "\n"
        writeSource(primaryPath, primaryText)

        val bridge = createBridge(workspace, sourceRoot)
        bridge.updateFile(primaryPath.toUri().toString(), primaryText.replace(": Service", ": Service /* open buffer */"))

        val locations = bridge
            .definition(primaryPath.toUri().toString(), line = 3, character = 23)
            .getAsJsonArray("locations")

        assertEquals(1, locations.size(), "expected one interface definition")
        val uri = locations[0].asJsonObject.get("uri").asString
        assertEquals(servicePath.toUri().toString(), uri)
        assertTrue(!uri.contains("/shadow/"), "definition URI must not contain shadow root path: $uri")
    }

    @Test
    fun `definition resolves interface member declaration from class implementation`() {
        val workspace = Files.createTempDirectory("interface-method-definition-class")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val servicePath = sourceRoot.resolve("com/example/Service.kt")
        writeSource(
            servicePath,
            """
            package com.example

            interface Service {
                fun fetch(id: String): String
            }
            """.trimIndent() + "\n",
        )
        val primaryPath = sourceRoot.resolve("com/example/PrimaryService.kt")
        writeSource(
            primaryPath,
            """
            package com.example

            class PrimaryService : Service {
                override fun fetch(id: String): String = id
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val locations = bridge
            .definition(primaryPath.toUri().toString(), line = 4, character = 17)
            .getAsJsonArray("locations")

        assertEquals(1, locations.size(), "expected a single interface declaration target")
        val location = locations[0].asJsonObject
        assertEquals(servicePath.toUri().toString(), location.get("uri").asString)
        assertEquals(4, location.get("line").asInt)
    }

    @Test
    fun `definition resolves interface member declaration from object implementation`() {
        val workspace = Files.createTempDirectory("interface-method-definition-object")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val servicePath = sourceRoot.resolve("com/example/Service.kt")
        writeSource(
            servicePath,
            """
            package com.example

            interface Service {
                fun fetch(id: String): String
            }
            """.trimIndent() + "\n",
        )
        val fallbackPath = sourceRoot.resolve("com/example/FallbackService.kt")
        writeSource(
            fallbackPath,
            """
            package com.example

            object FallbackService : Service {
                override fun fetch(id: String): String = id.reversed()
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val locations = bridge
            .definition(fallbackPath.toUri().toString(), line = 4, character = 17)
            .getAsJsonArray("locations")

        assertEquals(1, locations.size(), "expected a single interface declaration target")
        val location = locations[0].asJsonObject
        assertEquals(servicePath.toUri().toString(), location.get("uri").asString)
        assertEquals(4, location.get("line").asInt)
    }

    @Test
    fun `implementation resolves concrete interface type implementations`() {
        val workspace = Files.createTempDirectory("interface-type-impls")
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

        val bridge = createBridge(workspace, sourceRoot)
        val locations = bridge
            .implementation(sourceRoot.resolve("com/example/Service.kt").toUri().toString(), line = 3, character = 11)
            .getAsJsonArray("locations")

        assertEquals(2, locations.size(), "expected two concrete type implementations")
        assertEquals(
            listOf(
                fallbackPath.toUri().toString(),
                primaryPath.toUri().toString(),
            ),
            locations.map { it.asJsonObject.get("uri").asString }.sorted(),
        )
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
