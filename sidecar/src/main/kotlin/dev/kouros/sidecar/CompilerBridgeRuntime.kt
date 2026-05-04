package dev.kouros.sidecar

import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import java.nio.file.Path

internal object CompilerBridgeRuntime {

    fun bundledKotlinVersion(): String = SidecarRuntime.kotlinVersion

    fun resolveLanguageVersion(compilerFlags: List<String>): LanguageVersion {
        val explicit = extractFlagValue(compilerFlags, "-language-version")
        if (explicit != null) {
            val parsed = LanguageVersion.fromFullVersionString(explicit)
            if (parsed != null) return parsed
        }
        return LanguageVersion.fromFullVersionString(bundledKotlinVersion())
            ?: LanguageVersion.LATEST_STABLE
    }

    fun resolveApiVersion(compilerFlags: List<String>, languageVersion: LanguageVersion): ApiVersion {
        val explicit = extractFlagValue(compilerFlags, "-api-version")
        if (explicit != null) {
            val parsed = ApiVersion.parse(explicit)
            if (parsed != null) return parsed
        }
        return ApiVersion.createByLanguageVersion(languageVersion)
    }

    private fun extractFlagValue(flags: List<String>, name: String): String? {
        for ((i, flag) in flags.withIndex()) {
            if (flag == name && i + 1 < flags.size) return flags[i + 1]
            if (flag.startsWith("$name=")) return flag.substringAfter('=').trim()
        }
        return null
    }

    internal fun findStdlibJarsInRepository(repositoryRoot: Path, preferVersion: String? = null): List<Path> {
        val stdlibNames = listOf("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")
        val found = mutableListOf<Path>()

        for (name in stdlibNames) {
            val moduleDir = repositoryRoot.resolve(name).toFile()
            if (!moduleDir.exists()) continue

            val jar = if (preferVersion != null) {
                findFirstJar(moduleDir.toPath().resolve(preferVersion))
            } else {
                moduleDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.asSequence()
                    ?.mapNotNull { findFirstJar(it.toPath()) }
                    ?.firstOrNull()
            }

            if (jar != null) {
                found.add(jar)
            }
        }

        return found
    }

    private fun findFirstJar(versionDir: Path): Path? {
        val dir = versionDir.toFile()
        if (!dir.exists()) return null

        return dir.walkTopDown()
            .firstOrNull { it.extension == "jar" && !it.name.contains("-sources") && !it.name.contains("-javadoc") }
            ?.toPath()
    }

    fun mapCompilerFlag(flag: String): LanguageFeature? {
        return when (flag) {
            "-Xcontext-parameters" -> LanguageFeature.ContextParameters
            "-Xcontext-receivers" -> LanguageFeature.ContextReceivers
            "-Xmulti-dollar-interpolation" -> LanguageFeature.MultiDollarInterpolation
            "-Xvalue-classes" -> LanguageFeature.InlineClasses
            "-Xjvm-default=all" -> null
            else -> {
                val featureName = flag
                    .removePrefix("-X")
                    .split("-")
                    .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                try {
                    LanguageFeature.valueOf(featureName)
                } catch (_: Exception) {
                    System.err.println("CompilerBridge: unknown compiler flag: $flag")
                    null
                }
            }
        }
    }
}
