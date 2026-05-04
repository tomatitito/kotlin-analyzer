package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InterfaceSignaturePropagationCodeActionIntegrationTest {

    private val bridges = mutableListOf<CompilerBridge>()

    @AfterEach
    fun tearDown() {
        bridges.asReversed().forEach { it.shutdown() }
        bridges.clear()
    }

    @Test
    fun `codeActions updates interface signature from overriding implementation`() {
        val workspace = Files.createTempDirectory("interface-signature-impl")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val interfacePath = sourceRoot.resolve("com/example/Greeter.kt")
        writeSource(
            interfacePath,
            """
            package com.example

            interface Greeter {
                fun greet(name: String)
            }
            """.trimIndent() + "\n",
        )
        val implPath = sourceRoot.resolve("com/example/ConsoleGreeter.kt")
        writeSource(
            implPath,
            """
            package com.example

            class ConsoleGreeter : Greeter {
                override fun greet(name: String, punctuation: String) {}
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge.codeActions(implPath.toUri().toString(), line = 4, character = 18).getAsJsonArray("actions")
        assertNotNull(actions)

        val action = actions.findByTitle("Update interface signature")
        val edits = action.getAsJsonArray("edits")
        assertEquals(1, edits.size(), "expected one interface edit")

        val updatedFiles = applyEdits(edits)
        assertEquals(
            """
            package com.example

            interface Greeter {
                fun greet(name: String, punctuation: String)
            }
            """.trimIndent() + "\n",
            updatedFiles.getValue(interfacePath.toUri().toString()),
        )
    }

    @Test
    fun `codeActions updates implementation signatures from interface declaration`() {
        val workspace = Files.createTempDirectory("interface-signature-iface")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val interfacePath = sourceRoot.resolve("com/example/Authenticator.kt")
        writeSource(
            interfacePath,
            """
            package com.example

            interface Authenticator {
                fun login(userId: String, tenant: String)
            }
            """.trimIndent() + "\n",
        )
        val primaryPath = sourceRoot.resolve("com/example/PasswordAuthenticator.kt")
        writeSource(
            primaryPath,
            """
            package com.example

            class PasswordAuthenticator : Authenticator {
                override fun login(userId: String) {}
            }
            """.trimIndent() + "\n",
        )
        val backupPath = sourceRoot.resolve("com/example/OtpAuthenticator.kt")
        writeSource(
            backupPath,
            """
            package com.example

            class OtpAuthenticator : Authenticator {
                override fun login(userId: String) {}
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge.codeActions(interfacePath.toUri().toString(), line = 4, character = 15).getAsJsonArray("actions")
        assertNotNull(actions)

        val action = actions.findByTitle("Update implementation signatures")
        val edits = action.getAsJsonArray("edits")
        assertEquals(2, edits.size(), "expected coordinated edits for both implementations")

        val updatedFiles = applyEdits(edits)
        val expectedImplementation = """
            package com.example

            class PasswordAuthenticator : Authenticator {
                override fun login(userId: String, tenant: String) {}
            }
            """.trimIndent() + "\n"
        assertEquals(expectedImplementation, updatedFiles.getValue(primaryPath.toUri().toString()))
        assertEquals(
            expectedImplementation.replace("PasswordAuthenticator", "OtpAuthenticator"),
            updatedFiles.getValue(backupPath.toUri().toString()),
        )
    }

    @Test
    fun `codeActions preserves valid multiline implementation signatures when appending parameter`() {
        val workspace = Files.createTempDirectory("interface-signature-multiline")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val interfacePath = sourceRoot.resolve("com/example/Authenticator.kt")
        writeSource(
            interfacePath,
            """
            package com.example

            interface Authenticator {
                fun login(
                    userId: String,
                    tenant: String
                )
            }
            """.trimIndent() + "\n",
        )
        val implPath = sourceRoot.resolve("com/example/PasswordAuthenticator.kt")
        writeSource(
            implPath,
            """
            package com.example

            class PasswordAuthenticator : Authenticator {
                override fun login(
                    userId: String
                ) {}
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge.codeActions(interfacePath.toUri().toString(), line = 5, character = 15).getAsJsonArray("actions")
        val action = actions.findByTitle("Update implementation signatures")
        val updatedFiles = applyEdits(action.getAsJsonArray("edits"))

        assertEquals(
            """
            package com.example

            class PasswordAuthenticator : Authenticator {
                override fun login(
                    userId: String,
                    tenant: String
                ) {}
            }
            """.trimIndent() + "\n",
            updatedFiles.getValue(implPath.toUri().toString()),
        )
    }

    @Test
    fun `codeActions sees override parameter additions from virtual editor changes`() {
        val workspace = Files.createTempDirectory("interface-signature-virtual-impl")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val interfacePath = sourceRoot.resolve("com/example/Greeter.kt")
        writeSource(
            interfacePath,
            """
            package com.example

            interface Greeter {
                fun greet(name: String)
            }
            """.trimIndent() + "\n",
        )
        val implPath = sourceRoot.resolve("com/example/ConsoleGreeter.kt")
        val originalImpl = """
            package com.example

            class ConsoleGreeter : Greeter {
                override fun greet(name: String) {}
            }
            """.trimIndent() + "\n"
        writeSource(implPath, originalImpl)

        val bridge = createBridge(workspace, sourceRoot)
        bridge.updateFile(
            implPath.toUri().toString(),
            """
            package com.example

            class ConsoleGreeter : Greeter {
                override fun greet(name: String, punctuation: String) {}
            }
            """.trimIndent() + "\n",
        )

        val actions = bridge.codeActions(implPath.toUri().toString(), line = 4, character = 18).getAsJsonArray("actions")
        val titles = actions.titles()

        assertTrue(
            "Update interface signature" in titles,
            "expected propagation action for virtual override edit, got: $titles",
        )
    }

    @Test
    fun `codeActions sees interface parameter additions from virtual editor changes`() {
        val workspace = Files.createTempDirectory("interface-signature-virtual-interface")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val interfacePath = sourceRoot.resolve("com/example/Authenticator.kt")
        writeSource(
            interfacePath,
            """
            package com.example

            interface Authenticator {
                fun login(userId: String)
            }
            """.trimIndent() + "\n",
        )
        val implPath = sourceRoot.resolve("com/example/PasswordAuthenticator.kt")
        writeSource(
            implPath,
            """
            package com.example

            class PasswordAuthenticator : Authenticator {
                override fun login(userId: String) {}
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        bridge.updateFile(
            interfacePath.toUri().toString(),
            """
            package com.example

            interface Authenticator {
                fun login(userId: String, tenant: String)
            }
            """.trimIndent() + "\n",
        )

        val actions = bridge.codeActions(interfacePath.toUri().toString(), line = 4, character = 15).getAsJsonArray("actions")
        val titles = actions.titles()

        assertTrue(
            "Update implementation signatures" in titles,
            "expected propagation action for virtual interface edit, got: $titles",
        )
    }

    @Test
    fun `codeActions skips propagation for non interface overrides`() {
        val workspace = Files.createTempDirectory("interface-signature-non-interface")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val path = sourceRoot.resolve("com/example/Derived.kt")
        writeSource(
            path,
            """
            package com.example

            abstract class Base {
                abstract fun compute(value: Int)
            }

            class Derived : Base() {
                override fun compute(value: Int, unit: String) {}
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge.codeActions(path.toUri().toString(), line = 7, character = 18).getAsJsonArray("actions")
        val titles = actions.titles()

        assertTrue(
            "Update interface signature" !in titles && "Update implementation signatures" !in titles,
            "expected no signature propagation actions, got: $titles",
        )
    }

    @Test
    fun `codeActions skips ambiguous multiple interface origins`() {
        val workspace = Files.createTempDirectory("interface-signature-ambiguous")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        writeSource(
            sourceRoot.resolve("com/example/Primary.kt"),
            """
            package com.example

            interface Primary {
                fun start(id: String)
            }
            """.trimIndent() + "\n",
        )
        writeSource(
            sourceRoot.resolve("com/example/Secondary.kt"),
            """
            package com.example

            interface Secondary {
                fun start(id: String)
            }
            """.trimIndent() + "\n",
        )
        val workerPath = sourceRoot.resolve("com/example/Worker.kt")
        writeSource(
            workerPath,
            """
            package com.example

            class Worker : Primary, Secondary {
                override fun start(id: String, traceId: String) {}
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge.codeActions(workerPath.toUri().toString(), line = 4, character = 18).getAsJsonArray("actions")
        val titles = actions.titles()

        assertTrue(
            "Update interface signature" !in titles,
            "expected no ambiguous interface propagation action, got: $titles",
        )
    }

    @Test
    fun `codeActions skips rename like parameter diffs`() {
        val workspace = Files.createTempDirectory("interface-signature-rename")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val interfacePath = sourceRoot.resolve("com/example/Greeter.kt")
        writeSource(
            interfacePath,
            """
            package com.example

            interface Greeter {
                fun greet(name: String)
            }
            """.trimIndent() + "\n",
        )
        val implPath = sourceRoot.resolve("com/example/ConsoleGreeter.kt")
        writeSource(
            implPath,
            """
            package com.example

            class ConsoleGreeter : Greeter {
                override fun greet(fullName: String, punctuation: String) {}
            }
            """.trimIndent() + "\n",
        )

        val bridge = createBridge(workspace, sourceRoot)
        val actions = bridge.codeActions(implPath.toUri().toString(), line = 4, character = 18).getAsJsonArray("actions")
        val titles = actions.titles()

        assertTrue(
            "Update interface signature" !in titles,
            "expected no propagation action for rename-like diff, got: $titles",
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

    private fun applyEdits(edits: JsonArray): Map<String, String> {
        val byUri = (0 until edits.size())
            .map { edits[it].asJsonObject }
            .groupBy { it.get("uri").asString }

        return byUri.mapValues { (uri, uriEdits) ->
            applyEditsToText(Path.of(java.net.URI.create(uri)).readText(), uriEdits)
        }
    }

    private fun applyEditsToText(text: String, edits: List<JsonObject>): String {
        val lineStarts = mutableListOf(0)
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                lineStarts += index + 1
            }
        }

        fun offset(line: Int, column: Int): Int {
            val lineIndex = (line - 1).coerceAtLeast(0)
            return lineStarts[lineIndex] + column
        }

        val builder = StringBuilder(text)
        edits.sortedByDescending { edit ->
            val range = edit.getAsJsonObject("range")
            offset(range.get("startLine").asInt, range.get("startColumn").asInt)
        }.forEach { edit ->
            val range = edit.getAsJsonObject("range")
            val start = offset(range.get("startLine").asInt, range.get("startColumn").asInt)
            val end = offset(range.get("endLine").asInt, range.get("endColumn").asInt)
            builder.replace(start, end, edit.get("newText").asString)
        }

        return builder.toString()
    }

    private fun JsonArray.titles(): List<String> =
        (0 until size()).map { get(it).asJsonObject.get("title")?.asString ?: "" }

    private fun JsonArray.findByTitle(title: String): JsonObject =
        (0 until size()).map { get(it).asJsonObject }.first { it.get("title")?.asString == title }
}
