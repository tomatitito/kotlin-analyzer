package dev.kouros.sidecar.proptest

import dev.kouros.sidecar.CompilerBridge
import net.jqwik.api.*
import net.jqwik.api.lifecycle.AfterProperty
import net.jqwik.api.lifecycle.BeforeProperty
import java.io.File

/**
 * Properties for session file isolation after updateFile.
 *
 * When an on-disk file is edited via updateFile (simulating textDocument/didChange),
 * the sidecar creates a LightVirtualFile for analysis because the session file's
 * content no longer matches. The key invariant is:
 *
 *   **Declarations from the stale session file must NOT be visible when analysing
 *   the updated (virtual) content.**
 *
 * If they leak, two bugs manifest:
 *   1. REDECLARATION — the updated file re-declares names also in the stale copy.
 *   2. Phantom resolution — references to names that were deleted in the edit
 *      still resolve through the stale copy, hiding real UNRESOLVED_REFERENCE errors.
 *
 * Both properties below target the same root cause: [CompilerBridge.findKtFile]
 * creates a LightVirtualFile alongside the session file instead of replacing it.
 */
class RedeclarationPropertyTest {

    private lateinit var bridge: CompilerBridge
    private val testSourceDir = System.getProperty("user.dir") + "/src/test/resources/test-sources"

    // Clean.kt exists on disk and is loaded into the session.
    // Its on-disk content declares `class Greeter` and `fun main`.
    private val cleanUri = "file://$testSourceDir/Clean.kt"

    @BeforeProperty
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

    @AfterProperty
    fun tearDown() {
        bridge.shutdown()
    }

    // -------------------------------------------------------------------
    // Providers
    // -------------------------------------------------------------------

    @Provide
    fun edits(): Arbitrary<ContentEdit> = contentEditArbitrary()

    @Provide
    fun freshClassNames(): Arbitrary<String> =
        Arbitraries.of("Alpha", "Beta", "Gamma", "Delta", "Epsilon")

    // -------------------------------------------------------------------
    // Property 1 — session declarations must not leak
    // -------------------------------------------------------------------

    /**
     * After replacing an on-disk file's content with something that does NOT
     * declare `Greeter`, any reference to `Greeter` must be unresolved.
     *
     * If the stale session file leaks, FIR resolves `Greeter` through it and
     * no UNRESOLVED_REFERENCE is reported — the assertion fails.
     */
    @Property(tries = 20)
    fun sessionDeclarationsMustNotLeakIntoVirtualFile(
        @ForAll("freshClassNames") className: String,
    ) {
        // Push content that references Greeter (from session file) without declaring it.
        val content = """
            class $className {
                fun greetWith(g: Greeter): String = g.greet()
            }
        """.trimIndent()

        bridge.updateFile(cleanUri, content)
        val result = bridge.analyze(cleanUri)
        val diagnostics = result.getAsJsonArray("diagnostics") ?: error("no diagnostics array")

        // Collect all diagnostic codes
        val codes = diagnostics.map { it.asJsonObject.get("code")?.asString ?: "" }

        assert("UNRESOLVED_REFERENCE" in codes) {
            buildString {
                appendLine("Session file leaked! Greeter should be unresolved but was resolved.")
                appendLine("Virtual class: $className")
                appendLine("Diagnostics: $codes")
                appendLine("Content pushed:")
                appendLine(content)
            }
        }
    }

    // -------------------------------------------------------------------
    // Property 2 — semantics-preserving edits must not introduce REDECLARATION
    // -------------------------------------------------------------------

    /**
     * Editing an on-disk file's content with a semantics-preserving change
     * (add comment, change string, etc.) must not introduce REDECLARATION
     * diagnostics. If the stale session file and the LightVirtualFile both
     * contribute to the module, every shared declaration triggers REDECLARATION.
     */
    @Property(tries = 30)
    fun editingOnDiskFileNeverCausesRedeclaration(
        @ForAll("edits") edit: ContentEdit,
    ) {
        val original = File("$testSourceDir/Clean.kt").readText()
        val edited = edit.apply(original)

        // Skip edits that didn't change anything
        Assume.that(edited != original)

        // Baseline: analyze with on-disk content (session file, no virtual override)
        bridge.updateFile(cleanUri, original)
        val baselineRedeclarations = countRedeclarations(bridge.analyze(cleanUri))

        // Push the edited content — this is where the bug manifests
        bridge.updateFile(cleanUri, edited)
        val editedRedeclarations = countRedeclarations(bridge.analyze(cleanUri))

        assert(editedRedeclarations == baselineRedeclarations) {
            buildString {
                appendLine("updateFile introduced REDECLARATION diagnostics!")
                appendLine("Edit: ${edit.name}")
                appendLine("Baseline REDECLARATION count: $baselineRedeclarations")
                appendLine("After edit REDECLARATION count: $editedRedeclarations")
                appendLine("Edited content:")
                appendLine(edited)
            }
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private fun countRedeclarations(result: com.google.gson.JsonObject): Int {
        val diagnostics = result.getAsJsonArray("diagnostics") ?: return 0
        return diagnostics.count { elem ->
            elem.asJsonObject.get("code")?.asString == "REDECLARATION"
        }
    }
}

/**
 * A semantics-preserving edit to Kotlin source text.
 * Every edit preserves the set of declarations (classes, functions, properties)
 * so that the only way a REDECLARATION can appear is if the compiler sees
 * two copies of the file.
 */
data class ContentEdit(
    val name: String,
    val apply: (String) -> String,
)

fun contentEditArbitrary(): Arbitrary<ContentEdit> {
    val commentTexts = Arbitraries.of("edited", "modified", "changed", "updated", "reviewed")
    val greetings = Arbitraries.of("Hi", "Hey", "Howdy", "Greetings", "Yo")

    return Arbitraries.oneOf(
        // Append a comment at the end of file
        commentTexts.map { text ->
            ContentEdit("appendComment($text)") { source ->
                "$source\n// $text"
            }
        },

        // Prepend a comment at the top of file
        commentTexts.map { text ->
            ContentEdit("prependComment($text)") { source ->
                "// $text\n$source"
            }
        },

        // Insert a comment in the middle of the file
        commentTexts.map { text ->
            ContentEdit("insertMiddleComment($text)") { source ->
                val lines = source.lines().toMutableList()
                if (lines.size > 2) {
                    lines.add(lines.size / 2, "// $text")
                }
                lines.joinToString("\n")
            }
        },

        // Change the greeting string literal (preserves structure, changes a value)
        greetings.map { greeting ->
            ContentEdit("changeGreeting($greeting)") { source ->
                source.replace("Hello", greeting)
            }
        },

        // Add trailing whitespace / newlines
        Arbitraries.of(1, 2, 3).map { n ->
            ContentEdit("addTrailingNewlines($n)") { source ->
                source + "\n".repeat(n)
            }
        },
    )
}
