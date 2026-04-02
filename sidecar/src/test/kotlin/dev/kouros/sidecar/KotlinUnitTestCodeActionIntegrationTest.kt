package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinUnitTestCodeActionIntegrationTest {

    private lateinit var bridge: CompilerBridge

    @AfterEach
    fun tearDown() {
        if (::bridge.isInitialized) {
            bridge.shutdown()
        }
    }

    @Test
    fun `codeActions - class with existing mirrored unit test returns open command`() {
        val workspace = Files.createTempDirectory("unit-test-code-action-existing")
        val mainRoot = workspace.resolve("src/main/kotlin")
        val testRoot = workspace.resolve("src/test/kotlin")
        val sourcePath = mainRoot.resolve("com/example/Foo.kt")
        val targetPath = testRoot.resolve("com/example/FooTest.kt")

        Files.createDirectories(sourcePath.parent)
        Files.createDirectories(targetPath.parent)
        sourcePath.writeText(
            """
            package com.example

            class Foo {
            }
            """.trimIndent() + "\n"
        )
        targetPath.writeText(
            """
            package com.example

            import org.junit.jupiter.api.Test

            class FooTest {
                @Test
                fun existingWorks() {
                }
            }
            """.trimIndent() + "\n"
        )

        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = workspace.absolutePathString(),
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(mainRoot.absolutePathString()),
        )

        val result = bridge.codeActions(sourcePath.toUri().toString(), line = 3, character = 6)
        val action = result.getAsJsonArray("actions").findByTitle("Open or Create Unit Test")
        val command = action.getAsJsonObject("command")
        val payload = command.getAsJsonArray("arguments")[0].asJsonObject

        assertEquals(AnalyzerCommands.OPEN_TEST_TARGET, command.get("command").asString)
        assertEquals(targetPath.toUri().toString(), payload.get(AnalyzerCommands.ARG_TARGET_URI).asString)
        assertEquals(
            TestTargetSelection(6, 4, 6, 4).toJson(),
            payload.getAsJsonObject(AnalyzerCommands.ARG_SELECTION),
        )
    }

    @Test
    fun `codeActions - class without mirrored unit test returns create command`() {
        val workspace = Files.createTempDirectory("unit-test-code-action-create")
        val mainRoot = workspace.resolve("src/main/kotlin")
        val sourcePath = mainRoot.resolve("com/example/Foo.kt")

        Files.createDirectories(sourcePath.parent)
        sourcePath.writeText(
            """
            package com.example

            class Foo {
            }
            """.trimIndent() + "\n"
        )

        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = workspace.absolutePathString(),
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(mainRoot.absolutePathString()),
        )

        val result = bridge.codeActions(sourcePath.toUri().toString(), line = 3, character = 6)
        val action = result.getAsJsonArray("actions").findByTitle("Open or Create Unit Test")
        val command = action.getAsJsonObject("command")
        val payload = command.getAsJsonArray("arguments")[0].asJsonObject
        val expectedTargetPath = workspace.resolve("src/test/kotlin/com/example/FooTest.kt")

        assertEquals(AnalyzerCommands.CREATE_AND_OPEN_TEST_TARGET, command.get("command").asString)
        assertEquals(expectedTargetPath.toString(), payload.get(AnalyzerCommands.ARG_TARGET_PATH).asString)
        assertEquals(expectedTargetPath.toUri().toString(), payload.get(AnalyzerCommands.ARG_TARGET_URI).asString)

        val initialContents = payload.get(AnalyzerCommands.ARG_INITIAL_CONTENTS).asString
        assertTrue(initialContents.contains("package com.example"))
        assertTrue(initialContents.contains("import org.junit.jupiter.api.Test"))
        assertTrue(initialContents.contains("class FooTest"))
        assertTrue(initialContents.contains("fun hurz()"))
        assertEquals(
            TestTargetSelection(7, 8, 7, 8).toJson(),
            payload.getAsJsonObject(AnalyzerCommands.ARG_SELECTION),
        )
    }

    @Test
    fun `generated junit test from gradle fixture analyzes and resolves main source symbols`() {
        val fixtureRoot = copyFixtureToTempDir(findFixtureRoot("tests/fixtures/gradle-kotlin-junit"))
        val config = resolveGradleProjectConfig(fixtureRoot)
        val mainRoot = fixtureRoot.resolve("src/main/kotlin")
        val testRoot = fixtureRoot.resolve("src/test/kotlin")
        val sourcePath = mainRoot.resolve("com/example/Foo.kt")
        val sourceUri = sourcePath.toUri().toString()

        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureRoot.absolutePathString(),
            classpath = config.classpath,
            compilerFlags = config.compilerFlags,
            jdkHome = System.getProperty("java.home"),
            sourceRoots = listOf(mainRoot.absolutePathString(), testRoot.absolutePathString()),
        )

        val result = bridge.codeActions(sourceUri, line = 3, character = 6)
        val action = result.getAsJsonArray("actions").findByTitle("Open or Create Unit Test")
        val command = action.getAsJsonObject("command")
        val payload = command.getAsJsonArray("arguments")[0].asJsonObject
        val targetPath = Paths.get(payload.get(AnalyzerCommands.ARG_TARGET_PATH).asString)
        val targetUri = payload.get(AnalyzerCommands.ARG_TARGET_URI).asString
        val initialContents = payload.get(AnalyzerCommands.ARG_INITIAL_CONTENTS).asString

        Files.createDirectories(targetPath.parent)
        Files.writeString(targetPath, initialContents)
        bridge.updateFile(targetUri, initialContents)

        val createdDiagnostics = bridge.analyze(targetUri).getAsJsonArray("diagnostics")
        val createdErrors = createdDiagnostics.filter { it.asJsonObject.get("severity")?.asString == "ERROR" }
        assertEquals(0, createdErrors.size, "generated test should analyze without errors: $createdErrors")

        val withMainReference = initialContents.replace(
            "    fun hurz() {\n        \n",
            "    fun hurz() {\n        Foo().greet()\n",
        )
        Files.writeString(targetPath, withMainReference)
        bridge.updateFile(targetUri, withMainReference)

        val referenceDiagnostics = bridge.analyze(targetUri).getAsJsonArray("diagnostics")
        val referenceErrors = referenceDiagnostics.filter { it.asJsonObject.get("severity")?.asString == "ERROR" }
        assertEquals(0, referenceErrors.size, "test source should resolve main-source symbols: $referenceErrors")
    }

    private data class ProjectConfig(
        val classpath: List<String>,
        val compilerFlags: List<String>,
    )

    private fun resolveGradleProjectConfig(fixtureRoot: Path): ProjectConfig {
        val process = ProcessBuilder(resolveGradleCommand(), "-p", fixtureRoot.toString(), "printProjectConfig", "--quiet")
            .directory(fixtureRoot.toFile())
            .apply {
                environment()["GRADLE_USER_HOME"] = Paths.get(
                    System.getProperty("java.io.tmpdir"),
                    "kotlin-analyzer-gradle",
                ).toString()
            }
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "Gradle config resolution failed (exit $exitCode): $output" }

        val lines = output.lines()
        fun extractBlock(startMarker: String, endMarker: String): List<String> {
            val startIndex = lines.indexOf(startMarker)
            val endIndex = lines.indexOf(endMarker)
            check(startIndex >= 0 && endIndex > startIndex) {
                "Could not find $startMarker/$endMarker in Gradle output: $output"
            }
            return lines.subList(startIndex + 1, endIndex).filter { it.isNotBlank() }
        }

        return ProjectConfig(
            classpath = extractBlock("---CLASSPATH-START---", "---CLASSPATH-END---"),
            compilerFlags = extractBlock("---FLAGS-START---", "---FLAGS-END---"),
        )
    }

    private fun resolveGradleCommand(): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            val wrapper = File(dir, "gradlew")
            if (wrapper.exists()) {
                return wrapper.absolutePath
            }
            val sidecarWrapper = File(dir, "sidecar/gradlew")
            if (sidecarWrapper.exists()) {
                return sidecarWrapper.absolutePath
            }
            dir = dir.parentFile
        }
        return "gradle"
    }

    private fun copyFixtureToTempDir(source: Path): Path {
        val tempDir = Files.createTempDirectory("gradle-kotlin-junit-fixture")
        Files.walk(source).use { paths ->
            paths.forEach { current ->
                val relative = source.relativize(current)
                val destination = tempDir.resolve(relative.toString())
                if (Files.isDirectory(current)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(current, destination)
                }
            }
        }
        return tempDir
    }

    private fun findFixtureRoot(relativePath: String): Path {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            val candidate = dir.toPath().resolve(relativePath)
            if (candidate.exists()) {
                return candidate
            }
            dir = dir.parentFile
        }
        error("Could not find $relativePath from ${System.getProperty("user.dir")}")
    }

    private fun JsonArray.findByTitle(title: String): JsonObject =
        (0 until size()).map { get(it).asJsonObject }.first { it.get("title")?.asString == title }

    private fun TestTargetSelection.toJson(): JsonObject =
        JsonObject().apply {
            addProperty(AnalyzerCommands.ARG_START_LINE, startLine)
            addProperty(AnalyzerCommands.ARG_START_CHARACTER, startCharacter)
            addProperty(AnalyzerCommands.ARG_END_LINE, endLine)
            addProperty(AnalyzerCommands.ARG_END_CHARACTER, endCharacter)
        }
}
