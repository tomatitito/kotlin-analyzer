package dev.kouros.sidecar

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PebbleSpringIndexTest {
    private lateinit var bridge: CompilerBridge
    private lateinit var fixtureRoot: File
    private lateinit var kotlinUri: String
    private lateinit var detailTemplateUri: String
    private lateinit var summaryTemplateUri: String

    @BeforeEach
    fun setUp() {
        fixtureRoot = findFixtureRoot()
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = fixtureRoot.absolutePath,
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(fixtureRoot.resolve("src/main/kotlin").absolutePath),
        )

        kotlinUri = "file://${fixtureRoot.resolve("src/main/kotlin/UsersController.kt").absolutePath}"
        detailTemplateUri = "file://${fixtureRoot.resolve("src/main/resources/templates/users/detail.peb").absolutePath}"
        summaryTemplateUri = "file://${fixtureRoot.resolve("src/main/resources/templates/users/summary.peb").absolutePath}"

        bridge.updatePebbleFile(detailTemplateUri, fixtureRoot.resolve("src/main/resources/templates/users/detail.peb").readText())
        bridge.updatePebbleFile(summaryTemplateUri, fixtureRoot.resolve("src/main/resources/templates/users/summary.peb").readText())
    }

    @AfterEach
    fun tearDown() {
        bridge.shutdown()
    }

    @Test
    fun `definition maps string return view names to pebble templates`() {
        val result = bridge.definition(kotlinUri, line = 17, character = 16)
        val locations = result.getAsJsonArray("locations")

        assertEquals(1, locations.size())
        assertEquals(detailTemplateUri, locations[0].asJsonObject.get("uri").asString)
    }

    @Test
    fun `definition maps ModelAndView constructor view names to pebble templates`() {
        val result = bridge.definition(kotlinUri, line = 22, character = 41)
        val locations = result.getAsJsonArray("locations")

        assertEquals(1, locations.size())
        assertEquals(summaryTemplateUri, locations[0].asJsonObject.get("uri").asString)
    }

    @Test
    fun `pebble root variables navigate to kotlin addAttribute producer`() {
        val result = bridge.pebbleDefinition(detailTemplateUri, line = 1, character = 4)
        val locations = result.getAsJsonArray("locations")

        assertEquals(1, locations.size())
        val location = locations[0].asJsonObject
        assertEquals(kotlinUri, location.get("uri").asString)
        assertEquals(15, location.get("line").asInt)
        assertEquals(8, location.get("column").asInt)
        assertEquals("User", location.get("type").asString)
    }

    @Test
    fun `pebble root variables navigate to kotlin addObject producer`() {
        val result = bridge.pebbleDefinition(summaryTemplateUri, line = 1, character = 4)
        val locations = result.getAsJsonArray("locations")

        assertEquals(1, locations.size())
        val location = locations[0].asJsonObject
        assertEquals(kotlinUri, location.get("uri").asString)
        assertEquals(21, location.get("line").asInt)
        assertEquals(8, location.get("column").asInt)
        assertEquals("User", location.get("type").asString)
    }

    @Test
    fun `unsupported dynamic spring view names fail conservatively`() {
        val result = bridge.definition(kotlinUri, line = 30, character = 17)
        assertTrue(result.getAsJsonArray("locations").isEmpty)
    }

    private fun findFixtureRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            val fixture = File(dir, "tests/fixtures/pebble-spring-mvc")
            if (fixture.exists()) return fixture
            dir = dir.parentFile
        }
        error("Could not find tests/fixtures/pebble-spring-mvc from ${System.getProperty("user.dir")}")
    }
}
