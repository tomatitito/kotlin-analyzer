package dev.kouros.sidecar

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BridgeCodeActionsTest {

    @Test
    fun `codeActions extension function exists on BridgeContext`() {
        val ctx = BridgeContext()
        ctx.initialize(
            projectRoot = "",
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = emptyList(),
        )

        val result: JsonObject = ctx.codeActions("file:///nonexistent.kt", 1, 0)
        assertNotNull(result)
        assertTrue(result.has("actions"))

        ctx.shutdown()
    }
}
