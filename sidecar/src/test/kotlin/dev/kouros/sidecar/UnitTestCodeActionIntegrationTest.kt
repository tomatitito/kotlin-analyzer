package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnitTestCodeActionIntegrationTest {

    private val bridges = mutableListOf<CompilerBridge>()

    @AfterEach
    fun tearDown() {
        bridges.asReversed().forEach { it.shutdown() }
        bridges.clear()
    }

    @Test
    fun `codeActions offers open existing unit test on class declaration`() {
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
                fun value(): Int = 42
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

        val bridge = createBridge(
            projectRoot = workspace.toString(),
            classpath = emptyList(),
            sourceRoots = listOf(mainRoot.toString(), testRoot.toString()),
        )

        val action = bridge.codeActions(sourcePath.toUri().toString(), line = 3, character = 7)
            .getAsJsonArray("actions")
            .findByTitle("Open or Create Unit Test")
        val command = action.getAsJsonObject("command")
        val payload = command.firstArgument()

        assertEquals(AnalyzerCommands.OPEN_TEST_TARGET, command.get("command").asString)
        assertEquals(targetPath.toUri().toString(), payload.get(AnalyzerCommands.ARG_TARGET_URI).asString)
        assertEquals(
            JsonObject().apply {
                addProperty("startLine", 6)
                addProperty("startCharacter", 4)
                addProperty("endLine", 6)
                addProperty("endCharacter", 4)
            },
            payload.getAsJsonObject(AnalyzerCommands.ARG_SELECTION),
        )
    }

    @Test
    fun `codeActions creates junit-backed unit test payload for missing target`() {
        val fixtureRoot = copyFixtureToTempDir(findFixtureRoot())
        val projectModel = loadProjectModel(fixtureRoot)
        val mainRoot = projectModel.sourceRoots.first { it.endsWith("src/main/kotlin") }
        val sourcePath = Path.of(mainRoot).resolve("com/example/Foo.kt")

        val bridge = createBridge(
            projectRoot = fixtureRoot,
            classpath = projectModel.classpath,
            sourceRoots = projectModel.sourceRoots,
            compilerFlags = projectModel.compilerFlags,
            jdkHome = projectModel.jdkHome ?: "",
        )

        val action = bridge.codeActions(sourcePath.toUri().toString(), line = 3, character = 6)
            .getAsJsonArray("actions")
            .findByTitle("Open or Create Unit Test")
        val command = action.getAsJsonObject("command")
        val payload = command.firstArgument()
        val targetPath = Path.of(payload.get(AnalyzerCommands.ARG_TARGET_PATH).asString)
        val targetUri = payload.get(AnalyzerCommands.ARG_TARGET_URI).asString
        val initialContents = payload.get(AnalyzerCommands.ARG_INITIAL_CONTENTS).asString

        assertEquals(AnalyzerCommands.CREATE_AND_OPEN_TEST_TARGET, command.get("command").asString)
        assertEquals(targetPath.toUri().toString(), targetUri)
        assertTrue(initialContents.contains("package com.example"))
        assertTrue(initialContents.contains("import org.junit.jupiter.api.Test"))
        assertTrue(initialContents.contains("class FooTest"))
        assertTrue(initialContents.contains("fun hurz()"))
        assertEquals(
            JsonObject().apply {
                addProperty("startLine", 7)
                addProperty("startCharacter", 8)
                addProperty("endLine", 7)
                addProperty("endCharacter", 8)
            },
            payload.getAsJsonObject(AnalyzerCommands.ARG_SELECTION),
        )

        Files.createDirectories(targetPath.parent)
        targetPath.writeText(initialContents)

        val reopenedBridge = createBridge(
            projectRoot = fixtureRoot,
            classpath = projectModel.classpath,
            sourceRoots = projectModel.sourceRoots,
            compilerFlags = projectModel.compilerFlags,
            jdkHome = projectModel.jdkHome ?: "",
        )
        reopenedBridge.updateFile(targetUri, initialContents)
        val diagnostics = reopenedBridge.analyze(targetUri).getAsJsonArray("diagnostics")
        assertNotNull(diagnostics)
        assertEquals(
            0,
            diagnostics.filter { it.asJsonObject.get("severity")?.asString == "ERROR" }.size,
            "generated test should be syntactically valid with the fixture classpath: $diagnostics",
        )
    }

    private fun createBridge(
        projectRoot: String,
        classpath: List<String>,
        sourceRoots: List<String>,
        compilerFlags: List<String> = emptyList(),
        jdkHome: String = "",
    ): CompilerBridge {
        return CompilerBridge().also { bridge ->
            bridges += bridge
            bridge.initialize(
                projectRoot = projectRoot,
                classpath = classpath,
                compilerFlags = compilerFlags,
                jdkHome = jdkHome,
                sourceRoots = sourceRoots,
            )
        }
    }

    private data class ProjectModel(
        val sourceRoots: List<String>,
        val classpath: List<String>,
        val compilerFlags: List<String>,
        val jdkHome: String?,
    )

    private fun loadProjectModel(fixtureRoot: String): ProjectModel {
        val config = resolveGradleProjectConfig(fixtureRoot)
        val sourceRoots = listOf(
            "$fixtureRoot/src/main/kotlin",
            "$fixtureRoot/src/test/kotlin",
            "$fixtureRoot/src/main/java",
            "$fixtureRoot/src/test/java",
        ).filter { File(it).exists() }

        return ProjectModel(
            sourceRoots = sourceRoots,
            classpath = config.classpath.filter { File(it).exists() },
            compilerFlags = config.compilerFlags,
            jdkHome = System.getProperty("java.home"),
        )
    }

    private data class GradleProjectConfig(val classpath: List<String>, val compilerFlags: List<String>)

    private fun resolveGradleProjectConfig(fixtureRoot: String): GradleProjectConfig {
        val process = ProcessBuilder(resolveGradleCommand(fixtureRoot))
            .directory(File(fixtureRoot))
            .apply {
                environment()["GRADLE_USER_HOME"] =
                    Paths.get(System.getProperty("java.io.tmpdir"), "kotlin-analyzer-gradle").toString()
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

        return GradleProjectConfig(
            classpath = extractBlock("---CLASSPATH-START---", "---CLASSPATH-END---"),
            compilerFlags = extractBlock("---FLAGS-START---", "---FLAGS-END---"),
        )
    }

    private fun resolveGradleCommand(fixtureRoot: String): List<String> {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            val wrapper = File(dir, "gradlew")
            if (wrapper.exists()) {
                return listOf(wrapper.absolutePath, "-p", fixtureRoot, "printProjectConfig", "--quiet")
            }
            val sidecarWrapper = File(dir, "sidecar/gradlew")
            if (sidecarWrapper.exists()) {
                return listOf(sidecarWrapper.absolutePath, "-p", fixtureRoot, "printProjectConfig", "--quiet")
            }
            dir = dir.parentFile
        }
        return listOf("gradle", "-p", fixtureRoot, "printProjectConfig", "--quiet")
    }

    private fun findFixtureRoot(): String {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            val fixture = File(dir, "tests/fixtures/gradle-kotlin-junit")
            if (fixture.exists()) return fixture.absolutePath
            dir = dir.parentFile
        }
        error("Could not find tests/fixtures/gradle-kotlin-junit/ from ${System.getProperty("user.dir")}")
    }

    private fun copyFixtureToTempDir(source: String): String {
        val sourcePath = Path.of(source)
        val tempDir = Files.createTempDirectory("gradle-kotlin-junit-fixture")
        Files.walk(sourcePath).use { paths ->
            paths.forEach { current ->
                val relative = sourcePath.relativize(current)
                val destination = tempDir.resolve(relative.toString())
                if (Files.isDirectory(current)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(current, destination)
                }
            }
        }
        return tempDir.toString()
    }

    private fun JsonArray.findByTitle(title: String): JsonObject =
        (0 until size()).map { get(it).asJsonObject }.first { it.get("title")?.asString == title }

    private fun JsonObject.firstArgument(): JsonObject =
        getAsJsonArray("arguments").first().asJsonObject
}
