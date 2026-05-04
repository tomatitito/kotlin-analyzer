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

    @Test
    fun `analyze - unresolved type produces diagnostic without auto import edit`() {
        val uri = "file://$sourceRoot/ThirdPartyAutoImport.kt"
        val content = """
            class ThirdPartyAutoImport {
                val exception: ResponseStatusException? = null
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.analyze(uri)

        // Analyze should report the unresolved reference as a diagnostic
        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")
        val unresolvedDiags = (0 until diagnostics.size())
            .map { diagnostics[it].asJsonObject }
            .filter { it.get("code")?.asString == "UNRESOLVED_REFERENCE" }
        assertTrue(
            unresolvedDiags.isNotEmpty(),
            "should report UNRESOLVED_REFERENCE diagnostic for ResponseStatusException",
        )

        // Analyze must NOT return import edits — imports should only be offered via code actions
        val edits = result.getAsJsonArray("edits")
        assertNotNull(edits, "edits array should be present")
        assertEquals(0, edits.size(), "analyze must not return auto-import edits")
    }

    @Test
    fun `codeActions - third party unresolved type offers import quick fix`() {
        val uri = "file://$sourceRoot/ThirdPartyCodeActions.kt"
        val content = """
            class ThirdPartyCodeActions {
                val criteria: Criteria? = null
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 2, character = 18)
        val actions = result.getAsJsonArray("actions")

        assertNotNull(actions, "actions array should be present")
        val titles = actions.map { it.asJsonObject.get("title")?.asString ?: "" }
        assertTrue(
            titles.any { it.startsWith("Import 'Criteria'") },
            "should offer import quick fix for third-party type, got: $titles",
        )
    }

    @Test
    fun `codeActions - unique unresolved type offers single import quick fix`() {
        val uri = "file://$sourceRoot/ThirdPartyUniqueImport.kt"
        val content = """
            class ThirdPartyUniqueImport {
                val exception: ResponseStatusException? = null
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 2, character = 24)
        val actions = result.getAsJsonArray("actions")

        assertNotNull(actions, "actions array should be present")
        val titles = actions.map { it.asJsonObject.get("title")?.asString ?: "" }
        assertTrue(
            titles.contains("Import 'ResponseStatusException'"),
            "should offer import quick fix for unique unresolved type, got: $titles",
        )
    }

    @Test
    fun `completion - already imported type appears in completion`() {
        val uri = "file://$sourceRoot/ImportedTypeCompletion.kt"
        val content = """
            import org.springframework.web.server.ResponseStatusException

            class ImportedTypeCompletion {
                val ex: Resp
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.completion(uri, line = 4, character = 16)
        val items = result.getAsJsonArray("items")

        assertNotNull(items, "completion items should be present")

        val matchingItems = (0 until items.size())
            .map { items[it].asJsonObject }
            .filter { it.get("label")?.asString?.startsWith("ResponseStatus") == true }

        assertTrue(
            matchingItems.isNotEmpty(),
            "should include ResponseStatusException in completion for already-imported type, got labels: ${
                (0 until items.size()).map { items[it].asJsonObject.get("label")?.asString }
            }",
        )

        // Already-imported type should NOT have additionalTextEdits (no import needed)
        val rseItem = matchingItems.first { it.get("label")?.asString == "ResponseStatusException" }
        assertTrue(
            !rseItem.has("additionalTextEdits"),
            "already-imported type should not have additionalTextEdits",
        )
    }

    @Test
    fun `completion - already imported constructor appears in completion`() {
        val uri = "file://$sourceRoot/ImportedConstructorCompletion.kt"
        val content = """
            import org.springframework.web.server.ResponseStatusException
            import org.springframework.http.HttpStatus

            class ImportedConstructorCompletion {
                fun make() {
                    val ex = Response
                }
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.completion(uri, line = 6, character = 26)
        val items = result.getAsJsonArray("items")

        assertNotNull(items, "completion items should be present")

        val matchingItems = (0 until items.size())
            .map { items[it].asJsonObject }
            .filter { it.get("label")?.asString == "ResponseStatusException" }

        assertTrue(
            matchingItems.isNotEmpty(),
            "should include ResponseStatusException constructor in completion, got labels: ${
                (0 until items.size()).map { items[it].asJsonObject.get("label")?.asString }
            }",
        )
    }

    @org.junit.jupiter.api.Disabled("Third-party symbol index not yet populated for completion — tracked in completion tickets")
    @Test
    fun `completion - includes third party symbol with auto import edit`() {
        val uri = "file://$sourceRoot/ThirdPartyCompletion.kt"
        val content = """
            class ThirdPartyCompletion {
                val either = Eit
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.completion(uri, line = 2, character = 20)
        val items = result.getAsJsonArray("items")

        assertNotNull(items, "completion items should be present")

        val eitherItems = (0 until items.size())
            .map { items[it].asJsonObject }
            .filter { it.get("label")?.asString == "Either" }

        assertTrue(
            eitherItems.isNotEmpty(),
            "should include Either in completion results, got labels: ${
                (0 until items.size()).map { items[it].asJsonObject.get("label")?.asString }
            }",
        )

        val autoImportEither = eitherItems.firstOrNull { item ->
            item.get("detail")?.asString == "arrow.core.Either" && item.has("additionalTextEdits")
        }
        assertNotNull(autoImportEither, "Either completion item should include auto-import edits")

        val edits = autoImportEither.getAsJsonArray("additionalTextEdits")
        assertTrue(edits.size() > 0, "additionalTextEdits should contain an import edit")
        assertEquals(
            "import arrow.core.Either\n\n",
            edits[0].asJsonObject.get("newText")?.asString,
        )
    }

    // --- Spring/Kotlin regression (ka-v3hp) ---

    @Test
    fun `diagnostics - SpringRegressions has no errors`() {
        val uri = "file://$sourceRoot/SpringRegressions.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")

        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(
            0, errors.size,
            "SpringRegressions.kt should have no errors, got: ${formatDiagnostics(errors)}"
        )
    }

    @Test
    fun `diagnostics - SpringRegressions has no warnings`() {
        val uri = "file://$sourceRoot/SpringRegressions.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")

        val warnings = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "WARNING"
        }
        assertEquals(
            0, warnings.size,
            "SpringRegressions.kt should have no warnings, got: ${formatDiagnostics(warnings)}"
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
            val cached = JsonParser.parseString(modelFile.readText()).asJsonObject
            val cachedClasspath = cached.getAsJsonArray("classpath")
                ?.map { it.asString }
                .orEmpty()
            val hasUsableClasspath = cachedClasspath.isNotEmpty() && cachedClasspath.all { java.io.File(it).exists() }
            if (cached.has("source_roots") && hasUsableClasspath) {
                cached
            } else {
                val regenerated = generateProjectModel(fixtureRoot)
                modelFile.parentFile?.mkdirs()
                modelFile.writeText(regenerated.toString())
                regenerated
            }
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
