package dev.kouros.sidecar

import org.jetbrains.kotlin.config.LanguageFeature
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilerBridgeTest {

    @Test
    fun `bundledKotlinVersion matches runtime metadata`() {
        assertEquals(SidecarRuntime.kotlinVersion, CompilerBridge.bundledKotlinVersion())
    }

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

    @Test
    fun `findStdlibJarsInRepository prefers bundled Kotlin version`() {
        val repositoryRoot = Files.createTempDirectory("stdlib-repo")
        val preferredVersion = CompilerBridge.bundledKotlinVersion()
        val fallbackVersion = "0.0.1"

        listOf("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8").forEach { name ->
            val preferredJar = repositoryRoot.resolve(name).resolve(preferredVersion).resolve("hash").resolve("$name-$preferredVersion.jar")
            Files.createDirectories(preferredJar.parent)
            Files.write(preferredJar, byteArrayOf())

            val fallbackJar = repositoryRoot.resolve(name).resolve(fallbackVersion).resolve("hash").resolve("$name-$fallbackVersion.jar")
            Files.createDirectories(fallbackJar.parent)
            Files.write(fallbackJar, byteArrayOf())
        }

        val jars = CompilerBridge.findStdlibJarsInRepository(
            repositoryRoot = repositoryRoot,
            preferVersion = preferredVersion,
        )

        assertEquals(3, jars.size)
        assertTrue(jars.all { it.toString().contains(preferredVersion) })
    }
}
