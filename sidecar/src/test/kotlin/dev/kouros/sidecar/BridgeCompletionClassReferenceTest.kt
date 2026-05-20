package dev.kouros.sidecar

import com.google.gson.JsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for class-reference member completion (sid-colf).
 *
 * Covers `EnumName.`, `ClassName.companionMethod`, and nested-class completion
 * — cases where the receiver is the class itself rather than an instance value.
 * The previous implementation walked instance scopes only, collapsing these to
 * the three inherited `Any` callables (`toString` / `equals` / `hashCode`).
 *
 * A regression test for `instanceValue.member` lives in
 * `BridgeCompletionPrefixFilterTest` and must remain green.
 */
class BridgeCompletionClassReferenceTest {

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
    fun `enum class-reference completion surfaces entries plus synthetic members`() {
        val uri = "file://$testSourceDir/scratch/EnumClassRefCompletion.kt"
        val content = """
            enum class AnfrageKontext {
                PDS,
                HOMEFEED,
                CHECKOUT,
            }

            fun useEnum() {
                AnfrageKontext.
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Line 8 is "    AnfrageKontext."; column 19 sits right after the dot.
        val result = bridge.completion(uri, line = 8, character = 19, triggerCharacter = ".")
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "enum class-reference completion should return items array")

        val labels = items.labels().toSet()
        assertTrue(
            labels.size >= 4,
            "enum class-reference completion should return at least 4 candidates (entries plus " +
                "synthetic members), got: $labels (reason=${result.get("reason")?.asString})",
        )

        val expectedEntries = listOf("PDS", "HOMEFEED", "CHECKOUT")
        val missingEntries = expectedEntries.filterNot { it in labels }
        assertTrue(
            missingEntries.isEmpty(),
            "enum class-reference completion must surface every enum entry, " +
                "missing: $missingEntries (labels=$labels)",
        )

        val expectedSynthetic = listOf("values", "valueOf", "entries")
        val foundSynthetic = expectedSynthetic.filter { it in labels }
        assertTrue(
            foundSynthetic.isNotEmpty(),
            "enum class-reference completion should surface at least one of the synthetic " +
                "members ${expectedSynthetic}, got: $labels",
        )

        // Inherited Any callables must not be surfaced through a static qualifier.
        val anyLeaks = labels.filter { it == "toString" || it == "equals" || it == "hashCode" }
        assertTrue(
            anyLeaks.isEmpty(),
            "enum class-reference completion must not surface inherited Any callables, " +
                "leaked: $anyLeaks (labels=$labels)",
        )
    }

    @Test
    fun `enum class-reference completion with typed prefix narrows results`() {
        val uri = "file://$testSourceDir/scratch/EnumClassRefCompletionPrefix.kt"
        val content = """
            enum class Color {
                RED,
                ROSE,
                BLUE,
                GREEN,
            }

            fun useColor() {
                Color.R
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Line 9 is "    Color.R"; column 11 sits at the end of the typed prefix "R".
        val result = bridge.completion(uri, line = 9, character = 11)
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "typed-prefix enum completion should return items array")

        val labels = items.labels()
        assertTrue(
            labels.isNotEmpty(),
            "typed-prefix enum completion should return matches for 'R', " +
                "got empty (reason=${result.get("reason")?.asString})",
        )
        val expected = setOf("RED", "ROSE")
        val missing = expected.filterNot { it in labels }
        assertTrue(
            missing.isEmpty(),
            "typed-prefix enum completion should surface R-prefixed entries, " +
                "missing: $missing (labels=$labels)",
        )
        // Every returned label must be a case-insensitive prefix match.
        val violators = labels.filterNot { it.startsWith("R", ignoreCase = true) }
        assertTrue(
            violators.isEmpty(),
            "typed-prefix enum completion must only return prefix matches, " +
                "got non-matches: $violators",
        )
    }

    @Test
    fun `class-reference completion surfaces companion callables`() {
        val uri = "file://$testSourceDir/scratch/CompanionClassRefCompletion.kt"
        val content = """
            class Widget {
                companion object {
                    fun create(): Widget = Widget()
                    val DEFAULT_SIZE: Int = 42
                }
            }

            fun useWidget() {
                Widget.
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Line 9 is "    Widget."; column 11 sits right after the dot.
        val result = bridge.completion(uri, line = 9, character = 11, triggerCharacter = ".")
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "companion class-reference completion should return items array")

        val labels = items.labels().toSet()
        assertTrue(
            "create" in labels,
            "companion class-reference completion must surface 'create' companion function, " +
                "got: $labels (reason=${result.get("reason")?.asString})",
        )
        assertTrue(
            "DEFAULT_SIZE" in labels,
            "companion class-reference completion must surface 'DEFAULT_SIZE' companion property, " +
                "got: $labels",
        )

        // Inherited Any callables must not be surfaced through a static qualifier.
        val anyLeaks = labels.filter { it == "toString" || it == "equals" || it == "hashCode" }
        assertTrue(
            anyLeaks.isEmpty(),
            "companion class-reference completion must not surface inherited Any callables, " +
                "leaked: $anyLeaks (labels=$labels)",
        )
    }

    @Test
    fun `nested class completion is surfaced via class-reference receiver`() {
        val uri = "file://$testSourceDir/scratch/NestedClassRefCompletion.kt"
        val content = """
            class Outer {
                class Inner
                object Helper
            }

            fun useOuter() {
                Outer.
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        // Line 7 is "    Outer."; column 10 sits right after the dot.
        val result = bridge.completion(uri, line = 7, character = 10, triggerCharacter = ".")
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "nested-class completion should return items array")

        val labels = items.labels().toSet()
        assertTrue(
            "Inner" in labels,
            "nested-class completion must surface 'Inner' class, got: $labels " +
                "(reason=${result.get("reason")?.asString})",
        )
        assertTrue(
            "Helper" in labels,
            "nested-class completion must surface 'Helper' nested object, got: $labels",
        )
    }

    @Test
    fun `enum class-reference completion orders entries before synthetic members`() {
        // The user's own declarations (entries) are the most useful candidates
        // after `EnumName.` and must rank ahead of the synthetic `values()` /
        // `valueOf()` / `entries` members. We assert this via `sortText`, which
        // the Rust LSP forwards verbatim to the client so popup ordering is
        // deterministic regardless of label collation.
        val uri = "file://$testSourceDir/scratch/EnumClassRefOrdering.kt"
        val content = """
            enum class AnfrageKontext {
                PDS,
                HOMEFEED,
                CHECKOUT,
            }

            fun useEnum() {
                AnfrageKontext.
            }
        """.trimIndent()
        bridge.updateFile(uri, content)

        val result = bridge.completion(uri, line = 8, character = 19, triggerCharacter = ".")
        val items = result.getAsJsonArray("items")
        assertNotNull(items, "ordered enum completion should return items array")

        val sorted = items.sortedByEffectiveSortText()
        val orderedLabels = sorted.map { it.label }
        val entryLabels = listOf("PDS", "HOMEFEED", "CHECKOUT")
        val syntheticLabels = listOf("values", "valueOf", "entries")

        val entryIndexes = entryLabels.map { label -> orderedLabels.indexOf(label) }
        val syntheticIndexes = syntheticLabels
            .map { label -> orderedLabels.indexOf(label) }
            .filter { it >= 0 }

        assertTrue(
            entryIndexes.all { it >= 0 },
            "every enum entry must appear in the ordered result, " +
                "indexes=$entryIndexes labels=$orderedLabels",
        )
        assertTrue(
            syntheticIndexes.isNotEmpty(),
            "at least one synthetic member must surface, labels=$orderedLabels",
        )
        val maxEntryIndex = entryIndexes.max()
        val minSyntheticIndex = syntheticIndexes.min()
        assertTrue(
            maxEntryIndex < minSyntheticIndex,
            "every enum entry must sort before every synthetic member " +
                "(maxEntryIndex=$maxEntryIndex, minSyntheticIndex=$minSyntheticIndex, " +
                "ordered=$orderedLabels)",
        )
    }

    @Test
    fun `instance receiver completion still surfaces class members after class-reference fix`() {
        // Regression guard: the class-reference diversion must only kick in for
        // class-reference receivers. Instance receivers must still walk the
        // existing instance-scope path (ka-d4n8 fixed for the PREFER_SELF flip).
        val uri = "file://$testSourceDir/scratch/InstanceVsClassRefCompletion.kt"
        val content = """
            class Fruit {
                fun bake() = Unit
                fun blend() = Unit
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
        assertNotNull(items, "instance-receiver completion should return items array")

        val labels = items.labels().toSet()
        val expected = listOf("bake", "blend", "ripeness")
        val missing = expected.filterNot { it in labels }
        assertTrue(
            missing.isEmpty(),
            "instance-receiver completion must continue to surface declared members, " +
                "missing: $missing (labels=$labels, reason=${result.get("reason")?.asString})",
        )
    }

    private fun JsonArray.labels(): List<String> =
        (0 until size()).map { get(it).asJsonObject.get("label")?.asString ?: "" }

    private data class OrderedItem(val label: String, val sortKey: String)

    /**
     * Sorts completion items by their effective sort key (`sortText` if
     * provided, otherwise the label) — mirroring the way LSP clients order
     * completion popups when no typed prefix narrows the candidates.
     */
    private fun JsonArray.sortedByEffectiveSortText(): List<OrderedItem> =
        (0 until size())
            .map { i ->
                val obj = get(i).asJsonObject
                val label = obj.get("label")?.asString ?: ""
                val sortKey = obj.get("sortText")?.asString ?: label
                OrderedItem(label = label, sortKey = sortKey)
            }
            .sortedBy { it.sortKey }
}
