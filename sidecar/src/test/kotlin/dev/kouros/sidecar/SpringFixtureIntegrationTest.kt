package dev.kouros.sidecar

import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that exercise CompilerBridge against the gradle-spring-web fixture.
 *
 * Unlike CompilerBridgeIntegrationTest (which uses test-sources/ with an empty classpath),
 * this test initializes the bridge using the project-model.json produced by Gradle,
 * providing real classpath entries for Spring Web, Arrow, etc. This lets us verify that
 * annotations like @RestController resolve without warnings or errors.
 *
 * Prerequisite: run `./gradlew printProjectConfig` in tests/fixtures/gradle-spring-web/
 * at least once so that Gradle downloads dependencies and project-model.json is populated.
 */
class SpringFixtureIntegrationTest {

    private lateinit var bridge: CompilerBridge
    private lateinit var fixtureRoot: String
    private lateinit var sourceRoot: String

    @BeforeEach
    fun setUp() {
        fixtureRoot = findFixtureRoot()
        val projectModel = loadProjectModel(fixtureRoot)

        sourceRoot = projectModel.sourceRoots.first()
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureRoot,
            classpath = projectModel.classpath,
            compilerFlags = projectModel.compilerFlags,
            jdkHome = projectModel.jdkHome ?: "",
            sourceRoots = projectModel.sourceRoots,
        )
    }

    @AfterEach
    fun tearDown() {
        bridge.shutdown()
    }

    // --- Diagnostics ---

    @Test
    fun `diagnostics - spring controller has no errors`() {
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")

        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(
            0, errors.size,
            "Example.kt with Spring classpath should have no errors, got: ${formatDiagnostics(errors)}"
        )
    }

    @Test
    fun `diagnostics - spring controller has no warnings`() {
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")

        val warnings = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "WARNING"
        }
        assertEquals(
            0, warnings.size,
            "Example.kt with Spring classpath should have no warnings, got: ${formatDiagnostics(warnings)}"
        )
    }

    // --- Hover ---

    @Test
    fun `hover - RestController annotation resolves`() {
        // Example.kt line 14: "@RestController"
        //                       ^1 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 14, character = 1)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on @RestController should return contents")
        assertTrue(
            contents.contains("RestController"),
            "hover should mention 'RestController', got: $contents"
        )
    }

    @Test
    fun `hover - GetMapping annotation resolves`() {
        // Example.kt line 17: "    @GetMapping("/greet/{name}")"
        //                           ^5 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 17, character = 5)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on @GetMapping should return contents")
        assertTrue(
            contents.contains("GetMapping"),
            "hover should mention 'GetMapping', got: $contents"
        )
    }

    @Test
    fun `hover - PathVariable annotation resolves`() {
        // Example.kt line 18: "    fun greet(@PathVariable name: String, ..."
        //                                     ^18 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 18, character = 18)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on @PathVariable should return contents")
        assertTrue(
            contents.contains("PathVariable"),
            "hover should mention 'PathVariable', got: $contents"
        )
    }

    @Test
    fun `hover - ResponseStatusException resolves`() {
        // Example.kt line 29: "        throw ResponseStatusException(..."
        //                                    ^14 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 29, character = 14)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on ResponseStatusException should return contents")
        assertTrue(
            contents.contains("ResponseStatusException"),
            "hover should mention 'ResponseStatusException', got: $contents"
        )
    }

    @Test
    fun `hover - HttpStatus enum resolves`() {
        // Example.kt line 29: "        throw ResponseStatusException(HttpStatus.NOT_FOUND, ...)"
        //                                                             ^43 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 29, character = 43)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on HttpStatus should return contents")
        assertTrue(
            contents.contains("HttpStatus") || contents.contains("NOT_FOUND"),
            "hover should mention 'HttpStatus' or 'NOT_FOUND', got: $contents"
        )
    }

    // --- Definition ---

    @Test
    fun `definition - RestController annotation resolves to Spring source`() {
        // Example.kt line 14: "@RestController"
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.definition(uri, line = 14, character = 1)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() > 0,
            "should find definition for @RestController"
        )
    }

    // --- Helpers ---

    private data class ProjectModel(
        val sourceRoots: List<String>,
        val classpath: List<String>,
        val compilerFlags: List<String>,
        val jdkHome: String?,
    )

    private fun loadProjectModel(fixtureRoot: String): ProjectModel {
        val modelFile = java.io.File(fixtureRoot, ".kotlin-analyzer/project-model.json")
        require(modelFile.exists()) {
            "project-model.json not found at ${modelFile.absolutePath}. " +
                "Run the Rust LSP server against the fixture once, or run " +
                "'./gradlew printProjectConfig' in $fixtureRoot to generate it."
        }

        val json = JsonParser.parseString(modelFile.readText()).asJsonObject

        val sourceRoots = json.getAsJsonArray("source_roots")
            .map { it.asString }
            .filter { java.io.File(it).exists() }
        require(sourceRoots.isNotEmpty()) { "No valid source_roots in project-model.json" }

        val classpath = json.getAsJsonArray("classpath")
            .map { it.asString }
            .filter { java.io.File(it).exists() }

        val compilerFlags = json.getAsJsonArray("compiler_flags")
            ?.map { it.asString }
            ?: emptyList()

        val jdkHome = json.get("jdk_home")?.let {
            if (it.isJsonNull) null else it.asString
        }

        return ProjectModel(sourceRoots, classpath, compilerFlags, jdkHome)
    }

    private fun findFixtureRoot(): String {
        var dir = java.io.File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            val fixture = java.io.File(dir, "tests/fixtures/gradle-spring-web")
            if (fixture.exists()) return fixture.absolutePath
            dir = dir.parentFile
        }
        error("Could not find tests/fixtures/gradle-spring-web/ from ${System.getProperty("user.dir")}")
    }

    private fun formatDiagnostics(diagnostics: List<com.google.gson.JsonElement>): String {
        return diagnostics.map {
            it.asJsonObject.let { o ->
                "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
            }
        }.toString()
    }
}
