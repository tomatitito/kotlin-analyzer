package dev.kouros.sidecar

import org.jetbrains.kotlin.config.LanguageFeature
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompilerBridgeTest {

    @Test
    fun `mapCompilerFlag maps known flags`() {
        assertEquals(
            LanguageFeature.ContextParameters,
            CompilerBridge.mapCompilerFlag("-Xcontext-parameters")
        )
        assertEquals(
            LanguageFeature.ContextReceivers,
            CompilerBridge.mapCompilerFlag("-Xcontext-receivers")
        )
        assertEquals(
            LanguageFeature.MultiDollarInterpolation,
            CompilerBridge.mapCompilerFlag("-Xmulti-dollar-interpolation")
        )
    }

    @Test
    fun `mapCompilerFlag returns null for unknown flags`() {
        assertNull(CompilerBridge.mapCompilerFlag("-Xjvm-default=all"))
    }

    @Test
    fun `mapCompilerFlag returns null for completely unknown flags`() {
        assertNull(CompilerBridge.mapCompilerFlag("-Xnonexistent-flag-12345"))
    }
}
