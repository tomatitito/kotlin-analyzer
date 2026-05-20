package dev.kouros.sidecar

import com.google.gson.JsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests covering the KaScopeNameFilter pushdown introduced for
 * ticket ka-t7gp. Asserts that completion still surfaces every member when no
 * prefix has been typed and that a typed prefix narrows results consistent with
 * the previous client-side filtering semantics.
 *
 * These tests run against the same minimal-classpath setup as
 * CompilerBridgeIntegrationTest, so they only rely on intrinsic String/List
 * members rather than stdlib extension functions.
 */
class BridgeCompletionPrefixFilterTest {

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

    @Test
    fun `dot member completion with empty prefix returns full member set`() {
        val uri = "file://$testSourceDir/scratch/PrefixFilterEmptyDot.kt"
        val content = """
            fun useString() {
                val s = "hello"
                s.
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // line is 1-based, character is 0-based offset within the line.
        // Line 3 is "    s.", so column 6 sits right after the dot.
        val result = bridge.completion(uri, line = 3, character = 6, triggerCharacter = ".")
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "empty-prefix dot completion should return items array")

        val labels = items.labels()
        // Confirm a broad set of String members is returned. With an empty
        // prefix the filter degrades to `{ true }`, so we should see methods
        // across the alphabet (l-, i-, h-, e-, t-, ...).
        assertTrue(labels.size >= 5, "empty-prefix dot completion should return many members, got: $labels")
        val expectedAny = listOf("length", "isEmpty", "hashCode", "equals", "toString")
        val matched = expectedAny.filter { it in labels }
        assertTrue(
            matched.size >= 3,
            "empty-prefix dot completion should expose multiple intrinsic String members, " +
                "expected at least 3 of $expectedAny in $labels (reason=${result.get("reason")?.asString})",
        )
    }

    @Test
    fun `dot member completion with typed prefix returns only prefix matches`() {
        val uri = "file://$testSourceDir/scratch/PrefixFilterTypedDot.kt"
        val content = """
            fun useString() {
                val s = "hello"
                s.le
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Line 3 is "    s.le", so column 8 sits at the end of the typed prefix "le".
        val result = bridge.completion(uri, line = 3, character = 8)
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "typed-prefix dot completion should return items array")

        val labels = items.labels()
        assertTrue(
            labels.isNotEmpty(),
            "typed-prefix dot completion should return at least one match for 'le', " +
                "got empty (reason=${result.get("reason")?.asString})",
        )
        assertTrue(
            labels.any { it.equals("length", ignoreCase = true) },
            "typed-prefix dot completion should include 'length', got: $labels",
        )
        // Every returned label must be a case-insensitive prefix match — this
        // mirrors the previous client-side semantics and the Analysis API
        // filter must not loosen it.
        val violators = labels.filterNot { it.startsWith("le", ignoreCase = true) }
        assertTrue(
            violators.isEmpty(),
            "typed-prefix dot completion must only return prefix matches, got non-matches: $violators",
        )
    }

    @Test
    fun `typed-prefix member results are a subset of empty-prefix results`() {
        val baseUri = "file://$testSourceDir/scratch/PrefixFilterSubsetBase.kt"
        val baseContent = """
            fun useString() {
                val s = "hello"
                s.
            }
        """.trimIndent()
        bridge.updateFile(baseUri, baseContent)
        val baseResult = bridge.completion(baseUri, line = 3, character = 6, triggerCharacter = ".")
        val baseLabels = baseResult.getAsJsonArray("items").labels().toSet()
        assertTrue(baseLabels.isNotEmpty(), "baseline empty-prefix completion must return items")

        val typedUri = "file://$testSourceDir/scratch/PrefixFilterSubsetTyped.kt"
        val typedContent = """
            fun useString() {
                val s = "hello"
                s.le
            }
        """.trimIndent()
        bridge.updateFile(typedUri, typedContent)
        val typedResult = bridge.completion(typedUri, line = 3, character = 8)
        val typedLabels = typedResult.getAsJsonArray("items").labels()
        assertTrue(typedLabels.isNotEmpty(), "typed-prefix completion must return items")

        // Correctness check: every typed-prefix item appeared in the empty-prefix
        // result. The push-down filter is allowed to drop non-matching symbols
        // but must not introduce new ones.
        val novel = typedLabels.filter { it !in baseLabels }
        assertTrue(
            novel.isEmpty(),
            "typed-prefix completion must not introduce labels missing from the " +
                "empty-prefix baseline, got novel: $novel",
        )
        // And the typed-prefix list must strictly narrow the baseline.
        assertTrue(
            typedLabels.size < baseLabels.size,
            "typed-prefix completion should fetch fewer symbols than empty prefix " +
                "(typed=${typedLabels.size} base=${baseLabels.size})",
        )
    }

    @Test
    fun `scope completion with typed prefix returns only prefix matches`() {
        val uri = "file://$testSourceDir/scratch/PrefixFilterScopeTyped.kt"
        val content = """
            class Carrot
            class Coconut
            class Pineapple
            fun outer() {
                val carryOn = 1
                Car
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Line 6 is "    Car", column 7 sits after the typed prefix "Car".
        val result = bridge.completion(uri, line = 6, character = 7)
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "scope completion should return items array")

        val labels = items.labels()
        assertTrue(
            labels.isNotEmpty(),
            "scope completion with prefix 'Car' should return items, " +
                "got empty (reason=${result.get("reason")?.asString})",
        )
        // At least one of the Car-prefixed declarations must surface.
        assertTrue(
            labels.any { it == "Carrot" } || labels.any { it == "carryOn" },
            "scope completion should include Car-prefixed declarations, got: ${labels.take(20)}",
        )
        // Local fixture declarations we know are in scope but should be filtered
        // out at the Analysis API by the prefix filter.
        val scopeLeaks = labels.filter { it == "Coconut" || it == "Pineapple" }
        assertTrue(
            scopeLeaks.isEmpty(),
            "scope completion must filter non-matching local declarations out at the Analysis API, " +
                "leaked: $scopeLeaks (labels=${labels.take(30)})",
        )
    }

    @Test
    fun `dot member completion on user class with typed prefix narrows KaClassType members`() {
        val uri = "file://$testSourceDir/scratch/PrefixFilterClassTypeTyped.kt"
        val content = """
            class Fruit {
                fun bake() = Unit
                fun blend() = Unit
                fun slice() = Unit
                val ripeness: Int = 0
            }
            fun useFruit() {
                val f = Fruit()
                f.bl
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Line 9 is "    f.bl"; column 8 sits at the end of the typed prefix "bl".
        val result = bridge.completion(uri, line = 9, character = 8)
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "typed-prefix class-member completion should return items array")

        val labels = items.labels()
        assertTrue(
            labels.isNotEmpty(),
            "typed-prefix class-member completion should return matches for 'bl', " +
                "got empty (reason=${result.get("reason")?.asString})",
        )
        // The KaClassType.memberScope branch should surface user-defined members
        // for the prefix.
        assertTrue(
            labels.any { it == "blend" },
            "typed-prefix class-member completion should include 'blend', got: $labels",
        )
        // Members that do not match the prefix must be filtered at the Analysis
        // API rather than slipping through the KaClassType branch unfiltered.
        val leaks = labels.filter { it == "slice" || it == "ripeness" }
        assertTrue(
            leaks.isEmpty(),
            "typed-prefix class-member completion must filter non-matching members " +
                "out at the Analysis API, leaked: $leaks (labels=$labels)",
        )
        // And every returned label must be a case-insensitive prefix match.
        val violators = labels.filterNot { it.startsWith("bl", ignoreCase = true) }
        assertTrue(
            violators.isEmpty(),
            "typed-prefix class-member completion must only return prefix matches, " +
                "got non-matches: $violators",
        )
    }

    @Test
    fun `dot member completion on user class with empty prefix returns all KaClassType members`() {
        val uri = "file://$testSourceDir/scratch/PrefixFilterClassTypeEmpty.kt"
        val content = """
            class Fruit {
                fun bake() = Unit
                fun blend() = Unit
                fun slice() = Unit
                val ripeness: Int = 0
            }
            fun useFruit() {
                val f = Fruit()
                f.
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Line 9 is "    f."; column 6 sits right after the dot.
        val result = bridge.completion(uri, line = 9, character = 6, triggerCharacter = ".")
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "empty-prefix class-member completion should return items array")

        val labels = items.labels()
        // With an empty prefix the filter degrades to `{ true }`, so all
        // user-declared members on the KaClassType receiver must still surface.
        val expected = listOf("bake", "blend", "slice", "ripeness")
        val missing = expected.filterNot { it in labels }
        assertTrue(
            missing.isEmpty(),
            "empty-prefix class-member completion must surface every declared member, " +
                "missing: $missing (labels=$labels, reason=${result.get("reason")?.asString})",
        )
    }

    private fun JsonArray.labels(): List<String> =
        (0 until size()).map { get(it).asJsonObject.get("label")?.asString ?: "" }
}
