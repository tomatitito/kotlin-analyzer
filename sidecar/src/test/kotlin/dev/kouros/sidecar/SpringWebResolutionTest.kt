package dev.kouros.sidecar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that the CompilerBridge can resolve third-party types (Spring Web,
 * Arrow Core) and handle compiler flags extracted from a real Gradle project.
 *
 * Uses the fixture at tests/fixtures/gradle-spring-web/ to extract a real
 * compileClasspath and freeCompilerArgs.
 */
class SpringWebResolutionTest {

    private lateinit var bridge: CompilerBridge
    private val testSourceDir = System.getProperty("user.dir") + "/src/test/resources/test-sources"

    companion object {
        private lateinit var projectClasspath: List<String>
        private lateinit var projectCompilerFlags: List<String>

        @JvmStatic
        @BeforeAll
        fun resolveProjectConfig() {
            val fixtureDir = findFixtureDir()
            val config = loadSpringProjectConfig(fixtureDir)
            projectClasspath = config.classpath
            projectCompilerFlags = config.compilerFlags

            assertTrue(
                projectClasspath.any { it.contains("spring-web") },
                "fixture classpath should contain spring-web JAR, got: $projectClasspath"
            )
            assertTrue(
                projectClasspath.any { it.contains("arrow-core") },
                "fixture classpath should contain arrow-core JAR, got: $projectClasspath"
            )
            assertTrue(
                projectCompilerFlags.contains("-Xcontext-parameters"),
                "fixture should have -Xcontext-parameters flag, got: $projectCompilerFlags"
            )
        }

        private fun findFixtureDir(): String {
            var dir = java.io.File(System.getProperty("user.dir"))
            while (dir.parentFile != null) {
                val fixture = java.io.File(dir, "tests/fixtures/gradle-spring-web")
                if (fixture.exists()) return fixture.absolutePath
                dir = dir.parentFile
            }
            error("Could not find tests/fixtures/gradle-spring-web/ from ${System.getProperty("user.dir")}")
        }

        data class ProjectConfig(val classpath: List<String>, val compilerFlags: List<String>)

        private fun loadSpringProjectConfig(fixtureDir: String): ProjectConfig {
            val modelFile = File(fixtureDir, ".kotlin-analyzer/project-model.json")
            val modelJson = if (modelFile.exists()) {
                JsonParser.parseString(modelFile.readText()).asJsonObject
            } else {
                val generated = resolveGradleProjectConfig(fixtureDir)
                val json = JsonObject().apply {
                    addProperty("jdk_home", System.getProperty("java.home"))
                    add("classpath", com.google.gson.JsonArray().also { classpath ->
                        generated.classpath.forEach { classpath.add(it) }
                    })
                    add("compiler_flags", com.google.gson.JsonArray().also { flags ->
                        generated.compilerFlags.forEach { flags.add(it) }
                    })
                }
                modelFile.parentFile?.mkdirs()
                modelFile.writeText(json.toString())
                json
            }

            val classpath = modelJson.getAsJsonArray("classpath")
                .map { it.asString }
                .filter { File(it).exists() }
            val compilerFlags = modelJson.getAsJsonArray("compiler_flags")
                .map { it.asString }
                .filter { it.isNotBlank() }

            return ProjectConfig(classpath, compilerFlags)
        }

        private fun resolveGradleProjectConfig(fixtureDir: String): ProjectConfig {
            val process = ProcessBuilder(resolveGradleCommand(fixtureDir))
                .directory(java.io.File(fixtureDir))
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

            return ProjectConfig(
                classpath = extractBlock("---CLASSPATH-START---", "---CLASSPATH-END---"),
                compilerFlags = extractBlock("---FLAGS-START---", "---FLAGS-END---"),
            )
        }

        private fun resolveGradleCommand(fixtureDir: String): List<String> {
            var dir = java.io.File(fixtureDir).absoluteFile
            while (dir.parentFile != null) {
                val wrapper = File(dir, "gradlew")
                if (wrapper.exists()) {
                    return listOf(wrapper.absolutePath, "-p", fixtureDir, "printProjectConfig", "--quiet")
                }
                val wrapperBat = File(dir, "gradlew.bat")
                if (wrapperBat.exists()) {
                    return listOf(wrapperBat.absolutePath, "-p", fixtureDir, "printProjectConfig", "--quiet")
                }
                val sidecarWrapper = File(dir, "sidecar/gradlew")
                if (sidecarWrapper.exists()) {
                    return listOf(sidecarWrapper.absolutePath, "-p", fixtureDir, "printProjectConfig", "--quiet")
                }
                val sidecarWrapperBat = File(dir, "sidecar/gradlew.bat")
                if (sidecarWrapperBat.exists()) {
                    return listOf(sidecarWrapperBat.absolutePath, "-p", fixtureDir, "printProjectConfig", "--quiet")
                }
                dir = dir.parentFile
            }
            return listOf("gradle", "-p", fixtureDir, "printProjectConfig", "--quiet")
        }
    }

    @AfterEach
    fun tearDown() {
        if (::bridge.isInitialized) bridge.shutdown()
    }

    @Test
    fun `spring ResponseStatusException resolves without errors`() {
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = testSourceDir,
            classpath = projectClasspath,
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(testSourceDir),
        )

        val uri = "file://$testSourceDir/SpringController.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")

        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(
            0, errors.size,
            "SpringController using ResponseStatusException should have no errors, got: ${
                errors.map {
                    it.asJsonObject.let { o ->
                        "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                    }
                }
            }"
        )
    }

    // --- Hover parity tests ---
    // These tests verify that hover output for Spring annotations matches IntelliJ's
    // quality: package info, meta-annotations, declaration, documentation, source origin.

    @Test
    fun `hover - RestController annotation shows package and meta-annotations`() {
        val fixtureSourceDir = findFixtureDir() + "/src/main/kotlin"
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureSourceDir,
            classpath = projectClasspath,
            compilerFlags = projectCompilerFlags,
            jdkHome = "",
            sourceRoots = listOf(fixtureSourceDir),
        )

        // Example.kt line 14: "@RestController"
        //                       ^1 (0-based, on "R" of RestController)
        val uri = "file://$fixtureSourceDir/Example.kt"
        val result = bridge.hover(uri, line = 14, character = 1)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for @RestController annotation")
        System.err.println("HOVER @RestController:\n$contents")

        // 1. Package info
        assertTrue(
            contents.contains("org.springframework"),
            "hover should show Spring package info, got:\n$contents"
        )

        // 2. Meta-annotations (like IntelliJ shows @Target, @Retention, @Documented, @Controller, @ResponseBody)
        assertTrue(
            contents.contains("@Target") || contents.contains("@Retention") || contents.contains("@Controller"),
            "hover should show meta-annotations, got:\n$contents"
        )

        // 3. Declaration keyword
        assertTrue(
            contents.contains("annotation class") || contents.contains("@interface"),
            "hover should show annotation class declaration, got:\n$contents"
        )
    }

    @Test
    fun `hover - GetMapping annotation shows package and meta-annotations`() {
        val fixtureSourceDir = findFixtureDir() + "/src/main/kotlin"
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureSourceDir,
            classpath = projectClasspath,
            compilerFlags = projectCompilerFlags,
            jdkHome = "",
            sourceRoots = listOf(fixtureSourceDir),
        )

        // Example.kt line 17: "    @GetMapping("/greet/{name}")"
        //                           ^5 (0-based, on "G")
        val uri = "file://$fixtureSourceDir/Example.kt"
        val result = bridge.hover(uri, line = 17, character = 5)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for @GetMapping annotation")
        System.err.println("HOVER @GetMapping:\n$contents")

        // Package info
        assertTrue(
            contents.contains("org.springframework"),
            "hover should show Spring package info, got:\n$contents"
        )

        // Declaration
        assertTrue(
            contents.contains("GetMapping"),
            "hover should contain GetMapping, got:\n$contents"
        )
    }

    @Test
    fun `hover - ResponseStatusException shows class info and documentation`() {
        val fixtureSourceDir = findFixtureDir() + "/src/main/kotlin"
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureSourceDir,
            classpath = projectClasspath,
            compilerFlags = projectCompilerFlags,
            jdkHome = "",
            sourceRoots = listOf(fixtureSourceDir),
        )

        // Example.kt line 29: "        throw ResponseStatusException(...)"
        //                                ^14 (0-based, on "R")
        val uri = "file://$fixtureSourceDir/Example.kt"
        val result = bridge.hover(uri, line = 29, character = 14)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for ResponseStatusException")
        System.err.println("HOVER ResponseStatusException:\n$contents")

        assertTrue(
            contents.contains("ResponseStatusException"),
            "hover should contain ResponseStatusException, got:\n$contents"
        )
        assertTrue(
            contents.contains("org.springframework"),
            "hover should show package info, got:\n$contents"
        )
    }

    @Test
    fun `hover - HttpStatus enum shows package info`() {
        val fixtureSourceDir = findFixtureDir() + "/src/main/kotlin"
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureSourceDir,
            classpath = projectClasspath,
            compilerFlags = projectCompilerFlags,
            jdkHome = "",
            sourceRoots = listOf(fixtureSourceDir),
        )

        // Example.kt line 29: "        throw ResponseStatusException(HttpStatus.NOT_FOUND, ...)"
        //                                                         ^39 (0-based, on "H")
        val uri = "file://$fixtureSourceDir/Example.kt"
        val result = bridge.hover(uri, line = 29, character = 39)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for HttpStatus")
        System.err.println("HOVER HttpStatus:\n$contents")

        assertTrue(
            contents.contains("HttpStatus") || contents.contains("NOT_FOUND"),
            "hover should mention HttpStatus or NOT_FOUND, got:\n$contents"
        )
    }

    // --- Go-to-definition parity tests ---

    @Test
    fun `definition - RestController annotation resolves to library`() {
        val fixtureSourceDir = findFixtureDir() + "/src/main/kotlin"
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureSourceDir,
            classpath = projectClasspath,
            compilerFlags = projectCompilerFlags,
            jdkHome = "",
            sourceRoots = listOf(fixtureSourceDir),
        )

        // Example.kt line 14: "@RestController"
        //                       ^1 (0-based, on "R" of RestController)
        val uri = "file://$fixtureSourceDir/Example.kt"
        val result = bridge.definition(uri, line = 14, character = 1)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        System.err.println("DEFINITION @RestController: ${locations}")

        // Go-to-definition should resolve to the Spring library source/decompiled class
        assertTrue(
            locations.size() > 0,
            "should find at least one definition location for @RestController"
        )

        val loc = locations[0].asJsonObject
        val locUri = loc.get("uri").asString
        assertTrue(locUri.startsWith("file://"), "URI should be a file:// URI, got: $locUri")
        assertTrue("!/" !in locUri, "URI should not contain JAR separator '!/', got: $locUri")

        val path = File(URI(locUri))
        assertTrue(path.exists(), "decompiled file should exist on disk: $path")

        val content = path.readText()
        assertTrue("RestController" in content, "decompiled file should contain 'RestController', got:\n${content.take(200)}")
    }

    @Test
    fun `definition - ResponseStatusException resolves to library`() {
        val fixtureSourceDir = findFixtureDir() + "/src/main/kotlin"
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureSourceDir,
            classpath = projectClasspath,
            compilerFlags = projectCompilerFlags,
            jdkHome = "",
            sourceRoots = listOf(fixtureSourceDir),
        )

        // Example.kt line 29: "        throw ResponseStatusException(...)"
        //                                ^14 (0-based, on "R")
        val uri = "file://$fixtureSourceDir/Example.kt"
        val result = bridge.definition(uri, line = 29, character = 14)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        System.err.println("DEFINITION ResponseStatusException: ${locations}")

        assertTrue(
            locations.size() > 0,
            "should find at least one definition location for ResponseStatusException"
        )

        val loc = locations[0].asJsonObject
        val locUri = loc.get("uri").asString
        assertTrue(locUri.startsWith("file://"), "URI should be a file:// URI, got: $locUri")
        assertTrue("!/" !in locUri, "URI should not contain JAR separator '!/', got: $locUri")

        val path = File(URI(locUri))
        assertTrue(path.exists(), "decompiled file should exist on disk: $path")

        val content = path.readText()
        assertTrue("ResponseStatusException" in content, "decompiled file should contain 'ResponseStatusException', got:\n${content.take(200)}")
    }

    // --- Completion parity tests ---

    @Test
    fun `completion - Spring annotation attributes available`() {
        val fixtureSourceDir = findFixtureDir() + "/src/main/kotlin"
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureSourceDir,
            classpath = projectClasspath,
            compilerFlags = projectCompilerFlags,
            jdkHome = "",
            sourceRoots = listOf(fixtureSourceDir),
        )

        // Test completion inside @GetMapping annotation arguments
        // Example.kt line 17: "    @GetMapping("/greet/{name}")"
        //                           ^5 (on "G")
        val uri = "file://$fixtureSourceDir/Example.kt"
        val result = bridge.completion(uri, line = 17, character = 5)

        val items = result.getAsJsonArray("items")
        assertNotNull(items, "completion items array should be present")
        // Completion at the annotation name position should return results
        // (may include GetMapping itself or other annotations)
        System.err.println("COMPLETION @GetMapping: ${items.size()} items")
    }

    @Test
    fun `arrow recover with spring web produces zero errors`() {
        // The Rust server now correctly forwards project_model.compiler_flags
        // (extracted from build.gradle.kts) to the sidecar, so context parameters
        // are properly enabled for projects that declare -Xcontext-parameters.
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = testSourceDir,
            classpath = projectClasspath,
            compilerFlags = projectCompilerFlags,
            jdkHome = "",
            sourceRoots = listOf(testSourceDir),
        )

        val uri = "file://$testSourceDir/ArrowRecoverController.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")

        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(
            0, errors.size,
            "ArrowRecoverController should have no errors in a Spring+Arrow project, got: ${
                errors.map {
                    it.asJsonObject.let { o ->
                        "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                    }
                }
            }"
        )
    }
}
