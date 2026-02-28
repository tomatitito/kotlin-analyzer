package dev.kouros.sidecar.proptest

import dev.kouros.sidecar.CompilerBridge
import net.jqwik.api.*
import net.jqwik.api.lifecycle.AfterProperty
import net.jqwik.api.lifecycle.BeforeProperty
import com.google.gson.JsonObject

class DiagnosticStabilityPropertyTest {

    private lateinit var bridge: CompilerBridge
    private val testSourceDir = System.getProperty("user.dir") + "/src/test/resources/test-sources"
    // Use an existing file's URI so the Analysis API associates the virtual
    // content with the source module (necessary for member reference resolution).
    private val virtualUri = "file://$testSourceDir/Clean.kt"

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

    @Provide
    fun snippets(): Arbitrary<KotlinSnippet> = snippetArbitrary()

    @Provide
    fun mutations(): Arbitrary<Mutation> = mutationArbitrary()

    /**
     * Core property: diagnostics are stable under revert.
     *
     * 1. Analyze the original snippet → d0
     * 2. Apply mutation, analyze → d1
     * 3. Revert to original, analyze → d2
     * 4. Assert d2 == d0 (stability)
     */
    @Property(tries = 50)
    fun diagnosticsAreStableUnderRevert(
        @ForAll("snippets") snippet: KotlinSnippet,
        @ForAll("mutations") mutation: Mutation,
    ) {
        // 1. Analyze original
        bridge.updateFile(virtualUri, snippet.source)
        val d0 = extractDiagnostics(bridge.analyze(virtualUri))

        // 2. Mutate and analyze
        val mutatedSource = mutation.apply(snippet.source)
        bridge.updateFile(virtualUri, mutatedSource)
        val d1 = extractDiagnostics(bridge.analyze(virtualUri))

        // 3. Revert and analyze
        bridge.updateFile(virtualUri, snippet.source)
        val d2 = extractDiagnostics(bridge.analyze(virtualUri))

        // 4. Stability: d2 must equal d0
        assert(d0 == d2) {
            buildString {
                appendLine("Diagnostics not stable after mutation revert!")
                appendLine("Snippet: ${snippet.name}")
                appendLine("Mutation: ${mutation.name}")
                appendLine("Source:")
                appendLine(snippet.source)
                appendLine("Mutated source:")
                appendLine(mutatedSource)
                appendLine("d0 (original):  $d0")
                appendLine("d1 (mutated):   $d1")
                appendLine("d2 (reverted):  $d2")
            }
        }
    }

    /**
     * Property: original snippets should compile without errors.
     *
     * This validates that our snippet generator only produces valid code.
     */
    @Property(tries = 100)
    fun generatedSnippetsCompileCleanly(
        @ForAll("snippets") snippet: KotlinSnippet,
    ) {
        bridge.updateFile(virtualUri, snippet.source)
        val diagnostics = extractDiagnostics(bridge.analyze(virtualUri))
        val errors = diagnostics.filter { it.severity == "ERROR" }

        assert(errors.isEmpty()) {
            buildString {
                appendLine("Generated snippet should compile without errors!")
                appendLine("Snippet: ${snippet.name}")
                appendLine("Source:")
                appendLine(snippet.source)
                appendLine("Errors: $errors")
            }
        }
    }

    /**
     * Property: if a mutation changes the source, it should not reduce the number of errors.
     *
     * This is a soft check — if the mutation actually changed the source text,
     * the mutated version should have at least as many errors as the original.
     * (Most mutations should introduce NEW errors.)
     */
    @Property(tries = 50)
    fun mutationDoesNotReduceErrors(
        @ForAll("snippets") snippet: KotlinSnippet,
        @ForAll("mutations") mutation: Mutation,
    ) {
        val mutatedSource = mutation.apply(snippet.source)

        // Skip if mutation didn't change anything
        Assume.that(mutatedSource != snippet.source)

        bridge.updateFile(virtualUri, snippet.source)
        val originalErrors = extractDiagnostics(bridge.analyze(virtualUri))
            .count { it.severity == "ERROR" }

        bridge.updateFile(virtualUri, mutatedSource)
        val mutatedErrors = extractDiagnostics(bridge.analyze(virtualUri))
            .count { it.severity == "ERROR" }

        assert(mutatedErrors >= originalErrors) {
            buildString {
                appendLine("Mutation reduced error count!")
                appendLine("Snippet: ${snippet.name}")
                appendLine("Mutation: ${mutation.name}")
                appendLine("Original errors: $originalErrors")
                appendLine("Mutated errors: $mutatedErrors")
                appendLine("Source:")
                appendLine(snippet.source)
                appendLine("Mutated source:")
                appendLine(mutatedSource)
            }
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Normalized diagnostic for comparison — excludes position info
     * since mutations shift line/column numbers.
     */
    private data class DiagnosticEntry(
        val severity: String,
        val code: String,
        val message: String,
    )

    private fun extractDiagnostics(result: JsonObject): List<DiagnosticEntry> {
        val array = result.getAsJsonArray("diagnostics") ?: return emptyList()
        return array.map { element ->
            val obj = element.asJsonObject
            DiagnosticEntry(
                severity = obj.get("severity")?.asString ?: "",
                code = obj.get("code")?.asString ?: "",
                message = obj.get("message")?.asString ?: "",
            )
        }.sortedWith(compareBy({ it.severity }, { it.code }, { it.message }))
    }
}
