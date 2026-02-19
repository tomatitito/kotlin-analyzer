package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

/**
 * Abstraction over the Kotlin Analysis API.
 * Encapsulates all direct Analysis API calls so that upstream API changes
 * only affect this class.
 */
class CompilerBridge {
    private var session: StandaloneAnalysisAPISession? = null
    private var disposable = Disposer.newDisposable("compiler-bridge")
    private val virtualFiles = mutableMapOf<String, String>() // uri -> content

    /**
     * Initializes the Analysis API session with the given project configuration.
     */
    fun initialize(
        projectRoot: String,
        classpath: List<String>,
        compilerFlags: List<String>,
        jdkHome: String,
        sourceRoots: List<String>,
    ) {
        System.err.println("CompilerBridge: initializing session")
        val startTime = System.currentTimeMillis()

        // Clean up any previous session
        if (session != null) {
            Disposer.dispose(disposable)
            disposable = Disposer.newDisposable("compiler-bridge")
        }

        val stdlibJars = findKotlinStdlibJars()

        // Map compiler flags to language features
        val languageFeatures = mutableMapOf<LanguageFeature, LanguageFeature.State>()
        for (flag in compilerFlags) {
            mapCompilerFlag(flag)?.let { feature ->
                languageFeatures[feature] = LanguageFeature.State.ENABLED
            }
        }

        val effectiveSourceRoots = if (sourceRoots.isNotEmpty()) {
            sourceRoots.map { Paths.get(it) }.filter { it.toFile().exists() }
        } else if (projectRoot.isNotEmpty()) {
            findSourceRoots(Paths.get(projectRoot))
        } else {
            emptyList()
        }

        session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                // JDK module
                val jdkModule = if (jdkHome.isNotEmpty()) {
                    buildKtSdkModule {
                        addBinaryRootsFromJdkHome(Paths.get(jdkHome), isJre = false)
                        libraryName = "JDK"
                        platform = JvmPlatforms.defaultJvmPlatform
                    }
                } else {
                    // Try to use the current JDK
                    val currentJdkHome = System.getProperty("java.home")
                    if (currentJdkHome != null) {
                        buildKtSdkModule {
                            addBinaryRootsFromJdkHome(Paths.get(currentJdkHome), isJre = true)
                            libraryName = "JDK"
                            platform = JvmPlatforms.defaultJvmPlatform
                        }
                    } else null
                }

                // Kotlin stdlib module
                val stdlibModule = if (stdlibJars.isNotEmpty()) {
                    buildKtLibraryModule {
                        libraryName = "kotlin-stdlib"
                        for (jar in stdlibJars) {
                            addBinaryRoot(jar)
                        }
                        platform = JvmPlatforms.defaultJvmPlatform
                    }
                } else null

                // Project classpath as library modules
                val classpathModules = classpath
                    .map { Paths.get(it) }
                    .filter { it.toFile().exists() }
                    .mapIndexed { index, jar ->
                        buildKtLibraryModule {
                            libraryName = "dep-$index"
                            addBinaryRoot(jar)
                            platform = JvmPlatforms.defaultJvmPlatform
                        }
                    }

                // Source module
                val mainModule = buildKtSourceModule {
                    moduleName = "main"
                    platform = JvmPlatforms.defaultJvmPlatform

                    for (root in effectiveSourceRoots) {
                        addSourceRoot(root)
                    }

                    if (languageFeatures.isNotEmpty()) {
                        languageVersionSettings = LanguageVersionSettingsImpl(
                            languageVersion = LanguageVersion.KOTLIN_2_1,
                            apiVersion = ApiVersion.KOTLIN_2_1,
                            specificFeatures = languageFeatures,
                        )
                    }

                    if (jdkModule != null) addRegularDependency(jdkModule)
                    if (stdlibModule != null) addRegularDependency(stdlibModule)
                    for (dep in classpathModules) {
                        addRegularDependency(dep)
                    }
                }
                addModule(mainModule)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        System.err.println("CompilerBridge: session created in ${elapsed}ms")
    }

    /**
     * Updates the content of a virtual file in the session.
     */
    fun updateFile(uri: String, text: String) {
        virtualFiles[uri] = text
    }

    /**
     * Removes a virtual file from the session.
     */
    fun removeFile(uri: String) {
        virtualFiles.remove(uri)
    }

    /**
     * Analyzes a file and returns diagnostics.
     */
    fun analyze(uri: String): JsonObject {
        val result = JsonObject()
        val diagnosticsArray = JsonArray()

        val currentSession = session
        if (currentSession == null) {
            result.add("diagnostics", diagnosticsArray)
            return result
        }

        val ktFile = findKtFile(currentSession, uri)
        if (ktFile == null) {
            System.err.println("CompilerBridge: file not found: $uri")
            result.add("diagnostics", diagnosticsArray)
            return result
        }

        try {
            analyze(ktFile) {
                val diagnostics = ktFile.collectDiagnostics(
                    KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
                )

                for (diagnostic in diagnostics) {
                    val diagObj = JsonObject()

                    diagObj.addProperty("severity", diagnostic.severity.name)
                    diagObj.addProperty("message", diagnostic.defaultMessage)
                    diagObj.addProperty("code", diagnostic.factoryName)

                    val textRange = diagnostic.textRanges.firstOrNull()
                    if (textRange != null) {
                        try {
                            val document = ktFile.viewProvider.document
                            if (document != null) {
                                val startLine = document.getLineNumber(textRange.startOffset) + 1
                                val startLineOffset = document.getLineStartOffset(
                                    document.getLineNumber(textRange.startOffset)
                                )
                                val startCol = textRange.startOffset - startLineOffset

                                val endLine = document.getLineNumber(textRange.endOffset) + 1
                                val endLineOffset = document.getLineStartOffset(
                                    document.getLineNumber(textRange.endOffset)
                                )
                                val endCol = textRange.endOffset - endLineOffset

                                diagObj.addProperty("line", startLine)
                                diagObj.addProperty("column", startCol)
                                diagObj.addProperty("endLine", endLine)
                                diagObj.addProperty("endColumn", endCol)
                            }
                        } catch (_: Exception) {
                            // Position extraction failed, skip position info
                        }
                    }

                    diagnosticsArray.add(diagObj)
                }
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: analysis failed for $uri: ${e.message}")
        }

        result.add("diagnostics", diagnosticsArray)
        return result
    }

    /**
     * Provides hover information at the given position.
     */
    fun hover(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()

        val currentSession = session ?: return result
        val ktFile = findKtFile(currentSession, uri) ?: return result

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: return@analyze
                val element = ktFile.findElementAt(offset) ?: return@analyze

                // Walk up to find the nearest expression or declaration
                var current: com.intellij.psi.PsiElement? = element
                while (current != null) {
                    if (current is org.jetbrains.kotlin.psi.KtExpression) {
                        try {
                            val type = current.expressionType
                            if (type != null) {
                                result.addProperty("contents", "```kotlin\n${type}\n```")
                                return@analyze
                            }
                        } catch (_: Exception) {
                            // expressionType may not be available for all expressions
                        }
                    }
                    if (current is org.jetbrains.kotlin.psi.KtNamedDeclaration) {
                        val name = current.name ?: "anonymous"
                        val text = current.text?.take(200) ?: name
                        result.addProperty("contents", "```kotlin\n$text\n```")
                        return@analyze
                    }
                    current = current.parent
                }
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: hover failed: ${e.message}")
        }

        return result
    }

    /**
     * Provides completion items at the given position.
     */
    fun completion(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val itemsArray = JsonArray()

        val currentSession = session ?: run {
            result.add("items", itemsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("items", itemsArray)
            return result
        }

        // Completion via scope analysis - requires precise API usage that varies
        // across Kotlin versions. Currently returns empty results; diagnostics are
        // the priority for M2. Full completion support is an M3 feature.
        // The infrastructure is in place: the sidecar receives the request, finds
        // the file and position, and can return items once we resolve the correct
        // scope API calls for the pinned Analysis API version.

        result.add("items", itemsArray)
        return result
    }

    /**
     * Provides go-to-definition locations.
     */
    fun definition(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val locationsArray = JsonArray()

        val currentSession = session ?: run {
            result.add("locations", locationsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("locations", locationsArray)
            return result
        }

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: run {
                    result.add("locations", locationsArray)
                    return@analyze
                }

                val element = ktFile.findElementAt(offset)
                if (element != null) {
                    // Find the reference element
                    var current: com.intellij.psi.PsiElement? = element
                    while (current != null) {
                        if (current is org.jetbrains.kotlin.psi.KtReferenceExpression) {
                            val references = current.references
                            for (ref in references) {
                                val resolved = ref.resolve()
                                if (resolved != null) {
                                    val file = resolved.containingFile
                                    val textOffset = resolved.textOffset
                                    val doc = file?.viewProvider?.document

                                    if (file != null && doc != null) {
                                        val loc = JsonObject()
                                        loc.addProperty("uri", "file://${file.virtualFile.path}")
                                        loc.addProperty("line", doc.getLineNumber(textOffset) + 1)
                                        loc.addProperty("column", textOffset - doc.getLineStartOffset(doc.getLineNumber(textOffset)))
                                        locationsArray.add(loc)
                                    }
                                }
                            }
                            break
                        }
                        current = current.parent
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: definition failed: ${e.message}")
        }

        result.add("locations", locationsArray)
        return result
    }

    /**
     * Provides find-references locations.
     */
    fun references(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val locationsArray = JsonArray()
        result.add("locations", locationsArray)
        // References search requires indexing which is not available in standalone mode
        // This is a stub that will be filled in when we have proper file indexing
        return result
    }

    /**
     * Provides signature help at the given position.
     */
    fun signatureHelp(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val signaturesArray = JsonArray()
        result.add("signatures", signaturesArray)
        // Stub - will be implemented with call expression analysis
        return result
    }

    /**
     * Shuts down the Analysis API session.
     */
    fun shutdown() {
        session = null
        Disposer.dispose(disposable)
        System.err.println("CompilerBridge: session disposed")
    }

    // --- Private helpers ---

    private fun findKtFile(session: StandaloneAnalysisAPISession, uri: String): KtFile? {
        val filePath = uriToPath(uri)
        return session.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()
            .find { it.virtualFile.path == filePath || it.name == filePath }
    }

    private fun uriToPath(uri: String): String {
        return if (uri.startsWith("file://")) {
            uri.removePrefix("file://")
        } else {
            uri
        }
    }

    private fun lineColToOffset(ktFile: KtFile, line: Int, character: Int): Int? {
        val document = ktFile.viewProvider.document ?: return null
        if (line < 1 || line > document.lineCount) return null
        val lineStartOffset = document.getLineStartOffset(line - 1)
        return lineStartOffset + character
    }

    private fun findKotlinStdlibJars(): List<Path> {
        val classpath = System.getProperty("java.class.path") ?: return emptyList()
        return classpath.split(File.pathSeparator)
            .filter { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
            .map { Paths.get(it) }
    }

    private fun findSourceRoots(projectRoot: Path): List<Path> {
        val candidates = listOf(
            projectRoot.resolve("src/main/kotlin"),
            projectRoot.resolve("src/main/java"),
            projectRoot.resolve("src"),
        )
        return candidates.filter { it.toFile().exists() }
    }

    companion object {
        /**
         * Maps a -X compiler flag to a LanguageFeature enum value.
         */
        fun mapCompilerFlag(flag: String): LanguageFeature? {
            return when (flag) {
                "-Xcontext-parameters" -> LanguageFeature.ContextParameters
                "-Xcontext-receivers" -> LanguageFeature.ContextReceivers
                "-Xmulti-dollar-interpolation" -> LanguageFeature.MultiDollarInterpolation
                "-Xvalue-classes" -> LanguageFeature.InlineClasses
                "-Xjvm-default=all" -> null // Handled differently
                else -> {
                    // Try to find by name
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
}
