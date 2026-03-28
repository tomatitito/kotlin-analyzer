package dev.kouros.sidecar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue

class SessionFileTest {
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
    fun `in-place update should reflect new content in diagnostics`() {
        val uri = "file://$testSourceDir/Clean.kt"

        // Push content with a type mismatch
        val brokenContent = "fun broken(): Int = \"not an int\""
        bridge.updateFile(uri, brokenContent)

        val result = bridge.analyze(uri)
        val diags = result.getAsJsonArray("diagnostics")
        val errors = diags?.filter { it.asJsonObject.get("severity")?.asString == "ERROR" }

        System.err.println("DEBUG: total diags = ${diags?.size()}, errors = ${errors?.count()}")
        diags?.forEach { System.err.println("DEBUG: ${it}") }

        assertTrue(
            errors != null && errors.isNotEmpty(),
            "Should see type mismatch error after in-place update, got: $diags"
        )
    }
}
