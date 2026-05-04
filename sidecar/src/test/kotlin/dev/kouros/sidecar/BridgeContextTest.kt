package dev.kouros.sidecar

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies BridgeContext exists, holds expected state fields,
 * and provides core utility methods.
 */
class BridgeContextTest {

    @Test
    fun `BridgeContext can be instantiated with default state`() {
        val ctx = BridgeContext()

        // Session state
        assertNull(ctx.session)
        assertNull(ctx.sourceModule)
        assertNotNull(ctx.disposable)

        // Virtual file state
        assertTrue(ctx.virtualFiles.isEmpty())
        assertNull(ctx.virtualFileTempDir)
        assertTrue(ctx.virtualFilesOnDisk.isEmpty())
        assertTrue(ctx.virtualFileDiskPaths.isEmpty())
        assertTrue(ctx.decompiledFileCache.isEmpty())
        assertTrue(ctx.shadowPathMapping.isEmpty())
        assertTrue(ctx.lightFileCache.isEmpty())

        // Init params default to empty
        assertEquals("", ctx.initProjectRoot)
        assertTrue(ctx.initClasspath.isEmpty())
        assertTrue(ctx.initCompilerFlags.isEmpty())
        assertEquals("", ctx.initJdkHome)
        assertTrue(ctx.initSourceRoots.isEmpty())
    }

    @Test
    fun `uriToPath strips file prefix`() {
        val ctx = BridgeContext()
        assertEquals("/home/user/file.kt", ctx.uriToPath("file:///home/user/file.kt"))
        assertEquals("/plain/path.kt", ctx.uriToPath("/plain/path.kt"))
    }

    @Test
    fun `buildFileUri produces file URI`() {
        val ctx = BridgeContext()
        assertEquals("file:///home/user/file.kt", ctx.buildFileUri("/home/user/file.kt"))
        assertEquals("file:///path/to.jar!/com/Foo.class", ctx.buildFileUri("/path/to.jar!/com/Foo.class"))
    }

    @Test
    fun `makeEdit constructs proper JSON edit object`() {
        val ctx = BridgeContext()
        val edit = ctx.makeEdit("file:///test.kt", 1, 0, 1, 5, "hello")
        assertEquals("file:///test.kt", edit.get("uri").asString)
        assertEquals("hello", edit.get("newText").asString)
        val range = edit.getAsJsonObject("range")
        assertEquals(1, range.get("startLine").asInt)
        assertEquals(0, range.get("startColumn").asInt)
        assertEquals(1, range.get("endLine").asInt)
        assertEquals(5, range.get("endColumn").asInt)
    }

    @Test
    fun `virtual file operations work correctly`() {
        val ctx = BridgeContext()

        // Initially no virtual files
        assertNull(ctx.getVirtualFileContent("file:///test.kt"))
        assertEquals(false, ctx.hasVirtualFile("file:///test.kt"))

        // Store a virtual file
        ctx.virtualFiles["file:///test.kt"] = "fun main() {}"

        // Retrieval works
        assertEquals("fun main() {}", ctx.getVirtualFileContent("file:///test.kt"))
        assertTrue(ctx.hasVirtualFile("file:///test.kt"))

        // Path-based lookup also works
        assertNotNull(ctx.getVirtualFileEntry("file:///test.kt"))
    }
}
