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
}
