package dev.kouros.sidecar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue

class StdlibResolutionTest {
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
    fun tearDown() { bridge.shutdown() }

    @Test
    fun `stdlib functions should resolve after shadow rebuild`() {
        val uri = "file://$testSourceDir/Clean.kt"

        // Push content that uses stdlib functions: listOf, emptyList, println, mapOf
        // Because Clean.kt exists on disk, updateFile triggers a shadow source tree rebuild
        val stdlibContent = """
            fun useStdlib() {
                val list = listOf(1, 2, 3)
                val empty = emptyList<String>()
                val map = mapOf("a" to 1, "b" to 2)
                println(list)
                println(empty)
                println(map)
            }
        """.trimIndent()

        bridge.updateFile(uri, stdlibContent)

        val result = bridge.analyze(uri)
        val diags = result.getAsJsonArray("diagnostics")

        System.err.println("DEBUG: total diags = ${diags?.size()}")
        diags?.forEach { System.err.println("DEBUG: ${it}") }

        // Filter for UNRESOLVED_REFERENCE errors on stdlib functions
        val unresolvedRefs = diags?.filter {
            val obj = it.asJsonObject
            val message = obj.get("message")?.asString ?: ""
            obj.get("severity")?.asString == "ERROR" &&
                message.contains("UNRESOLVED_REFERENCE") || message.contains("Unresolved reference")
        } ?: emptyList()

        System.err.println("DEBUG: unresolved reference errors = ${unresolvedRefs.size}")
        unresolvedRefs.forEach { System.err.println("DEBUG unresolved: ${it}") }

        assertTrue(
            unresolvedRefs.isEmpty(),
            "Stdlib functions (listOf, emptyList, println, mapOf) should resolve without " +
                "UNRESOLVED_REFERENCE errors after shadow rebuild, but got: $unresolvedRefs"
        )
    }

    @Test
    fun `stdlib collection operations should resolve after shadow rebuild`() {
        val uri = "file://$testSourceDir/Clean.kt"

        // Use stdlib extension functions on collections
        val collectionOpsContent = """
            fun useCollectionOps() {
                val numbers = listOf(1, 2, 3, 4, 5)
                val doubled = numbers.map { it * 2 }
                val evens = numbers.filter { it % 2 == 0 }
                val sum = numbers.fold(0) { acc, n -> acc + n }
                val grouped = numbers.groupBy { if (it % 2 == 0) "even" else "odd" }
                println(doubled)
                println(evens)
                println(sum)
                println(grouped)
            }
        """.trimIndent()

        bridge.updateFile(uri, collectionOpsContent)

        val result = bridge.analyze(uri)
        val diags = result.getAsJsonArray("diagnostics")

        System.err.println("DEBUG: total diags = ${diags?.size()}")
        diags?.forEach { System.err.println("DEBUG: ${it}") }

        val unresolvedRefs = diags?.filter {
            val obj = it.asJsonObject
            val message = obj.get("message")?.asString ?: ""
            obj.get("severity")?.asString == "ERROR" &&
                message.contains("UNRESOLVED_REFERENCE") || message.contains("Unresolved reference")
        } ?: emptyList()

        assertTrue(
            unresolvedRefs.isEmpty(),
            "Stdlib collection operations (map, filter, fold, groupBy) should resolve without " +
                "UNRESOLVED_REFERENCE errors after shadow rebuild, but got: $unresolvedRefs"
        )
    }
}
