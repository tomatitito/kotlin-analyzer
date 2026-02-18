package dev.kouros.spike

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

fun main() {
    val totalStart = System.currentTimeMillis()

    println("=== Kotlin Analysis API Spike ===")
    println()

    val projectRoot = Paths.get("").toAbsolutePath()
    val testSourcesDir = projectRoot.resolve("test-sources")
    val jdkHome = System.getProperty("jdk.home") ?: System.getenv("JAVA_HOME") ?: ""

    println("Project root: $projectRoot")
    println("Test sources: $testSourcesDir")
    println("JDK home: $jdkHome")
    println()

    val stdlibJars = findKotlinStdlibJars()
    println("Kotlin stdlib JARs (${stdlibJars.size}):")
    stdlibJars.forEach { println("  - ${it.fileName}") }
    println()

    analyzeBasicFile(testSourcesDir, jdkHome, stdlibJars)
    println()
    analyzeContextParamsFile(testSourcesDir, jdkHome, stdlibJars)

    val totalEnd = System.currentTimeMillis()
    println()
    println("=== Timing ===")
    println("Total execution time: ${totalEnd - totalStart}ms")

    val runtime = Runtime.getRuntime()
    println()
    println("=== Memory ===")
    println("Max memory: ${runtime.maxMemory() / 1024 / 1024}MB")
    println("Total memory: ${runtime.totalMemory() / 1024 / 1024}MB")
    println("Used memory: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024}MB")
    println("Free memory: ${runtime.freeMemory() / 1024 / 1024}MB")
}

private fun analyzeBasicFile(testSourcesDir: Path, jdkHome: String, stdlibJars: List<Path>) {
    println("--- Analyzing Basic.kt (expect type error) ---")

    val disposable = Disposer.newDisposable("basic-analysis")
    try {
        val sessionStart = System.currentTimeMillis()
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                val jdkModule = if (jdkHome.isNotEmpty()) {
                    buildKtSdkModule {
                        addBinaryRootsFromJdkHome(Paths.get(jdkHome), isJre = false)
                        libraryName = "JDK"
                        platform = JvmPlatforms.defaultJvmPlatform
                    }
                } else null

                val stdlibModule = if (stdlibJars.isNotEmpty()) {
                    buildKtLibraryModule {
                        libraryName = "kotlin-stdlib"
                        for (jar in stdlibJars) {
                            addBinaryRoot(jar)
                        }
                        platform = JvmPlatforms.defaultJvmPlatform
                    }
                } else null

                val mainModule = buildKtSourceModule {
                    moduleName = "test-basic"
                    platform = JvmPlatforms.defaultJvmPlatform
                    addSourceRoot(testSourcesDir.resolve("Basic.kt"))
                    if (jdkModule != null) addRegularDependency(jdkModule)
                    if (stdlibModule != null) addRegularDependency(stdlibModule)
                }
                addModule(mainModule)
            }
        }
        val sessionEnd = System.currentTimeMillis()
        println("  Session creation: ${sessionEnd - sessionStart}ms")

        val analysisStart = System.currentTimeMillis()
        collectAndPrintDiagnostics(session)
        val analysisEnd = System.currentTimeMillis()
        println("  Analysis time: ${analysisEnd - analysisStart}ms")
    } catch (e: Exception) {
        println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        Disposer.dispose(disposable)
    }
}

private fun analyzeContextParamsFile(testSourcesDir: Path, jdkHome: String, stdlibJars: List<Path>) {
    println("--- Analyzing ContextParams.kt (with -Xcontext-parameters) ---")

    val disposable = Disposer.newDisposable("context-params-analysis")
    try {
        val sessionStart = System.currentTimeMillis()
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                val jdkModule = if (jdkHome.isNotEmpty()) {
                    buildKtSdkModule {
                        addBinaryRootsFromJdkHome(Paths.get(jdkHome), isJre = false)
                        libraryName = "JDK"
                        platform = JvmPlatforms.defaultJvmPlatform
                    }
                } else null

                val stdlibModule = if (stdlibJars.isNotEmpty()) {
                    buildKtLibraryModule {
                        libraryName = "kotlin-stdlib"
                        for (jar in stdlibJars) {
                            addBinaryRoot(jar)
                        }
                        platform = JvmPlatforms.defaultJvmPlatform
                    }
                } else null

                val mainModule = buildKtSourceModule {
                    moduleName = "test-context-params"
                    platform = JvmPlatforms.defaultJvmPlatform
                    addSourceRoot(testSourcesDir.resolve("ContextParams.kt"))
                    languageVersionSettings = LanguageVersionSettingsImpl(
                        languageVersion = LanguageVersion.KOTLIN_2_1,
                        apiVersion = ApiVersion.KOTLIN_2_1,
                        specificFeatures = mapOf(
                            LanguageFeature.ContextParameters to LanguageFeature.State.ENABLED
                        )
                    )
                    if (jdkModule != null) addRegularDependency(jdkModule)
                    if (stdlibModule != null) addRegularDependency(stdlibModule)
                }
                addModule(mainModule)
            }
        }
        val sessionEnd = System.currentTimeMillis()
        println("  Session creation: ${sessionEnd - sessionStart}ms")

        val analysisStart = System.currentTimeMillis()
        collectAndPrintDiagnostics(session)
        val analysisEnd = System.currentTimeMillis()
        println("  Analysis time: ${analysisEnd - analysisStart}ms")
    } catch (e: Exception) {
        println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        Disposer.dispose(disposable)
    }
}

private fun collectAndPrintDiagnostics(session: StandaloneAnalysisAPISession) {
    val ktFiles = session.modulesWithFiles.entries
        .flatMap { (_, files) -> files }
        .filterIsInstance<KtFile>()

    println("  Found ${ktFiles.size} KtFile(s)")

    for (ktFile in ktFiles) {
        println("  File: ${ktFile.name}")

        analyze(ktFile) {
            val diagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
            if (diagnostics.isEmpty()) {
                println("  No diagnostics found.")
            } else {
                println("  Diagnostics (${diagnostics.size}):")
                for (diagnostic in diagnostics) {
                    val severity = diagnostic.severity
                    val message = diagnostic.defaultMessage
                    val factoryName = diagnostic.factoryName

                    val textRanges = diagnostic.textRanges
                    val textRange = textRanges.firstOrNull()

                    val lineCol = if (textRange != null) {
                        try {
                            val document = ktFile.viewProvider.document
                            if (document != null) {
                                val line = document.getLineNumber(textRange.startOffset) + 1
                                val lineStart = document.getLineStartOffset(
                                    document.getLineNumber(textRange.startOffset)
                                )
                                val col = textRange.startOffset - lineStart + 1
                                "$line:$col"
                            } else {
                                "?:?"
                            }
                        } catch (_: Exception) {
                            "?:?"
                        }
                    } else {
                        "?:?"
                    }

                    println("    [$severity] $factoryName at $lineCol - $message")
                }
            }
        }
    }
}

private fun findKotlinStdlibJars(): List<Path> {
    val classpath = System.getProperty("java.class.path") ?: return emptyList()
    return classpath.split(File.pathSeparator)
        .filter { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
        .map { Paths.get(it) }
}
