package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths
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
 * If project-model.json is missing, this test can generate it from the fixture's
 * `printProjectConfig` task through the repo `gradlew` wrapper before parsing.
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
        // Example.kt line 15: "@RestController"
        //                       ^1 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 15, character = 1)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on @RestController should return contents")
        assertTrue(
            contents.contains("RestController"),
            "hover should mention 'RestController', got: $contents"
        )
    }

    @Test
    fun `hover - GetMapping annotation resolves`() {
        // Example.kt line 18: "    @GetMapping("/greeting/{name}")"
        //                           ^5 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 18, character = 5)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on @GetMapping should return contents")
        assertTrue(
            contents.contains("GetMapping"),
            "hover should mention 'GetMapping', got: $contents"
        )
    }

    @Test
    fun `hover - PathVariable annotation resolves`() {
        // Example.kt line 20: "        @PathVariable name: String,"
        //                                     ^18 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 20, character = 18)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on @PathVariable should return contents")
        assertTrue(
            contents.contains("PathVariable"),
            "hover should mention 'PathVariable', got: $contents"
        )
    }

    @Test
    fun `hover - ResponseStatusException resolves`() {
        // Example.kt line 42: "        throw ResponseStatusException(..."
        //                                    ^14 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 42, character = 14)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on ResponseStatusException should return contents")
        assertTrue(
            contents.contains("ResponseStatusException"),
            "hover should mention 'ResponseStatusException', got: $contents"
        )
    }

    @Test
    fun `hover - HttpStatus enum resolves`() {
        // Example.kt line 42: "        throw ResponseStatusException(HttpStatus.NOT_FOUND, ...)"
        //                                                             ^43 (0-based)
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.hover(uri, line = 42, character = 43)

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
        // Example.kt line 15: "@RestController"
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.definition(uri, line = 15, character = 1)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() > 0,
            "should find definition for @RestController"
        )
    }

    @Test
    fun `definition - controller return resolves template`() {
        // Example.kt line 27: `return "greeting"`
        val uri = "file://$sourceRoot/Example.kt"
        val result = bridge.definition(uri, line = 27, character = 15)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() > 0,
            "should find template definition for greeting return value"
        )
        val uriValue = locations[0].asJsonObject.get("uri")?.asString ?: ""
        assertTrue(
            uriValue.contains("greeting.peb"),
            "greeting return should define to greeting.peb, got: $uriValue"
        )
    }

    @Test
    fun `definition - template include resolves to partial`() {
        val uri = "file://$sourceRoot/../resources/templates/greeting.peb"
        val result = bridge.definition(uri, line = 2, character = 13)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() > 0,
            "include in greeting.peb should define to a template file"
        )
        val uriValue = locations[0].asJsonObject.get("uri")?.asString ?: ""
        assertTrue(
            uriValue.contains("partials/_header.peb"),
            "include should resolve to partial template, got: $uriValue"
        )
    }

    @Test
    fun `definition - template extends resolves to base`() {
        val uri = "file://$sourceRoot/../resources/templates/greeting.peb"
        val result = bridge.definition(uri, line = 1, character = 12)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() > 0,
            "extends in greeting.peb should define to a template file"
        )
        val uriValue = locations[0].asJsonObject.get("uri")?.asString ?: ""
        assertTrue(
            uriValue.contains("base.peb"),
            "extends should resolve to base.peb, got: $uriValue"
        )
    }

    @Test
    fun `definition - template variable resolves to kotlin producer`() {
        val uri = "file://$sourceRoot/../resources/templates/greeting.peb"
        val result = bridge.definition(uri, line = 4, character = 13)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() > 0,
            "template variable should resolve to Kotlin declaration"
        )
        val uriValue = locations[0].asJsonObject.get("uri")?.asString ?: ""
        assertTrue(
            uriValue.endsWith("Example.kt"),
            "template variable should resolve into Example.kt, got: $uriValue"
        )
    }

    @Test
    fun `references - kotlin references include pebble hits`() {
        val uri = "file://$sourceRoot/Example.kt"
        // Example.kt line 24: `val resolvedTitle = title ?: "Guest"`
        val result = bridge.references(uri, line = 24, character = 17)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() >= 1,
            "should find at least one reference for resolvedTitle"
        )

        val uris = locations.map { it.asJsonObject.get("uri")?.asString ?: "" }.toSet()
        assertTrue(
            uris.any { it.contains("greeting.peb") },
            "references should include template usage in greeting.peb, got: $uris"
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
        val json = if (modelFile.exists()) {
            JsonParser.parseString(modelFile.readText()).asJsonObject
        } else {
            val generated = generateProjectModel(fixtureRoot)
            modelFile.parentFile?.mkdirs()
            modelFile.writeText(generated.toString())
            generated
        }
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

    private fun generateProjectModel(fixtureRoot: String): JsonObject {
        val config = resolveGradleProjectConfig(fixtureRoot)
        val sourceRoots = listOf(
            "$fixtureRoot/src/main/kotlin",
            "$fixtureRoot/src/main/java",
            "$fixtureRoot/src",
        )
            .filter { java.io.File(it).exists() }
        require(sourceRoots.isNotEmpty()) {
            "Could not infer source roots for spring fixture at $fixtureRoot"
        }

        val json = JsonObject().apply {
            addProperty("project_root", fixtureRoot)
            addProperty("build_system", "Gradle")
            add("source_roots", JsonArray().also { src ->
                sourceRoots.forEach { src.add(it) }
            })
            add("classpath", JsonArray().also { path ->
                config.classpath.forEach { path.add(it) }
            })
            add("compiler_flags", JsonArray().also { flags ->
                config.compilerFlags.forEach { flags.add(it) }
            })
            addProperty("jdk_home", System.getProperty("java.home"))
            addProperty("kotlin_version", null as String?)
            addProperty("has_compose", false)
            add("generated_source_roots", JsonArray())
        }
        return json
    }

    private data class GradleProjectConfig(val classpath: List<String>, val compilerFlags: List<String>)

    private fun resolveGradleProjectConfig(fixtureRoot: String): GradleProjectConfig {
        val process = ProcessBuilder(resolveGradleCommand(fixtureRoot))
            .directory(java.io.File(fixtureRoot))
            .apply {
                val gradleHome = Paths.get(System.getProperty("java.io.tmpdir"), "kotlin-analyzer-gradle").toString()
                environment()["GRADLE_USER_HOME"] = gradleHome
            }
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "Gradle config resolution failed (exit $exitCode): $output" }

        val lines = output.lines()
        fun extractBlock(startMarker: String, endMarker: String): List<String> {
            val startIdx = lines.indexOf(startMarker)
            val endIdx = lines.indexOf(endMarker)
            check(startIdx >= 0 && endIdx > startIdx) {
                "Could not find $startMarker/$endMarker in Gradle output: $output"
            }
            return lines.subList(startIdx + 1, endIdx).filter { it.isNotBlank() }
        }

        return GradleProjectConfig(
            classpath = extractBlock("---CLASSPATH-START---", "---CLASSPATH-END---"),
            compilerFlags = extractBlock("---FLAGS-START---", "---FLAGS-END---"),
        )
    }

    private fun resolveGradleCommand(fixtureRoot: String): List<String> {
        val fixtureDir = File(fixtureRoot).absoluteFile
        var dir = fixtureDir
        while (dir.parentFile != null) {
            val wrapper = File(dir, "gradlew")
            if (wrapper.exists()) {
                return listOf(wrapper.absolutePath, "-p", fixtureRoot, "printProjectConfig", "--quiet")
            }
            val wrapperBat = File(dir, "gradlew.bat")
            if (wrapperBat.exists()) {
                return listOf(wrapperBat.absolutePath, "-p", fixtureRoot, "printProjectConfig", "--quiet")
            }
            val sidecarWrapper = File(dir, "sidecar/gradlew")
            if (sidecarWrapper.exists()) {
                return listOf(sidecarWrapper.absolutePath, "-p", fixtureRoot, "printProjectConfig", "--quiet")
            }
            val sidecarWrapperBat = File(dir, "sidecar/gradlew.bat")
            if (sidecarWrapperBat.exists()) {
                return listOf(sidecarWrapperBat.absolutePath, "-p", fixtureRoot, "printProjectConfig", "--quiet")
            }
            dir = dir.parentFile
        }
        return listOf("gradle", "-p", fixtureRoot, "printProjectConfig", "--quiet")
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
