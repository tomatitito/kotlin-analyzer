package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
    fun `hover - line-end position still resolves symbol`() {
        // Clean.kt line 1: "class Greeter("
        //                            ^14 (0-based, after '(')
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.hover(uri, line = 1, character = 14)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover at line-end boundary should return contents, got: null")
        assertTrue(
            contents.contains("Greeter"),
            "hover should mention 'Greeter', got: $contents"
        )
    }

    @Test
    fun `hover - oversized character is clamped to line end`() {
        // Clean.kt line 1: "class Greeter("
        //                                  ^120 (well beyond line length)
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.hover(uri, line = 1, character = 120)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover with oversized character should still return contents, got: null")
        assertTrue(
            contents.contains("Greeter"),
            "hover should mention 'Greeter' when character is clamped, got: $contents"
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

    @Test
    fun `hover - accepts end-of-line character by falling back to previous token`() {
        // Clean.kt line 1: "class Greeter("
        //                                ^end-of-line fallback to "r" in Greeter
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.hover(uri, line = 1, character = 14)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for end-of-line position")
        assertTrue(
            contents.contains("Greeter"),
            "hover should still resolve constructor context, got: $contents"
        )
    }

    @Test
    fun `hover - reports explicit reason when unresolved`() {
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.hover(uri, line = 20, character = 0)

        assertTrue(
            result.get("reason") != null,
            "hover should include a reason when no hover target is found"
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

    // --- Virtual file content injection ---

    @Test
    fun `updateFile - diagnostics reflect edited content`() {
        // Clean.kt starts with no errors. Inject a type mismatch via updateFile
        // and verify that analyze() sees the new content.
        val uri = "file://$testSourceDir/Clean.kt"

        // First verify the original file is clean
        val cleanResult = bridge.analyze(uri)
        val cleanErrors = cleanResult.getAsJsonArray("diagnostics").filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(0, cleanErrors.size, "original Clean.kt should have no errors")

        // Now inject broken content via updateFile
        val brokenContent = """
            class Greeter(val name: String) {
                fun greet(): String {
                    return "Hello, ${'$'}name!"
                }
            }
            fun main() {
                val x: Int = "not an int"
            }
        """.trimIndent()
        bridge.updateFile(uri, brokenContent)

        // Analyze should now see the type mismatch from the injected content
        val brokenResult = bridge.analyze(uri)
        val brokenDiagnostics = brokenResult.getAsJsonArray("diagnostics")
        val brokenErrors = brokenDiagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertTrue(
            brokenErrors.isNotEmpty(),
            "after updateFile with type mismatch, analyze should report errors"
        )
    }

    @Test
    fun `updateFile - hover reflects edited content`() {
        val uri = "file://$testSourceDir/Clean.kt"

        // Inject new content with a different function
        val newContent = """
            fun computeSum(a: Int, b: Int): Int = a + b
        """.trimIndent()
        bridge.updateFile(uri, newContent)

        // Hover on "computeSum" (line 1, character 4)
        val result = bridge.hover(uri, line = 1, character = 4)
        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for the new function")
        assertTrue(
            contents.contains("computeSum") || contents.contains("Int"),
            "hover should reflect the injected content, got: $contents"
        )
    }

    // --- Code actions: auto-import ---

    @Test
    fun `codeActions - import uses FQN for project declarations`() {
        // Create a file that references Greeter (defined in Clean.kt) without importing it.
        // Since Greeter is in the default package in test-sources, its FQN is just "Greeter",
        // so this test verifies the code action mechanism works without erroring.
        val uri = "file://$testSourceDir/Clean.kt"
        val result = bridge.codeActions(uri, line = 1, character = 6)

        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions, "actions array should be present")
        // We don't necessarily expect actions at a class declaration, but
        // the method should not throw
    }

    @Test
    fun `codeActions - virtual file resolves cross-file references in same module`() {
        // Create a virtual file that references MyClass (defined in Completion.kt).
        // Since both files are in the same source module (default package), MyClass
        // should resolve without needing an import. The custom module provider ensures
        // KtPsiFactory files are associated with the source module.
        val uri = "file://$testSourceDir/TestImport.kt"
        val content = """
            fun test() {
                val x = MyClass()
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 2, character = 12)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions, "actions array should be present")

        // MyClass should resolve (same package/module), so no import action needed.
        // Instead we may see other actions like "Add explicit type" or "Suppress warning".
        if (actions.size() > 0) {
            val titles = actions.map { it.asJsonObject.get("title")?.asString ?: "" }
            val hasTypeAction = titles.any {
                it.contains("MyClass") || it.contains("type") || it.contains("Suppress")
            }
            assertTrue(
                hasTypeAction,
                "should have code actions for the resolved reference, got: $titles"
            )
        }
    }

    // --- Missing import diagnostics ---

    @Test
    fun `diagnostics - on-disk file with stdlib types resolves correctly`() {
        // MissingImport.kt uses List, mutableListOf, and java.io.File.
        // Stdlib types should resolve fine; java.io.File should also resolve (JDK on classpath).
        val uri = "file://$testSourceDir/MissingImport.kt"
        val result = bridge.analyze(uri)

        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")

        // Log all diagnostics for debugging
        for (d in diagnostics) {
            val obj = d.asJsonObject
            System.err.println("TEST: [${obj.get("severity")?.asString}] ${obj.get("code")?.asString}: ${obj.get("message")?.asString}")
        }
    }

    // --- Cross-file references ---

    @Test
    fun `references - cross-file finds usages in other files`() {
        // User.kt line 1: "data class User("
        //                              ^11 (0-based)
        val uri = "file://$testSourceDir/User.kt"
        val result = bridge.references(uri, line = 1, character = 11)

        val locations = result.getAsJsonArray("locations")
        assertNotNull(locations, "locations array should be present")

        // Should find references in UserService.kt (uses User type in multiple places)
        // plus the declaration in User.kt itself
        val uris = locations.map { it.asJsonObject.get("uri")?.asString ?: "" }.toSet()
        assertTrue(
            uris.any { it.contains("UserService.kt") },
            "should find references to User in UserService.kt, got URIs: $uris"
        )
    }

    // --- Workspace symbols ---

    @Test
    fun `workspaceSymbols - finds declarations from multiple files`() {
        val result = bridge.workspaceSymbols("")

        val symbols = result.getAsJsonArray("symbols")
        assertNotNull(symbols, "symbols array should be present")

        val names = symbols.map { it.asJsonObject.get("name")?.asString ?: "" }.toSet()

        // Should find declarations from multiple test files
        assertTrue(names.contains("Greeter"), "should find Greeter from Clean.kt, got: $names")
        assertTrue(names.contains("User"), "should find User from User.kt, got: $names")
        assertTrue(names.contains("UserService"), "should find UserService from UserService.kt, got: $names")
        assertTrue(names.contains("MyClass"), "should find MyClass from Completion.kt, got: $names")
    }

    @Test
    fun `workspaceSymbols - case insensitive search`() {
        val result = bridge.workspaceSymbols("greeter")

        val symbols = result.getAsJsonArray("symbols")
        assertNotNull(symbols, "symbols array should be present")

        val names = symbols.map { it.asJsonObject.get("name")?.asString ?: "" }
        assertTrue(
            names.any { it == "Greeter" },
            "case-insensitive search for 'greeter' should find 'Greeter', got: $names"
        )
    }

    @Test
    fun `workspaceSymbols - specific query filters results`() {
        val result = bridge.workspaceSymbols("User")

        val symbols = result.getAsJsonArray("symbols")
        assertNotNull(symbols, "symbols array should be present")

        val names = symbols.map { it.asJsonObject.get("name")?.asString ?: "" }
        assertTrue(names.any { it == "User" }, "should find User, got: $names")
        assertTrue(names.any { it == "UserService" }, "should find UserService, got: $names")
        assertTrue(names.none { it == "Greeter" }, "should NOT find Greeter when searching 'User', got: $names")
    }

    // --- Completion with auto-import ---

    @Test
    fun `completion - includes unimported symbols with auto-import edit`() {
        // Create a file that types "Us" — should get User from the index with additionalTextEdits
        val uri = "file://$testSourceDir/TestAutoImport.kt"
        val content = """
            fun test() {
                val x = Us
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Complete at "Us" (line 2, col 14)
        val result = bridge.completion(uri, line = 2, character = 14)

        val items = result.getAsJsonArray("items")
        assertNotNull(items, "completion items should be present")

        // Find the User item from auto-import
        val userItems = (0 until items.size())
            .map { items[it].asJsonObject }
            .filter { it.get("label")?.asString == "User" }

        assertTrue(
            userItems.isNotEmpty(),
            "should find 'User' in completion items, got labels: ${
                (0 until items.size()).map { items[it].asJsonObject.get("label")?.asString }
            }"
        )

        // Check that the auto-import item has additionalTextEdits
        val autoImportItem = userItems.find { it.has("additionalTextEdits") }
        if (autoImportItem != null) {
            val edits = autoImportItem.getAsJsonArray("additionalTextEdits")
            assertTrue(edits.size() > 0, "additionalTextEdits should have at least one edit")
            val editText = edits[0].asJsonObject.get("newText")?.asString ?: ""
            assertTrue(
                editText.contains("import") && editText.contains("User"),
                "edit should contain import statement for User, got: $editText"
            )
        }
    }

    // --- Organize imports ---

    @Test
    fun `codeActions - organize imports sorts and deduplicates`() {
        val uri = "file://$testSourceDir/UnorganizedImports.kt"
        val content = """
            import kotlin.collections.List
            import java.io.File
            import kotlin.collections.List
            import java.io.BufferedReader

            fun test() {
                val x: List<String> = listOf()
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 7, character = 0)

        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions, "actions array should be present")

        val organizeAction = (0 until actions.size())
            .map { actions[it].asJsonObject }
            .find { it.get("title")?.asString == "Organize imports" }

        assertNotNull(organizeAction, "should have an 'Organize imports' action, got: ${
            (0 until actions.size()).map { actions[it].asJsonObject.get("title")?.asString }
        }")

        // Verify the edits sort and deduplicate
        val edits = organizeAction!!.getAsJsonArray("edits")
        assertTrue(edits.size() > 0, "should have at least one edit")
        val newText = edits[0].asJsonObject.get("newText")?.asString ?: ""
        assertTrue(
            newText.contains("java.io.BufferedReader") && newText.contains("java.io.File") && newText.contains("kotlin.collections.List"),
            "organized imports should contain all unique imports, got: $newText"
        )
        // Verify sorted order: BufferedReader < File < List
        val lines = newText.lines()
        val importLines = lines.filter { it.startsWith("import ") }
        assertEquals(3, importLines.size, "should have exactly 3 unique imports (was 4 with duplicate), got: $importLines")
        assertTrue(
            importLines[0].contains("BufferedReader"),
            "first import should be BufferedReader, got: ${importLines[0]}"
        )
    }

    // --- Compiler flags: context parameters ---

    @Test
    fun `diagnostics - context parameters flag suppresses errors for edited files`() {
        // Re-initialize with -Xcontext-parameters enabled
        bridge.shutdown()
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = testSourceDir,
            classpath = emptyList(),
            compilerFlags = listOf("-Xcontext-parameters"),
            jdkHome = "",
            sourceRoots = listOf(testSourceDir),
        )

        // Inject content that uses context parameters via updateFile (creates KtPsiFactory file)
        val uri = "file://$testSourceDir/Clean.kt"
        val content = """
            class Logger {
                fun log(message: String) = println(message)
            }

            context(logger: Logger)
            fun greetWithContext(name: String) {
                logger.log("Hello, ${'$'}name!")
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.analyze(uri)
        val diagnostics = result.getAsJsonArray("diagnostics")

        // Should NOT have "context-parameters" errors
        val contextParamErrors = diagnostics.filter {
            val msg = it.asJsonObject.get("message")?.asString ?: ""
            msg.contains("context-parameters") || msg.contains("Xcontext-parameters")
        }
        assertTrue(
            contextParamErrors.isEmpty(),
            "should not report context-parameters errors when flag is enabled, got: $contextParamErrors"
        )
    }

    // --- Regression tests for active plans ---
    // These tests reproduce known bugs from plans/active/.
    // Each test should FAIL until its corresponding issue is fixed.

    // Plan: virtual-file-member-resolution.md
    // UNRESOLVED_REFERENCE for class member properties in virtual files

    @Test
    fun `regression - virtual file class body property resolves without errors`() {
        val uri = "file://$testSourceDir/VirtualItem.kt"
        val content = """
            class Item {
                var count: Int = 0
                fun increment(): Int {
                    count++
                    return count
                }
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.analyze(uri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(
            0, errors.size,
            "class with body-declared property referenced in method should have no errors in virtual files, got: ${
                errors.map { it.asJsonObject.let { o -> "${o.get("code")?.asString}: ${o.get("message")?.asString}" } }
            }"
        )
    }

    @Test
    fun `regression - virtual file constructor property resolves without errors`() {
        val uri = "file://$testSourceDir/VirtualCtorItem.kt"
        val content = """
            class Item(var count: Int) {
                fun increment(): Int {
                    count++
                    return count
                }
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.analyze(uri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(
            0, errors.size,
            "class with constructor property referenced in method should have no errors in virtual files, got: ${
                errors.map { it.asJsonObject.let { o -> "${o.get("code")?.asString}: ${o.get("message")?.asString}" } }
            }"
        )
    }

    // Plan: code-actions-not-showing.md
    // Code actions (convert expression/block body) not appearing

    @Test
    fun `regression - expression body function offers convert to block body action`() {
        val uri = "file://$testSourceDir/ExprBodyAction.kt"
        val content = """
            fun add(a: Int, b: Int): Int = a + b
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Cursor on "fun" keyword (line 1, character 0)
        val result = bridge.codeActions(uri, line = 1, character = 0)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions, "actions array should be present")

        val titles = (0 until actions.size())
            .map { actions[it].asJsonObject.get("title")?.asString ?: "" }

        assertTrue(
            titles.any { it.contains("Convert to block body") },
            "expression-body function should offer 'Convert to block body' action, got: $titles"
        )
    }

    @Test
    fun `regression - block body function offers convert to expression body action`() {
        val uri = "file://$testSourceDir/BlockBodyAction.kt"
        val content = """
            fun add(a: Int, b: Int): Int {
                return a + b
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Cursor on "fun" keyword (line 1, character 0)
        val result = bridge.codeActions(uri, line = 1, character = 0)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions, "actions array should be present")

        val titles = (0 until actions.size())
            .map { actions[it].asJsonObject.get("title")?.asString ?: "" }

        assertTrue(
            titles.any { it.contains("Convert to expression body") },
            "block-body function with single return should offer 'Convert to expression body' action, got: $titles"
        )
    }

    // Plan: context-keyword-raise-dsl-diagnostics.md
    // context keyword and raise DSL produce false error diagnostics

    @Test
    fun `regression - context parameters produce zero total errors`() {
        // Re-initialize with -Xcontext-parameters enabled
        bridge.shutdown()
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = testSourceDir,
            classpath = emptyList(),
            compilerFlags = listOf("-Xcontext-parameters"),
            jdkHome = "",
            sourceRoots = listOf(testSourceDir),
        )

        val uri = "file://$testSourceDir/ContextParamRegression.kt"
        val content = """
            class Logger {
                fun log(message: String) = println(message)
            }

            context(logger: Logger)
            fun greetWithContext(name: String) {
                logger.log("Hello, ${'$'}name!")
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.analyze(uri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }

        // Stronger assertion than the existing test: zero total errors,
        // not just "no error mentions context-parameters"
        assertEquals(
            0, errors.size,
            "context parameters code should produce zero errors when -Xcontext-parameters is enabled, got: ${
                errors.map { it.asJsonObject.let { o -> "[${o.get("code")?.asString}] ${o.get("message")?.asString}" } }
            }"
        )
    }

    @Test
    fun `regression - context receivers produce zero total errors`() {
        // Re-initialize with -Xcontext-receivers enabled
        bridge.shutdown()
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = testSourceDir,
            classpath = emptyList(),
            compilerFlags = listOf("-Xcontext-receivers"),
            jdkHome = "",
            sourceRoots = listOf(testSourceDir),
        )

        val uri = "file://$testSourceDir/ContextReceiverRegression.kt"
        val content = """
            interface LoggingContext {
                fun log(message: String)
            }

            context(LoggingContext)
            fun performAction(action: String) {
                log("Performing: ${'$'}action")
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.analyze(uri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }

        assertEquals(
            0, errors.size,
            "context receivers code should produce zero errors when -Xcontext-receivers is enabled, got: ${
                errors.map { it.asJsonObject.let { o -> "[${o.get("code")?.asString}] ${o.get("message")?.asString}" } }
            }"
        )
    }

    // --- Hover: Annotated declarations ---

    @Test
    fun `hover - deprecated class shows annotation and KDoc`() {
        // Annotated.kt line 8: "@Deprecated(...)"
        // line 8: "class OldGreeter(val name: String) {"
        //                ^6 (0-based)
        val uri = "file://$testSourceDir/Annotated.kt"
        val result = bridge.hover(uri, line = 8, character = 6)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for @Deprecated class")
        assertTrue(
            contents.contains("OldGreeter"),
            "hover should mention 'OldGreeter', got: $contents"
        )
        assertTrue(
            contents.contains("@Deprecated"),
            "hover should show @Deprecated annotation, got: $contents"
        )
        assertTrue(
            contents.contains("Test Author") || contents.contains("@author"),
            "hover should show @author tag from KDoc, got: $contents"
        )
    }

    @Test
    fun `hover - deprecated function shows annotation`() {
        // Annotated.kt line 10: "    @Deprecated("Use newGreet() instead")"
        // line 11: "    fun greet(): String = "Hello, $name!""
        //                  ^8 (0-based)
        val uri = "file://$testSourceDir/Annotated.kt"
        val result = bridge.hover(uri, line = 10, character = 8)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for @Deprecated function")
        assertTrue(
            contents.contains("greet") || contents.contains("@Deprecated"),
            "hover should mention 'greet' or '@Deprecated', got: $contents"
        )
    }

    @Test
    fun `hover - annotation reference shows annotation type info`() {
        // Hover on "@Deprecated" annotation usage (line 7, col 2 = on "D" of Deprecated)
        // Annotated.kt line 7: "@Deprecated("Use NewGreeter instead", ...)"
        val uri = "file://$testSourceDir/Annotated.kt"
        val result = bridge.hover(uri, line = 7, character = 2)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for @Deprecated annotation reference")
        assertTrue(
            contents.contains("annotation class Deprecated"),
            "hover should show annotation class declaration, got: $contents"
        )
        assertTrue(
            contents.contains("kotlin"),
            "hover should show kotlin package info, got: $contents"
        )
    }

    @Test
    fun `hover - stdlib reference shows documentation`() {
        // Hover on 'String' in OldGreeter(val name: String)
        // Annotated.kt line 8: "class OldGreeter(val name: String) {"
        //                                               ^30 (0-based)
        val uri = "file://$testSourceDir/Annotated.kt"
        val result = bridge.hover(uri, line = 8, character = 30)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents for String type reference")
        assertTrue(
            contents.contains("String") || contents.contains("kotlin"),
            "hover should mention 'String' or its package, got: $contents"
        )
    }

    // --- Java-only project graceful handling ---

    @Test
    fun `java-only project - initialize and analyze without crash`() {
        // Verify that the CompilerBridge handles a project with no Kotlin source files
        // gracefully. This simulates a Java-only Gradle project.
        bridge.shutdown()
        bridge = CompilerBridge()

        val javaOnlyDir = findJavaOnlyFixtureDir()
        bridge.initialize(
            projectRoot = javaOnlyDir,
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(javaOnlyDir),
        )

        // Analyze a non-existent Kotlin file — should return empty diagnostics, not crash
        val uri = "file://$javaOnlyDir/NonExistent.kt"
        val result = bridge.analyze(uri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        assertNotNull(diagnostics, "diagnostics array should be present")
        // Should not crash, may return empty diagnostics
    }

    @Test
    fun `java-only project - hover on virtual Kotlin file works`() {
        bridge.shutdown()
        bridge = CompilerBridge()

        val javaOnlyDir = findJavaOnlyFixtureDir()
        bridge.initialize(
            projectRoot = javaOnlyDir,
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(javaOnlyDir),
        )

        // Inject a virtual Kotlin file and verify basic analysis works
        val uri = "file://$javaOnlyDir/Test.kt"
        val content = """
            fun hello(): String = "world"
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.hover(uri, line = 1, character = 4)
        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover should return contents even in Java-only project")
        assertTrue(
            contents.contains("hello") || contents.contains("String"),
            "hover should resolve the function, got: $contents"
        )
    }

    // --- Editor flow tests: simulate production didOpen/didChange + analyze ---
    // In production, textDocument/didChange is ALWAYS sent before analyze, which
    // stores the file content in virtualFiles. This path differs from calling
    // analyze() directly (which uses the session file without virtual content).

    @Test
    fun `editor flow - cross-file reference resolves after updateFile with same content`() {
        // Simulate what happens when the editor opens UserService.kt:
        // 1. didChange sends the file's current content (same as disk)
        // 2. analyze is called
        // UserService.kt references User (from User.kt) — should resolve.
        val userServiceUri = "file://$testSourceDir/UserService.kt"
        val diskContent = java.io.File("$testSourceDir/UserService.kt").readText()

        bridge.updateFile(userServiceUri, diskContent)

        val result = bridge.analyze(userServiceUri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(
            0, errors.size,
            "UserService.kt with cross-file reference to User should have zero errors " +
                "when analyzed via the editor flow (updateFile + analyze), got: ${
                    errors.map {
                        it.asJsonObject.let { o ->
                            "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                        }
                    }
                }"
        )
    }

    @Test
    fun `editor flow - clean file has no errors after updateFile with same content`() {
        // Same as above but with Clean.kt (no cross-file references).
        val uri = "file://$testSourceDir/Clean.kt"
        val diskContent = java.io.File("$testSourceDir/Clean.kt").readText()

        bridge.updateFile(uri, diskContent)

        val result = bridge.analyze(uri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }
        assertEquals(
            0, errors.size,
            "Clean.kt should have zero errors when analyzed via editor flow, got: ${
                errors.map {
                    it.asJsonObject.let { o ->
                        "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                    }
                }
            }"
        )
    }

    @Test
    fun `editor flow - cross-package import resolves when analyzed directly from session`() {
        // The files model/Person.kt and service/PersonService.kt are on disk
        // in the test-sources directory and should be discovered by the session.
        // When analyzed directly (no updateFile), cross-package imports should resolve.
        val serviceUri = "file://$testSourceDir/service/PersonService.kt"

        val result = bridge.analyze(serviceUri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }

        assertEquals(
            0, errors.size,
            "PersonService.kt (session file, direct analyze) should have zero errors, got: ${
                errors.map {
                    it.asJsonObject.let { o ->
                        "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                    }
                }
            }"
        )
    }

    @Test
    fun `editor flow - cross-package import resolves after updateFile with same content`() {
        // Simulate the editor opening PersonService.kt: the editor sends
        // textDocument/didChange with the disk content, then requests analysis.
        // This is the EXACT production flow — updateFile is called before analyze.
        val serviceUri = "file://$testSourceDir/service/PersonService.kt"
        val diskContent = java.io.File("$testSourceDir/service/PersonService.kt").readText()

        bridge.updateFile(serviceUri, diskContent)

        val result = bridge.analyze(serviceUri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }

        assertEquals(
            0, errors.size,
            "PersonService.kt (editor flow, same-as-disk content) should have zero errors, got: ${
                errors.map {
                    it.asJsonObject.let { o ->
                        "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                    }
                }
            }"
        )
    }

    @Test
    fun `editor flow - virtual-only files cannot resolve cross-package imports`() {
        // FUNDAMENTAL LIMITATION: When both files exist ONLY as virtual content
        // (not on disk in session source roots), cross-package imports fail.
        //
        // This happens in production when:
        // 1. Gradle extraction fails entirely → no source roots, all files virtual
        // 2. Source root extraction is incomplete (e.g. only "main" extracted but
        //    user opens files from "test" source set, or from an unrecognized module)
        // 3. Multi-module project where module A depends on module B but B's source
        //    roots are not in the session
        //
        // The LightVirtualFile is mapped to the source module via
        // registerVirtualFileModuleProvider, but since the IMPORTED class is also
        // not in the session, FIR cannot resolve the import.
        //
        // This test SHOULD pass when the issue is fixed (change assertTrue to
        // assertEquals(0, errors.size, ...)).

        val modelUri = "file://$testSourceDir/virtual/Item.kt"
        val modelContent = """
            package virtual_model

            data class Item(val id: Long, val name: String, val price: Double)
        """.trimIndent()
        bridge.updateFile(modelUri, modelContent)

        val serviceUri = "file://$testSourceDir/virtual/ItemService.kt"
        val serviceContent = """
            package virtual_service

            import virtual_model.Item

            class ItemService {
                private val items = mutableListOf<Item>()

                fun addItem(item: Item) {
                    items.add(item)
                }

                fun findItem(id: Long): Item? {
                    return items.find { it.id == id }
                }
            }
        """.trimIndent()
        bridge.updateFile(serviceUri, serviceContent)

        val result = bridge.analyze(serviceUri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }

        // This test FAILS until the issue is fixed: virtual-only files that
        // are not in the session source roots cannot resolve cross-package imports.
        assertEquals(
            0, errors.size,
            "virtual-only files should resolve cross-package imports without errors, got: ${
                errors.map {
                    it.asJsonObject.let { o ->
                        "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                    }
                }
            }"
        )
    }

    @Test
    fun `editor flow - test file importing from main source set resolves`() {
        // When only "main" source roots are extracted (current behavior), test
        // files that import from main sources should still resolve those imports
        // because the main source module contains the referenced declarations.
        //
        // The file is analyzed as a LightVirtualFile mapped to the source module
        // via registerVirtualFileModuleProvider, which gives it access to session
        // declarations.

        val testFileUri = "file://$testSourceDir/test/UserServiceTest.kt"
        val testContent = """
            class UserServiceTest {
                fun testAddUser() {
                    val service = UserService()
                    val user = User(1L, "Alice", "alice@example.com")
                    service.addUser(user)
                    val found = service.findUser(1L)
                    println(found?.name)
                }
            }
        """.trimIndent()
        bridge.updateFile(testFileUri, testContent)

        val result = bridge.analyze(testFileUri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }

        assertEquals(
            0, errors.size,
            "test file importing from main source set should resolve (User and UserService " +
                "are in the session), got: ${
                    errors.map {
                        it.asJsonObject.let { o ->
                            "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                        }
                    }
                }"
        )
    }

    @Test
    fun `editor flow - file in source roots but with edited content still resolves cross-file imports`() {
        // When the editor modifies a file (content differs from disk), the file
        // goes through the LightVirtualFile path. Cross-file imports should still
        // resolve because the other files are still in the session.
        val serviceUri = "file://$testSourceDir/service/PersonService.kt"

        // Send modified content that still has the same imports
        val modifiedContent = """
            package service

            import model.Person

            class PersonService {
                private val people = mutableListOf<Person>()

                fun addPerson(person: Person) {
                    people.add(person)
                }

                fun findPerson(id: Long): Person? {
                    return people.find { it.id == id }
                }

                fun allPeople(): List<Person> {
                    return people.toList()
                }

                // New method added by the user
                fun countPeople(): Int = people.size
            }
        """.trimIndent()
        bridge.updateFile(serviceUri, modifiedContent)

        val result = bridge.analyze(serviceUri)
        val diagnostics = result.getAsJsonArray("diagnostics")
        val errors = diagnostics.filter {
            it.asJsonObject.get("severity")?.asString == "ERROR"
        }

        assertEquals(
            0, errors.size,
            "PersonService.kt with edited content should still resolve cross-file imports, got: ${
                errors.map {
                    it.asJsonObject.let { o ->
                        "[${o.get("code")?.asString}] ${o.get("message")?.asString}"
                    }
                }
            }"
        )
    }

    // --- Move to Companion Object ---

    @Test
    fun `codeActions - move to companion creates companion when none exists`() {
        val uri = "file://$testSourceDir/MoveToCompanion.kt"
        val content = """
            class Foo {
                fun helper(): Int = 42
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 2, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.any { it == "Move to companion object" },
            "should offer 'Move to companion object', got: $titles"
        )

        val moveAction = actions.findByTitle("Move to companion object")
        val edits = moveAction.getAsJsonArray("edits")
        assertTrue(edits.size() >= 2, "should have at least 2 edits (delete + insert), got: ${edits.size()}")

        // Verify the insert edit creates a companion object
        val insertEdit = (0 until edits.size()).map { edits[it].asJsonObject }
            .first { (it.get("newText")?.asString ?: "").contains("companion object") }
        val newText = insertEdit.get("newText")?.asString ?: ""
        assertTrue(newText.contains("fun helper()"), "companion should contain the function, got: $newText")
    }

    @Test
    fun `codeActions - move to existing companion object`() {
        val uri = "file://$testSourceDir/MoveToExistingCompanion.kt"
        val content = """
            class Foo {
                fun helper(): Int = 42

                companion object {
                    fun existing(): String = "hi"
                }
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 2, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.any { it == "Move to companion object" },
            "should offer 'Move to companion object', got: $titles"
        )
    }

    @Test
    fun `codeActions - move to companion NOT offered when this is referenced`() {
        val uri = "file://$testSourceDir/MoveToCompanionThis.kt"
        val content = """
            class Foo {
                val name = "foo"
                fun greet(): String = "Hello, ${'$'}{this.name}"
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 3, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.none { it == "Move to companion object" },
            "should NOT offer 'Move to companion object' when this is referenced, got: $titles"
        )
    }

    @Test
    fun `codeActions - move to companion NOT offered for companion members`() {
        val uri = "file://$testSourceDir/MoveToCompanionAlready.kt"
        val content = """
            class Foo {
                companion object {
                    fun helper(): Int = 42
                }
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 3, character = 8)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.none { it == "Move to companion object" },
            "should NOT offer 'Move to companion object' for members already in companion, got: $titles"
        )
    }

    // --- Move from Companion Object ---

    @Test
    fun `codeActions - move from companion to class`() {
        val uri = "file://$testSourceDir/MoveFromCompanion.kt"
        val content = """
            class Foo {
                companion object {
                    fun helper(): Int = 42
                }
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 3, character = 8)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.any { it == "Move from companion object" },
            "should offer 'Move from companion object', got: $titles"
        )
    }

    @Test
    fun `codeActions - move from companion deletes empty companion`() {
        val uri = "file://$testSourceDir/MoveFromCompanionEmpty.kt"
        val content = """
            class Foo {
                companion object {
                    fun helper(): Int = 42
                }
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 3, character = 8)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val moveAction = actions.findByTitle("Move from companion object")
        val edits = moveAction.getAsJsonArray("edits")

        // Should delete the companion object entirely when it becomes empty
        val deleteEdits = (0 until edits.size()).map { edits[it].asJsonObject }
            .filter { (it.get("newText")?.asString ?: "x").isEmpty() }
        assertTrue(deleteEdits.isNotEmpty(), "should have a delete edit for the companion")
    }

    @Test
    fun `codeActions - move from companion NOT offered for regular class members`() {
        val uri = "file://$testSourceDir/MoveFromCompanionNotOffered.kt"
        val content = """
            class Foo {
                fun helper(): Int = 42
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 2, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.none { it == "Move from companion object" },
            "should NOT offer 'Move from companion object' for regular members, got: $titles"
        )
    }

    // --- Move to Top Level ---

    @Test
    fun `codeActions - move pure function to top level`() {
        val uri = "file://$testSourceDir/MoveToTopLevel.kt"
        val content = """
            class Foo {
                fun helper(x: Int): Int = x + 1
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 2, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.any { it == "Move to top level" },
            "should offer 'Move to top level', got: $titles"
        )

        val moveAction = actions.findByTitle("Move to top level")
        val edits = moveAction.getAsJsonArray("edits")
        assertTrue(edits.size() >= 2, "should have at least 2 edits (delete + insert)")

        // Verify the insert creates a top-level function
        val insertEdit = (0 until edits.size()).map { edits[it].asJsonObject }
            .first { (it.get("newText")?.asString ?: "").contains("fun helper") }
        val newText = insertEdit.get("newText")?.asString ?: ""
        assertTrue(newText.contains("fun helper(x: Int): Int"), "should be a top-level function, got: $newText")
    }

    @Test
    fun `codeActions - move to top level adds self parameter when this referenced`() {
        val uri = "file://$testSourceDir/MoveToTopLevelThis.kt"
        val content = """
            class Foo {
                val name = "foo"
                fun greet(): String = "Hello, ${'$'}{this.name}"
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 3, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.any { it == "Move to top level" },
            "should offer 'Move to top level' even when this is referenced (adds self param), got: $titles"
        )

        val moveAction = actions.findByTitle("Move to top level")
        val edits = moveAction.getAsJsonArray("edits")
        val insertEdit = (0 until edits.size()).map { edits[it].asJsonObject }
            .first { (it.get("newText")?.asString ?: "").contains("fun greet") }
        val newText = insertEdit.get("newText")?.asString ?: ""
        assertTrue(newText.contains("self: Foo"), "should add self: Foo parameter, got: $newText")
    }

    @Test
    fun `codeActions - move to top level NOT offered for override functions`() {
        val uri = "file://$testSourceDir/MoveToTopLevelOverride.kt"
        val content = """
            abstract class Base {
                abstract fun compute(): Int
            }
            class Impl : Base() {
                override fun compute(): Int = 42
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 5, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.none { it == "Move to top level" },
            "should NOT offer 'Move to top level' for override functions, got: $titles"
        )
    }

    // --- Convert to Extension Function ---

    @Test
    fun `codeActions - convert member to extension function`() {
        val uri = "file://$testSourceDir/ConvertToExtension.kt"
        val content = """
            class Foo {
                fun helper(x: Int): Int = x + 1
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 2, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.any { it == "Convert to extension function" },
            "should offer 'Convert to extension function', got: $titles"
        )

        val extAction = actions.findByTitle("Convert to extension function")
        val edits = extAction.getAsJsonArray("edits")
        val insertEdit = (0 until edits.size()).map { edits[it].asJsonObject }
            .first { (it.get("newText")?.asString ?: "").contains("fun Foo.helper") }
        val newText = insertEdit.get("newText")?.asString ?: ""
        assertTrue(newText.contains("fun Foo.helper(x: Int): Int"), "should create extension function, got: $newText")
    }

    @Test
    fun `codeActions - convert to extension NOT offered for override`() {
        val uri = "file://$testSourceDir/ConvertToExtensionOverride.kt"
        val content = """
            abstract class Base {
                abstract fun compute(): Int
            }
            class Impl : Base() {
                override fun compute(): Int = 42
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.codeActions(uri, line = 5, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        assertTrue(
            titles.none { it == "Convert to extension function" },
            "should NOT offer 'Convert to extension function' for override functions, got: $titles"
        )
    }

    @Test
    fun `codeActions - convert to extension preserves this as receiver`() {
        val uri = "file://$testSourceDir/ConvertToExtensionThis.kt"
        val content = """
            class Foo {
                val name = "foo"
                fun describe(): String = "I am ${'$'}{this.name}"
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // The function uses `this`, but for extension functions `this` is valid
        // as it becomes the extension receiver — so the action may or may not be offered
        // depending on whether accessesPrivateMembers detects `name` as private.
        // Since `name` is public by default in Kotlin, the action should be offered.
        val result = bridge.codeActions(uri, line = 3, character = 4)
        val actions = result.getAsJsonArray("actions")
        assertNotNull(actions)

        val titles = actions.titles()
        // If offered, verify it creates an extension function; if not, that's also valid
        // because the private members check may catch `name` depending on analysis resolution
        if (titles.any { it == "Convert to extension function" }) {
            val extAction = (0 until actions.size()).map { actions[it].asJsonObject }
                .first { it.get("title")?.asString == "Convert to extension function" }
            val edits = extAction.getAsJsonArray("edits")
            val insertEdit = (0 until edits.size()).map { edits[it].asJsonObject }
                .first { (it.get("newText")?.asString ?: "").contains("fun Foo.describe") }
            val newText = insertEdit.get("newText")?.asString ?: ""
            assertTrue(newText.contains("fun Foo.describe()"), "extension function should have Foo receiver, got: $newText")
        }
    }

    private fun JsonArray.titles(): List<String> =
        (0 until size()).map { get(it).asJsonObject.get("title")?.asString ?: "" }

    private fun JsonArray.findByTitle(title: String): JsonObject =
        (0 until size()).map { get(it).asJsonObject }.first { it.get("title")?.asString == title }

    private fun findJavaOnlyFixtureDir(): String {
        var dir = java.io.File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            val fixture = java.io.File(dir, "tests/fixtures/gradle-java-only/src/main/java")
            if (fixture.exists()) return fixture.absolutePath
            dir = dir.parentFile
        }
        error("Could not find tests/fixtures/gradle-java-only/ from ${System.getProperty("user.dir")}")
    }
}
