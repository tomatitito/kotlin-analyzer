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
    private lateinit var carouselTemplateUri: String
    private lateinit var includeParentTemplateUri: String
    private lateinit var includeChildTemplateUri: String
    private lateinit var includeUnsupportedParentTemplateUri: String
    private lateinit var includeUnsupportedChildTemplateUri: String

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
        carouselTemplateUri = "file://${fixtureRoot.resolve("src/main/resources/templates/outfits/carousel.peb").absolutePath}"
        includeParentTemplateUri = "file://${fixtureRoot.resolve("src/main/resources/templates/users/include-parent.peb").absolutePath}"
        includeChildTemplateUri = "file://${fixtureRoot.resolve("src/main/resources/templates/users/include-child.peb").absolutePath}"
        includeUnsupportedParentTemplateUri = "file://${fixtureRoot.resolve("src/main/resources/templates/users/include-unsupported-parent.peb").absolutePath}"
        includeUnsupportedChildTemplateUri = "file://${fixtureRoot.resolve("src/main/resources/templates/users/include-unsupported-child.peb").absolutePath}"

        bridge.updatePebbleFile(detailTemplateUri, fixtureRoot.resolve("src/main/resources/templates/users/detail.peb").readText())
        bridge.updatePebbleFile(summaryTemplateUri, fixtureRoot.resolve("src/main/resources/templates/users/summary.peb").readText())
        bridge.updatePebbleFile(carouselTemplateUri, fixtureRoot.resolve("src/main/resources/templates/outfits/carousel.peb").readText())
        bridge.updatePebbleFile(includeParentTemplateUri, fixtureRoot.resolve("src/main/resources/templates/users/include-parent.peb").readText())
        bridge.updatePebbleFile(includeChildTemplateUri, fixtureRoot.resolve("src/main/resources/templates/users/include-child.peb").readText())
        bridge.updatePebbleFile(
            includeUnsupportedParentTemplateUri,
            fixtureRoot.resolve("src/main/resources/templates/users/include-unsupported-parent.peb").readText(),
        )
        bridge.updatePebbleFile(
            includeUnsupportedChildTemplateUri,
            fixtureRoot.resolve("src/main/resources/templates/users/include-unsupported-child.peb").readText(),
        )
    }

    @AfterEach
    fun tearDown() {
        bridge.shutdown()
    }

    @Test
    fun `definition maps string return view names to pebble templates`() {
        val result = bridge.definition(kotlinUri, line = 22, character = 16)
        val locations = result.getAsJsonArray("locations")

        assertEquals(1, locations.size())
        assertEquals(detailTemplateUri, locations[0].asJsonObject.get("uri").asString)
    }

    @Test
    fun `definition maps ModelAndView constructor view names to pebble templates`() {
        val result = bridge.definition(kotlinUri, line = 27, character = 41)
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
        assertEquals(20, location.get("line").asInt)
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
        assertEquals(26, location.get("line").asInt)
        assertEquals(8, location.get("column").asInt)
        assertEquals("User", location.get("type").asString)
    }

    @Test
    fun `pebble property segments navigate to kotlin property declarations`() {
        val result = bridge.pebbleDefinition(detailTemplateUri, line = 1, character = 9)
        val locations = result.getAsJsonArray("locations")

        assertEquals(1, locations.size())
        val location = locations[0].asJsonObject
        assertEquals(kotlinUri, location.get("uri").asString)
        assertEquals(11, location.get("line").asInt)
        assertEquals(16, location.get("column").asInt)
    }

    @Test
    fun `kotlin references include indexed pebble usages`() {
        val propertyReferences = bridge.references(kotlinUri, line = 11, character = 20)
        val propertyLocations = propertyReferences.getAsJsonArray("locations")
        assertTrue((0 until propertyLocations.size()).any { index ->
            val location = propertyLocations[index].asJsonObject
            location.get("uri").asString == detailTemplateUri &&
                location.get("line").asInt == 1 &&
                location.get("column").asInt == 8
        })

        val producerReferences = bridge.references(kotlinUri, line = 20, character = 12)
        val producerLocations = producerReferences.getAsJsonArray("locations")
        assertTrue((0 until producerLocations.size()).any { index ->
            val location = producerLocations[index].asJsonObject
            location.get("uri").asString == detailTemplateUri &&
                location.get("line").asInt == 1 &&
                location.get("column").asInt == 3
        })
    }

    @Test
    fun `unsupported dynamic spring view names fail conservatively`() {
        val result = bridge.definition(kotlinUri, line = 35, character = 17)
        assertTrue(result.getAsJsonArray("locations").isEmpty)
    }

    @Test
    fun `pebble for-loop aliases navigate to collection element properties`() {
        val pathResult = bridge.pebbleDefinition(carouselTemplateUri, line = 3, character = 11)
        val pathLocations = pathResult.getAsJsonArray("locations")

        assertEquals(1, pathLocations.size())
        val pathLocation = pathLocations[0].asJsonObject
        assertEquals(kotlinUri, pathLocation.get("uri").asString)
        assertEquals(12, pathLocation.get("line").asInt)
        assertEquals(18, pathLocation.get("column").asInt)

        val relativePathResult = bridge.pebbleDefinition(carouselTemplateUri, line = 4, character = 11)
        val relativePathLocations = relativePathResult.getAsJsonArray("locations")

        assertEquals(1, relativePathLocations.size())
        val relativePathLocation = relativePathLocations[0].asJsonObject
        assertEquals(kotlinUri, relativePathLocation.get("uri").asString)
        assertEquals(12, relativePathLocation.get("line").asInt)
        assertEquals(36, relativePathLocation.get("column").asInt)
    }

    @Test
    fun `pebvariable hints resolve typed method definitions and kotlin references`() {
        val definition = bridge.pebbleDefinition(carouselTemplateUri, line = 6, character = 18)
        val definitionLocations = definition.getAsJsonArray("locations")

        assertEquals(1, definitionLocations.size())
        val definitionLocation = definitionLocations[0].asJsonObject
        assertEquals(kotlinUri, definitionLocation.get("uri").asString)
        assertEquals(15, definitionLocation.get("line").asInt)
        assertEquals(4, definitionLocation.get("column").asInt)

        val references = bridge.references(kotlinUri, line = 15, character = 8)
        val referenceLocations = references.getAsJsonArray("locations")
        assertTrue((0 until referenceLocations.size()).any { index ->
            val location = referenceLocations[index].asJsonObject
            location.get("uri").asString == carouselTemplateUri &&
                location.get("line").asInt == 6 &&
                location.get("column").asInt == 15
        })
    }

    @Test
    fun `include-with forwarded variables navigate back to parent kotlin producers`() {
        val forwardedUserDefinition = bridge.pebbleDefinition(includeChildTemplateUri, line = 1, character = 4)
        val forwardedUserLocations = forwardedUserDefinition.getAsJsonArray("locations")

        assertEquals(1, forwardedUserLocations.size())
        val forwardedUserLocation = forwardedUserLocations[0].asJsonObject
        assertEquals(kotlinUri, forwardedUserLocation.get("uri").asString)
        assertEquals(47, forwardedUserLocation.get("line").asInt)
        assertEquals(8, forwardedUserLocation.get("column").asInt)
        assertEquals("User", forwardedUserLocation.get("type").asString)

        val forwardedPropertyDefinition = bridge.pebbleDefinition(includeChildTemplateUri, line = 1, character = 18)
        val forwardedPropertyLocations = forwardedPropertyDefinition.getAsJsonArray("locations")

        assertEquals(1, forwardedPropertyLocations.size())
        val forwardedPropertyLocation = forwardedPropertyLocations[0].asJsonObject
        assertEquals(kotlinUri, forwardedPropertyLocation.get("uri").asString)
        assertEquals(11, forwardedPropertyLocation.get("line").asInt)
        assertEquals(16, forwardedPropertyLocation.get("column").asInt)

        val nestedForwardedDefinition = bridge.pebbleDefinition(includeChildTemplateUri, line = 2, character = 4)
        val nestedForwardedLocations = nestedForwardedDefinition.getAsJsonArray("locations")

        assertEquals(1, nestedForwardedLocations.size())
        val nestedForwardedLocation = nestedForwardedLocations[0].asJsonObject
        assertEquals(kotlinUri, nestedForwardedLocation.get("uri").asString)
        assertEquals(11, nestedForwardedLocation.get("line").asInt)
        assertEquals(16, nestedForwardedLocation.get("column").asInt)
        assertEquals("String", nestedForwardedLocation.get("type").asString)
    }

    @Test
    fun `kotlin references include usages from include-with child templates`() {
        val userProducerReferences = bridge.references(kotlinUri, line = 47, character = 12)
        val userProducerLocations = userProducerReferences.getAsJsonArray("locations")
        assertTrue((0 until userProducerLocations.size()).any { index ->
            val location = userProducerLocations[index].asJsonObject
            location.get("uri").asString == includeChildTemplateUri &&
                location.get("line").asInt == 1 &&
                location.get("column").asInt == 3
        })

        val userNameReferences = bridge.references(kotlinUri, line = 11, character = 20)
        val userNameLocations = userNameReferences.getAsJsonArray("locations")
        assertTrue((0 until userNameLocations.size()).any { index ->
            val location = userNameLocations[index].asJsonObject
            location.get("uri").asString == includeChildTemplateUri &&
                location.get("line").asInt == 2 &&
                location.get("column").asInt == 3
        })
    }

    @Test
    fun `spring model attribute calls jump to pebble usages`() {
        val definition = bridge.definition(kotlinUri, line = 48, character = 15)
        val definitionLocations = definition.getAsJsonArray("locations")
        assertTrue((0 until definitionLocations.size()).any { index ->
            val location = definitionLocations[index].asJsonObject
            location.get("uri").asString == includeChildTemplateUri &&
                location.get("line").asInt == 1 &&
                location.get("column").asInt == 3
        })

        val references = bridge.references(kotlinUri, line = 48, character = 15)
        val referenceLocations = references.getAsJsonArray("locations")
        assertTrue((0 until referenceLocations.size()).any { index ->
            val location = referenceLocations[index].asJsonObject
            location.get("uri").asString == includeChildTemplateUri &&
                location.get("line").asInt == 1 &&
                location.get("column").asInt == 3
        })
    }

    @Test
    fun `unsupported include-with expressions fail conservatively`() {
        val result = bridge.pebbleDefinition(includeUnsupportedChildTemplateUri, line = 1, character = 4)
        assertTrue(result.getAsJsonArray("locations").isEmpty)
    }

    @Test
    fun `kotlin references include pebble loop alias usages`() {
        val pathReferences = bridge.references(kotlinUri, line = 12, character = 22)
        val pathLocations = pathReferences.getAsJsonArray("locations")
        assertTrue((0 until pathLocations.size()).any { index ->
            val location = pathLocations[index].asJsonObject
            location.get("uri").asString == carouselTemplateUri &&
                location.get("line").asInt == 3 &&
                location.get("column").asInt == 10
        })

        val outfitsProducerReferences = bridge.references(kotlinUri, line = 39, character = 13)
        val outfitsProducerLocations = outfitsProducerReferences.getAsJsonArray("locations")
        assertTrue((0 until outfitsProducerLocations.size()).any { index ->
            val location = outfitsProducerLocations[index].asJsonObject
            location.get("uri").asString == carouselTemplateUri &&
                location.get("line").asInt == 3 &&
                location.get("column").asInt == 3
        })
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
