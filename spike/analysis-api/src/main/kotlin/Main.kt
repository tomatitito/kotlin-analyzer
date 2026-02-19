package dev.kouros.spike

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance
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
    println()
    analyzeSemanticFile(testSourcesDir, jdkHome, stdlibJars)

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

private fun analyzeSemanticFile(testSourcesDir: Path, jdkHome: String, stdlibJars: List<Path>) {
    println("--- Analyzing Semantic.kt (hover, go-to-definition, expression types) ---")

    val disposable = Disposer.newDisposable("semantic-analysis")
    try {
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
                    moduleName = "test-semantic"
                    platform = JvmPlatforms.defaultJvmPlatform
                    addSourceRoot(testSourcesDir.resolve("Semantic.kt"))
                    if (jdkModule != null) addRegularDependency(jdkModule)
                    if (stdlibModule != null) addRegularDependency(stdlibModule)
                }
                addModule(mainModule)
            }
        }

        val ktFile = session.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()
            .first()

        println("  File: ${ktFile.name}")
        println()

        // --- Hover: type at expression position ---
        // Line 17: "    val message = greeter.greet()"
        //   "greeter" starts at col 19
        testHoverAtPosition(ktFile, line = 17, col = 19, label = "greeter ref (expect Greeter)")
        //   "greet" starts at col 27
        testHoverAtPosition(ktFile, line = 17, col = 27, label = "greet() call (expect String)")

        // Line 16: "    val greeter = Greeter("World")"
        //   "Greeter" starts at col 19
        testHoverAtPosition(ktFile, line = 16, col = 19, label = "Greeter constructor ref")

        // --- Hover: declaration rendering ---
        // Line 6: "    fun greet(): String {"
        //   "greet" starts at col 9
        testHoverAtPosition(ktFile, line = 6, col = 9, label = "greet() declaration")

        // Line 4: "class Greeter(val name: String) {"
        //   "Greeter" starts at col 7
        testHoverAtPosition(ktFile, line = 4, col = 7, label = "Greeter class declaration")

        println()

        // --- Go-to-definition: resolve references ---
        // Line 17: "    val message = greeter.greet()"
        //   "greet" at col 27 should resolve to line 6
        testGoToDefinition(ktFile, line = 17, col = 27, label = "greet() call -> declaration")

        // Line 16: "    val greeter = Greeter("World")"
        //   "Greeter" at col 19 should resolve to class declaration at line 4
        testGoToDefinition(ktFile, line = 16, col = 19, label = "Greeter ref -> class declaration")

        // Line 18: "    println(message)"
        //   "message" at col 13 should resolve to val on line 17
        testGoToDefinition(ktFile, line = 18, col = 13, label = "message ref -> val declaration")

    } catch (e: Exception) {
        println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        Disposer.dispose(disposable)
    }
}

/**
 * Tests hover-like functionality: gets the type or declaration rendering at a position.
 * Line and col are 1-based (matching how editors display them).
 */
private fun testHoverAtPosition(ktFile: KtFile, line: Int, col: Int, label: String) {
    print("  HOVER $label (L$line:$col): ")

    analyze(ktFile) {
        val offset = lineColToOffset(ktFile, line, col)
        if (offset == null) {
            println("FAIL - could not convert position to offset")
            return@analyze
        }

        val element = ktFile.findElementAt(offset)
        if (element == null) {
            println("FAIL - no PSI element at offset $offset")
            return@analyze
        }

        // Walk up to find meaningful element, same strategy as CompilerBridge
        var current: PsiElement? = element
        while (current != null) {
            // Named declaration: render the declaration signature
            if (current is KtNamedDeclaration) {
                val symbol = current.symbol
                val rendered = try {
                    symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
                } catch (_: Exception) {
                    current.text?.lines()?.first() ?: "?"
                }
                println("OK (declaration)")
                println("    $rendered")
                return@analyze
            }

            // Reference expression: resolve to target declaration
            if (current is KtReferenceExpression) {
                for (ref in current.references) {
                    if (ref is KtReference) {
                        val symbol = ref.resolveToSymbol()
                        if (symbol is KaDeclarationSymbol) {
                            val rendered = try {
                                symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
                            } catch (_: Exception) {
                                symbol.toString()
                            }
                            println("OK (resolved reference)")
                            println("    $rendered")
                            return@analyze
                        }
                    }
                }
            }

            // Any expression: get its type
            if (current is KtExpression) {
                val type = current.expressionType
                if (type != null) {
                    val rendered = type.render(
                        KaTypeRendererForSource.WITH_SHORT_NAMES,
                        Variance.INVARIANT
                    )
                    println("OK (expression type)")
                    println("    $rendered")
                    return@analyze
                }
            }

            current = current.parent
        }

        println("FAIL - no type or declaration info found")
    }
}

/**
 * Tests go-to-definition: resolves a reference at a position to its declaration location.
 * Line and col are 1-based.
 */
private fun testGoToDefinition(ktFile: KtFile, line: Int, col: Int, label: String) {
    print("  DEFINITION $label (L$line:$col): ")

    analyze(ktFile) {
        val offset = lineColToOffset(ktFile, line, col)
        if (offset == null) {
            println("FAIL - could not convert position to offset")
            return@analyze
        }

        val element = ktFile.findElementAt(offset)
        if (element == null) {
            println("FAIL - no PSI element at offset $offset")
            return@analyze
        }

        // Walk up to find reference expression
        var current: PsiElement? = element
        while (current != null && current !is KtFile) {
            if (current is KtReferenceExpression) {
                // Try Analysis API resolution first (more reliable in standalone mode)
                for (ref in current.references) {
                    if (ref is KtReference) {
                        try {
                            val symbol = ref.resolveToSymbol()
                            if (symbol is KaDeclarationSymbol) {
                                val psi = symbol.psi
                                if (psi != null) {
                                    val targetFile = psi.containingFile
                                    val textOffset = psi.textOffset
                                    val doc = targetFile?.viewProvider?.document
                                    if (doc != null) {
                                        val targetLine = doc.getLineNumber(textOffset) + 1
                                        val lineStart = doc.getLineStartOffset(doc.getLineNumber(textOffset))
                                        val targetCol = textOffset - lineStart + 1
                                        val targetText = psi.text?.lines()?.first()?.take(60) ?: "?"
                                        println("OK -> L$targetLine:$targetCol")
                                        println("    $targetText")
                                        return@analyze
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // try next ref
                        }
                    }
                }

                // Fallback: PsiReference.resolve()
                for (ref in current.references) {
                    try {
                        val resolved = ref.resolve()
                        if (resolved != null) {
                            val targetFile = resolved.containingFile
                            val textOffset = resolved.textOffset
                            val doc = targetFile?.viewProvider?.document

                            if (doc != null) {
                                val targetLine = doc.getLineNumber(textOffset) + 1
                                val lineStart = doc.getLineStartOffset(doc.getLineNumber(textOffset))
                                val targetCol = textOffset - lineStart + 1
                                val targetText = resolved.text?.lines()?.first()?.take(60) ?: "?"
                                println("OK -> L$targetLine:$targetCol (via PsiReference)")
                                println("    $targetText")
                                return@analyze
                            }
                        }
                    } catch (_: Exception) {
                        // try next ref
                    }
                }
                // Don't break — keep walking up, maybe there's a higher reference
            }
            current = current.parent
        }

        println("FAIL - could not resolve reference")
    }
}

/**
 * Converts 1-based line/col to a 0-based offset in the file.
 */
private fun lineColToOffset(ktFile: KtFile, line: Int, col: Int): Int? {
    val document = ktFile.viewProvider.document ?: return null
    val lineIndex = line - 1
    if (lineIndex < 0 || lineIndex >= document.lineCount) return null
    val lineStart = document.getLineStartOffset(lineIndex)
    return lineStart + (col - 1)
}

private fun findKotlinStdlibJars(): List<Path> {
    val classpath = System.getProperty("java.class.path") ?: return emptyList()
    return classpath.split(File.pathSeparator)
        .filter { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
        .map { Paths.get(it) }
}
