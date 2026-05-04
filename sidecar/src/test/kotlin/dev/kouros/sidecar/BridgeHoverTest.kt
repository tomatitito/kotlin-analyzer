package dev.kouros.sidecar

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BridgeHoverTest {

    @Test
    fun `hover extension function exists on BridgeContext`() {
        val ctx = BridgeContext()
        ctx.initialize(
            projectRoot = "",
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = emptyList(),
        )

        val result: JsonObject = ctx.hover("file:///nonexistent.kt", 1, 0)
        // Should return a JsonObject (possibly with a reason for failure, but not crash)
        assertNotNull(result)

        ctx.shutdown()
    }

    @Test
    fun `enrichKDocText converts bracket references to inline code`() {
        val input = "See [String] and [kotlin.Int] for details"
        val result = enrichKDocText(input)
        assertTrue(result.contains("`String`"))
        assertTrue(result.contains("`Int`"))
    }

    @Test
    fun `parseDocCommentText strips comment markers`() {
        val comment = """/**
         * This is a doc comment.
         * Second line.
         */"""
        val result = parseDocCommentText(comment)
        assertNotNull(result)
        assertTrue(result.contains("This is a doc comment."))
        assertTrue(result.contains("Second line."))
    }
}
