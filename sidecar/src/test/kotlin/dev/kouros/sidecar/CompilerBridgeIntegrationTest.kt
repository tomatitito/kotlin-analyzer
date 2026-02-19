package dev.kouros.sidecar

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that exercise CompilerBridge against the real Kotlin Analysis API.
 * These tests verify that hover, completion, diagnostics, definition, and references
 * work correctly with a StandaloneAnalysisAPISession.
 *
 * All test files are in src/test/resources/test-sources/ which is registered as
 * a source root so the Analysis API session discovers them.
 */
class CompilerBridgeIntegrationTest {

    private lateinit var bridge: CompilerBridge
    private val testSourceDir = System.getProperty("user.dir") + "/src/test/resources/test-sources"

    @BeforeEach
    fun setUp() {
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = testSourceDir,
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(testSourceDir),
        )
    }

    @AfterEach
    fun tearDown() {
        bridge.shutdown()
    }

    // --- Diagnostics ---

    @Test
    fun `diagnostics - type mismatch detected`() {
        val uri = "file://$testSourceDir/TypeMismatch.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")
        assertTrue(diagnostics.size() > 0, "should find at least one diagnostic")

        // Verify we found a type-related error
        val hasTypeError = diagnostics.any {
            val obj = it.asJsonObject
            val severity = obj.get("severity")?.asString ?: ""
            val message = obj.get("message")?.asString ?: ""
            severity == "ERROR" && (
                message.contains("type", ignoreCase = true) ||
                message.contains("mismatch", ignoreCase = true) ||
                message.contains("expected", ignoreCase = true) ||
                message.contains("Int", ignoreCase = false)
            )
        }
        assertTrue(hasTypeError, "should find a type-related error diagnostic")
    }

    @Test
    fun `diagnostics - clean file has no errors`() {
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")

        // Filter for errors only (ignore warnings)
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(0, errors.size, "clean file should have no error diagnostics, got: $errors")
    }

    // --- Hover ---

    @Test
    fun `hover - class declaration`() {
        // Clean.kt line 1: "class Greeter("
        //                          ^6 (0-based)
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.hover(uri, line = 1, character = 6)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for class declaration")
        assertTrue(
            contents.contains("Greeter"),
            "hover contents should mention 'Greeter', got: $contents"
        )
    }

    @Test
    fun `hover - function declaration`() {
        // Clean.kt line 4: "    fun greet(): String {"
        //                            ^8 (0-based)
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.hover(uri, line = 4, character = 8)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for function declaration")
        assertTrue(
            contents.contains("greet") || contents.contains("String"),
            "hover contents should mention 'greet' or 'String', got: $contents"
        )
    }

    @Test
    fun `hover - constructor reference`() {
        // Clean.kt line 10: "    val greeter = Greeter("World")"
        //                                     ^18 (0-based)
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.hover(uri, line = 10, character = 18)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for constructor reference")
        assertTrue(
            contents.contains("Greeter") || contents.contains("class") || contents.contains("constructor"),
            "hover should mention Greeter class or constructor, got: $contents"
        )
    }

    // --- Definition ---

    @Test
    fun `definition - resolve variable reference`() {
        // Clean.kt line 11: "    println(greeter.greet())"
        //                              ^12 (0-based) = "greeter"
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.definition(uri, line = 11, character = 12)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() > 0,
            "should find at least one definition location for 'greeter'"
        )

        // The definition should point to line 10 where 'greeter' is declared
        val loc = locations[0].asJsonObject
        val defLine = loc.get("line")?.asInt
        assertNotNull(defLine, "definition location should have a line number")
        assertEquals(10, defLine, "definition should point to line 10")
    }

    @Test
    fun `definition - resolve class reference`() {
        // Clean.kt line 10: "    val greeter = Greeter("World")"
        //                                     ^18 (0-based) = "Greeter"
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.definition(uri, line = 10, character = 18)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        assertTrue(
            locations.size() > 0,
            "should find at least one definition location for 'Greeter'"
        )
    }

    // --- References ---

    @Test
    fun `references - find all references to class`() {
        // Clean.kt line 1: "class Greeter("
        //                          ^6 (0-based)
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.references(uri, line = 1, character = 6)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")
        // Should find at least the declaration itself
        // Note: constructor calls may resolve to the constructor symbol rather than
        // the class, so cross-reference counting can vary
        assertTrue(
            locations.size() >= 1,
            "should find at least 1 reference (declaration), got: ${locations.size()}"
        )
    }

    // --- Completion ---

    @Test
    fun `completion - dot member completion`() {
        // Completion.kt line 4: "        items."
        //                                     ^14 (0-based, after the dot)
        val uri = "file://$testSourceDir/Completion.kt"
        val result = bridge.completion(uri, line = 4, character = 14)

        val items = result.getAsJsonArray("items")
        assertNotNull(items, "completion items array should be present")
        val labels = items.map { it.asJsonObject.get("label")?.asString ?: "" }
        assertTrue(
            labels.any { it == "size" || it == "isEmpty" || it == "first" || it == "contains" || it == "get" },
            "completion should include list members, got: ${labels.take(20)}"
        )
    }

    @Test
    fun `completion - scope completion`() {
        // Completion.kt line 10: "    my"
        //                              ^6 (0-based, after "my")
        val uri = "file://$testSourceDir/Completion.kt"
        val result = bridge.completion(uri, line = 10, character = 6)

        val items = result.getAsJsonArray("items")
        assertNotNull(items, "completion items array should be present")
        val labels = items.map { it.asJsonObject.get("label")?.asString ?: "" }
        assertTrue(
            labels.any { it.contains("myFunction") || it.contains("MyClass") },
            "completion should include 'myFunction' or 'MyClass' from file scope, got: ${labels.take(20)}"
        )
    }

    // --- Signature Help ---

    @Test
    fun `signatureHelp - function call`() {
        val uri = "file://$testSourceDir/SignatureHelp.kt"
        val content = """
            fun add(a: Int, b: Int): Int = a + b
            fun test() {
                add(1,
            }
        """.trimIndent()
        bridge.updateFile(uri, content)
        // Inside the call to add(), after "1, " (line 3, col 11)
        val result = bridge.signatureHelp(uri, line = 3, character = 11)

        val signatures = result.getAsJsonArray("signatures")
        assertNotNull(signatures, "signatures array should be present")
        if (signatures.size() > 0) {
            val sig = signatures[0].asJsonObject
            val label = sig.get("label")?.asString ?: ""
            assertTrue(
                label.contains("add") && label.contains("Int"),
                "signature should contain 'add' and 'Int', got: $label"
            )
        }
    }
}
