package dev.kouros.sidecar

import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies CompilerBridgeRuntime exists and holds the runtime helper implementations.
 */
class CompilerBridgeRuntimeTest {

    @Test
    fun `bundledKotlinVersion matches runtime metadata`() {
        assertEquals(SidecarRuntime.kotlinVersion, CompilerBridgeRuntime.bundledKotlinVersion())
    }

    @Test
    fun `mapCompilerFlag maps known flags`() {
        assertEquals(
            LanguageFeature.ContextParameters,
            CompilerBridgeRuntime.mapCompilerFlag("-Xcontext-parameters"),
        )
        assertEquals(
            LanguageFeature.ContextReceivers,
            CompilerBridgeRuntime.mapCompilerFlag("-Xcontext-receivers"),
        )
    }

    @Test
    fun `mapCompilerFlag returns null for unknown flags`() {
        assertNull(CompilerBridgeRuntime.mapCompilerFlag("-Xjvm-default=all"))
        assertNull(CompilerBridgeRuntime.mapCompilerFlag("-Xnonexistent-flag-12345"))
    }

    // --- Version resolution ---

    @Test
    fun `resolveLanguageVersion defaults to bundled compiler version`() {
        val version = CompilerBridgeRuntime.resolveLanguageVersion(emptyList())
        val expected = LanguageVersion.fromFullVersionString(CompilerBridgeRuntime.bundledKotlinVersion())
            ?: LanguageVersion.LATEST_STABLE
        assertEquals(expected, version)
    }

    @Test
    fun `resolveLanguageVersion respects explicit flag with equals syntax`() {
        val version = CompilerBridgeRuntime.resolveLanguageVersion(listOf("-language-version=2.0"))
        assertEquals(LanguageVersion.KOTLIN_2_0, version)
    }

    @Test
    fun `resolveLanguageVersion respects explicit flag with space syntax`() {
        val version = CompilerBridgeRuntime.resolveLanguageVersion(listOf("-language-version", "2.0"))
        assertEquals(LanguageVersion.KOTLIN_2_0, version)
    }

    @Test
    fun `resolveLanguageVersion falls back to bundled for unknown version string`() {
        val version = CompilerBridgeRuntime.resolveLanguageVersion(listOf("-language-version=99.99"))
        val expected = LanguageVersion.fromFullVersionString(CompilerBridgeRuntime.bundledKotlinVersion())
            ?: LanguageVersion.LATEST_STABLE
        assertEquals(expected, version)
    }

    @Test
    fun `resolveApiVersion defaults to language version when no explicit flag`() {
        val langVersion = LanguageVersion.KOTLIN_2_0
        val apiVersion = CompilerBridgeRuntime.resolveApiVersion(emptyList(), langVersion)
        assertEquals(ApiVersion.createByLanguageVersion(langVersion), apiVersion)
    }

    @Test
    fun `resolveApiVersion respects explicit flag`() {
        val apiVersion = CompilerBridgeRuntime.resolveApiVersion(
            listOf("-api-version=1.9"),
            LanguageVersion.KOTLIN_2_0,
        )
        assertEquals(ApiVersion.KOTLIN_1_9, apiVersion)
    }

    @Test
    fun `resolveLanguageVersion ignores unrelated flags`() {
        val version = CompilerBridgeRuntime.resolveLanguageVersion(
            listOf("-Xcontext-parameters", "-Xmulti-dollar-interpolation"),
        )
        val expected = LanguageVersion.fromFullVersionString(CompilerBridgeRuntime.bundledKotlinVersion())
            ?: LanguageVersion.LATEST_STABLE
        assertEquals(expected, version)
    }

    @Test
    fun `findStdlibJarsInRepository prefers specified version`() {
        val repositoryRoot = Files.createTempDirectory("stdlib-repo-rt")
        val preferredVersion = CompilerBridgeRuntime.bundledKotlinVersion()
        val fallbackVersion = "0.0.1"

        listOf("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8").forEach { name ->
            val preferredJar = repositoryRoot.resolve(name).resolve(preferredVersion).resolve("hash").resolve("$name-$preferredVersion.jar")
            Files.createDirectories(preferredJar.parent)
            Files.write(preferredJar, byteArrayOf())

            val fallbackJar = repositoryRoot.resolve(name).resolve(fallbackVersion).resolve("hash").resolve("$name-$fallbackVersion.jar")
            Files.createDirectories(fallbackJar.parent)
            Files.write(fallbackJar, byteArrayOf())
        }

        val jars = CompilerBridgeRuntime.findStdlibJarsInRepository(
            repositoryRoot = repositoryRoot,
            preferVersion = preferredVersion,
        )

        assertEquals(3, jars.size)
        assertTrue(jars.all { it.toString().contains(preferredVersion) })
    }
}
