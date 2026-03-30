package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.types.Variance
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Abstraction over the Kotlin Analysis API.
 * Encapsulates all direct Analysis API calls so that upstream API changes
 * only affect this class.
 */
class CompilerBridge {
    private var session: StandaloneAnalysisAPISession? = null
    private var sourceModule: KaModule? = null
    private var disposable = Disposer.newDisposable("compiler-bridge")
    private val virtualFiles = mutableMapOf<String, String>() // uri -> content
    private val pebbleDocuments = PebbleDocumentStore()
    private val pebbleIndex = PebbleTemplateIndex()
    private val pebbleSpringIndex = PebbleSpringIndex(pebbleIndex)
    private val symbolIndex = SymbolIndex()
    private var pebbleSpringIndexDirty = true

    // Temp directory for virtual files so FIR can discover them as source root files
    private var virtualFileTempDir: Path? = null
    // URIs of virtual files written to the temp dir during the current session generation
    private val virtualFilesOnDisk = mutableSetOf<String>()
    // Maps virtual file URI to its path on disk in the temp dir
    private val virtualFileDiskPaths = mutableMapOf<String, String>()
    // Cache of decompiled library files: virtual file path -> disk path
    private val decompiledFileCache = mutableMapOf<String, Path>()
    // When true, the session must be rebuilt before next analysis (new virtual files appeared)
    private var sessionDirty = false
    // URIs of on-disk files whose content has been overridden via updateFile()
    private val dirtyOnDiskFiles = mutableSetOf<String>()
    // Maps shadow source tree paths back to original paths for findKtFile matching
    private val shadowPathMapping = mutableMapOf<String, String>()
    // Stored init params for session rebuilds
    private var initProjectRoot = ""
    private var initClasspath = emptyList<String>()
    private var initCompilerFlags = emptyList<String>()
    private var initJdkHome = ""
    private var initSourceRoots = emptyList<String>()

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

        // Store init params for session rebuilds
        initProjectRoot = projectRoot
        initClasspath = classpath
        initCompilerFlags = compilerFlags
        initJdkHome = jdkHome
        initSourceRoots = sourceRoots

        // Create temp directory for virtual files
        if (virtualFileTempDir == null) {
            virtualFileTempDir = Files.createTempDirectory("kotlin-analyzer-virtual")
            System.err.println("CompilerBridge: created virtual file temp dir: $virtualFileTempDir")
        }

        // Write any existing virtual files to temp dir before session init
        for ((uri, text) in virtualFiles) {
            writeVirtualFileToDisk(uri, text)
        }

        // Clear cached LightVirtualFiles from previous session
        lightFileCache.clear()

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
        if (languageFeatures.isNotEmpty()) {
            System.err.println("CompilerBridge: languageFeatures=${languageFeatures.keys}")
        }
        if (compilerFlags.isNotEmpty()) {
            System.err.println("CompilerBridge: compilerFlags=$compilerFlags")
        }

        val baseSourceRoots = if (sourceRoots.isNotEmpty()) {
            sourceRoots.map { Paths.get(it) }.filter { it.toFile().exists() }
        } else if (projectRoot.isNotEmpty()) {
            findSourceRoots(Paths.get(projectRoot))
        } else {
            emptyList()
        }

        // When on-disk files have virtual overrides, build a shadow source tree so the
        // session discovers the virtual content instead of stale on-disk content.
        // Without this, FIR sees both the session file (old content) and a LightVirtualFile
        // (new content) simultaneously, causing REDECLARATION and phantom resolution.
        val effectiveSourceRoots = if (dirtyOnDiskFiles.isNotEmpty() && virtualFileTempDir != null) {
            val shadowRoots = buildShadowSourceRoots(baseSourceRoots)
            if (virtualFilesOnDisk.isNotEmpty()) {
                shadowRoots + listOf(virtualFileTempDir!!)
            } else {
                shadowRoots
            }
        } else if (virtualFileTempDir != null && virtualFilesOnDisk.isNotEmpty()) {
            baseSourceRoots + listOf(virtualFileTempDir!!)
        } else {
            baseSourceRoots
        }

        System.err.println("CompilerBridge: projectRoot=$projectRoot")
        System.err.println("CompilerBridge: sourceRoots=$sourceRoots")
        System.err.println("CompilerBridge: effectiveSourceRoots=$effectiveSourceRoots")
        System.err.println("CompilerBridge: classpath=${classpath.size} entries")

        // Warn if classpath is empty — likely means dependency extraction failed
        if (classpath.isEmpty()) {
            System.err.println("CompilerBridge: WARNING — classpath is empty, external dependency resolution will fail")
        }

        // Log Spring-related JARs for diagnostic visibility
        classpath.filter { "spring" in it.lowercase() }.forEach {
            System.err.println("CompilerBridge: spring JAR on classpath: $it")
        }
        // Log Arrow-related JARs for context/raise DSL diagnostics
        classpath.filter { "arrow" in it.lowercase() }.forEach {
            System.err.println("CompilerBridge: arrow JAR on classpath: $it")
        }
        System.err.println("CompilerBridge: stdlibJars=${stdlibJars.size} jars")
        System.err.println("CompilerBridge: jdkHome=$jdkHome")

        try {
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
                            System.err.println("CompilerBridge: using current JDK at $currentJdkHome")
                            buildKtSdkModule {
                                addBinaryRootsFromJdkHome(Paths.get(currentJdkHome), isJre = false)
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

                    // Project classpath as a single flat library module
                    // Using one module with all JARs mirrors flat-classpath semantics
                    // and avoids JPMS module-info boundary issues (e.g. JUnit 6)
                    val classpathRoots = classpath
                        .map { Paths.get(it) }
                        .filter { it.toFile().exists() }
                    val classpathModule = if (classpathRoots.isNotEmpty()) {
                        buildKtLibraryModule {
                            libraryName = "project-dependencies"
                            for (jar in classpathRoots) {
                                addBinaryRoot(jar)
                            }
                            platform = JvmPlatforms.defaultJvmPlatform
                        }
                    } else null

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
                        if (classpathModule != null) addRegularDependency(classpathModule)
                    }
                    sourceModule = mainModule
                    addModule(mainModule)
                }
            }
        } catch (e: Exception) {
            // Session build failed — leave sessionDirty = true so the next
            // ensureSessionCurrent() call retries the build instead of using
            // a stale/null session forever.
            sessionDirty = true
            System.err.println("CompilerBridge: session build failed, will retry on next request: ${e.message}")
            throw e
        }

        // Session built successfully — clear the dirty flag
        sessionDirty = false

        // Register a custom module provider that maps KtPsiFactory files (LightVirtualFile)
        // to the source module. Without this, KtPsiFactory files fall into the default
        // "not under content root" module which lacks custom language settings like
        // -Xcontext-parameters.
        registerVirtualFileModuleProvider()

        val elapsed = System.currentTimeMillis() - startTime
        System.err.println("CompilerBridge: session created in ${elapsed}ms")

        // Log discovered files
        val allFiles = session!!.modulesWithFiles.entries
            .flatMap { (module, files) ->
                files.filterIsInstance<KtFile>().map { module to it }
            }
        System.err.println("CompilerBridge: discovered ${allFiles.size} KtFile(s) in session:")
        for ((module, ktFile) in allFiles.take(20)) {
            System.err.println("  - ${ktFile.virtualFile.path} (module: ${module})")
        }
        if (allFiles.size > 20) {
            System.err.println("  ... and ${allFiles.size - 20} more")
        }

        // Build the symbol index from all discovered files
        symbolIndex.rebuildFromSession(session!!)
        System.err.println("CompilerBridge: symbol index built with ${symbolIndex.size()} declarations")

        pebbleSpringIndexDirty = true
    }

    /**
     * Updates the content of a virtual file in the session.
     */
    fun updateFile(uri: String, text: String) {
        virtualFiles[uri] = text
        updateFileInSession(uri, text)
        pebbleSpringIndexDirty = true

        // Detect on-disk file edits: the session's FIR caches are baked in at creation
        // time, so when an on-disk file's content changes, the session must be rebuilt
        // with a shadow source tree that reflects the virtual content.
        // Only mark dirty if the content actually differs from on-disk to avoid
        // unnecessary rebuilds (e.g., textDocument/didOpen sends original content).
        val filePath = uriToPath(uri)
        val onDiskFile = File(filePath)
        if (onDiskFile.exists()) {
            if (uri in dirtyOnDiskFiles) {
                // Already dirty — just need rebuild for new content
                sessionDirty = true
            } else {
                // First edit: compare with on-disk content
                try {
                    val onDiskContent = onDiskFile.readText()
                    if (text != onDiskContent) {
                        dirtyOnDiskFiles.add(uri)
                        sessionDirty = true
                        System.err.println("CompilerBridge: updateFile($uri) — on-disk file content changed, session marked dirty")
                    }
                } catch (e: Exception) {
                    // Can't read the file — mark dirty to be safe
                    dirtyOnDiskFiles.add(uri)
                    sessionDirty = true
                }
            }
        }

        // Write virtual file to temp dir so FIR can discover it for cross-package resolution.
        // Only needed for files that don't exist on disk in the session's source roots.
        val wasOnDisk = uri in virtualFilesOnDisk
        writeVirtualFileToDisk(uri, text)
        val nowOnDisk = uri in virtualFilesOnDisk
        if (nowOnDisk && !wasOnDisk) {
            // New file appeared in temp dir — session must be rebuilt so FIR discovers it
            sessionDirty = true
            System.err.println("CompilerBridge: updateFile($uri) — new virtual file in temp dir, session marked dirty")
        } else if (nowOnDisk && wasOnDisk) {
            // Content changed for existing temp dir file — need rebuild since session caches file content
            sessionDirty = true
        }

        // Re-index the file with updated content
        val currentSession = session
        if (currentSession != null) {
            try {
                val filePath = uriToPath(uri)
                val fileName = filePath.substringAfterLast('/')
                val psiFactory = KtPsiFactory(currentSession.project)
                val ktFile = psiFactory.createFile(fileName, text)
                symbolIndex.indexFile(uri, ktFile)
            } catch (e: Exception) {
                System.err.println("CompilerBridge: failed to re-index $uri: ${e.message}")
            }
        }
    }

    /**
     * Removes a virtual file from the session.
     */
    fun removeFile(uri: String) {
        virtualFiles.remove(uri)
        symbolIndex.removeFile(uri)
        pebbleSpringIndexDirty = true
        if (uri in dirtyOnDiskFiles) {
            dirtyOnDiskFiles.remove(uri)
            sessionDirty = true
        }
        if (uri in virtualFilesOnDisk) {
            virtualFilesOnDisk.remove(uri)
            sessionDirty = true
        }
    }

    fun updatePebbleFile(uri: String, text: String) {
        pebbleDocuments.put(uri, text)
        pebbleIndex.update(uri, text)
        pebbleSpringIndexDirty = true
    }

    fun removePebbleFile(uri: String) {
        pebbleDocuments.remove(uri)
        pebbleIndex.remove(uri)
        pebbleSpringIndexDirty = true
    }

    fun pebbleDefinition(uri: String, line: Int, character: Int): JsonObject {
        ensurePebbleSpringIndexCurrent()

        val result = JsonObject()
        val locationsArray = JsonArray()
        val targetUri = pebbleIndex.definition(uri, line, character)
        if (targetUri != null) {
            val location = JsonObject()
            location.addProperty("uri", targetUri)
            location.addProperty("line", 1)
            location.addProperty("column", 0)
            locationsArray.add(location)
        } else {
            val symbolLocation = pebbleSpringIndex.definitionForPebbleSymbol(uri, line, character)
            if (symbolLocation != null) {
                val location = JsonObject()
                location.addProperty("uri", symbolLocation.uri)
                location.addProperty("line", symbolLocation.line)
                location.addProperty("column", symbolLocation.column)
                pebbleSpringIndex.producerTypeForPebbleVariableRoot(uri, line, character)?.let {
                    location.addProperty("type", it)
                }
                locationsArray.add(location)
            }
        }
        result.add("locations", locationsArray)
        return result
    }

    fun pebbleReferences(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val locationsArray = JsonArray()
        for (reference in pebbleIndex.referencesAt(uri, line, character)) {
            val location = JsonObject()
            location.addProperty("uri", reference.sourceUri)
            location.addProperty("line", reference.range.startLine)
            location.addProperty("column", reference.range.startColumn)
            locationsArray.add(location)
        }
        result.add("locations", locationsArray)
        return result
    }

    /**
     * Rebuilds the Analysis API session if virtual files have changed.
     * This ensures FIR can discover all virtual files for cross-package resolution.
     */
    private fun ensureSessionCurrent() {
        if (!sessionDirty || session == null) return
        System.err.println("CompilerBridge: session dirty, rebuilding to pick up virtual file changes")
        initialize(initProjectRoot, initClasspath, initCompilerFlags, initJdkHome, initSourceRoots)
    }

    private fun ensurePebbleSpringIndexCurrent() {
        if (!pebbleSpringIndexDirty) return
        ensureSessionCurrent()
        val currentSession = session ?: return
        val kotlinFiles = linkedMapOf<String, KtFile>()
        for ((_, files) in currentSession.modulesWithFiles) {
            for (ktFile in files.filterIsInstance<KtFile>()) {
                kotlinFiles["file://${ktFile.virtualFile.path}"] = ktFile
            }
        }
        for ((uri, text) in virtualFiles) {
            try {
                val psiFactory = KtPsiFactory(currentSession.project)
                kotlinFiles[uri] = psiFactory.createFile(uriToPath(uri).substringAfterLast('/'), text)
            } catch (e: Exception) {
                System.err.println("CompilerBridge: failed to create PSI for spring index file $uri: ${e.message}")
            }
        }
        pebbleSpringIndex.rebuild(kotlinFiles)
        pebbleSpringIndexDirty = false
    }

    /**
     * Analyzes a file and returns diagnostics.
     */
    fun analyze(uri: String): JsonObject {
        ensureSessionCurrent()

        val result = JsonObject()
        val diagnosticsArray = JsonArray()

        // Skip diagnostics for decompiled library stub files
        val tempDir = virtualFileTempDir
        if (tempDir != null) {
            val filePath = uriToPath(uri)
            if (filePath.startsWith(tempDir.resolve("decompiled").toString())) {
                result.add("diagnostics", diagnosticsArray)
                return result
            }
        }

        val currentSession = session
        if (currentSession == null) {
            System.err.println("CompilerBridge: analyze($uri) — session is NULL, returning empty diagnostics")
            result.add("diagnostics", diagnosticsArray)
            return result
        }

        val ktFile = findKtFile(currentSession, uri)
        if (ktFile == null) {
            System.err.println("CompilerBridge: analyze($uri) — file not found anywhere")
            result.add("diagnostics", diagnosticsArray)
            return result
        }

        System.err.println("CompilerBridge: analyze($uri) — found KtFile: ${ktFile.virtualFile?.path ?: "(ad-hoc)"}, name=${ktFile.name}")

        // Log content source for debugging
        val virtualContent = getVirtualFileContent(uri)
        val isVirtualFile = virtualContent != null &&
            findKtFileInSession(currentSession, uri)?.text != virtualContent
        System.err.println("CompilerBridge: analyze($uri) — using ${if (isVirtualFile) "virtual" else "session"} content (${ktFile.text.length} chars)")

        // For virtual files (content differs from session), collect names declared in the file.
        // The standalone Analysis API can't resolve same-file declarations for non-session files,
        // so we filter out false UNRESOLVED_REFERENCE diagnostics for names that ARE declared
        // within the file (they would resolve correctly in a full session).
        val declaredNames = if (isVirtualFile) collectDeclaredNames(ktFile) else emptySet()
        if (isVirtualFile && declaredNames.isNotEmpty()) {
            System.err.println("CompilerBridge: analyze($uri) — virtual file declares: $declaredNames (will filter false UNRESOLVED_REFERENCE)")
        }

        try {
            analyze(ktFile) {
                val analysisStart = System.currentTimeMillis()
                val diagnostics = ktFile.collectDiagnostics(
                    KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
                )
                val analysisElapsed = System.currentTimeMillis() - analysisStart

                System.err.println("CompilerBridge: analyze($uri) — collectDiagnostics took ${analysisElapsed}ms, returned ${diagnostics.size} diagnostic(s)")
                for (d in diagnostics) {
                    System.err.println("  [${d.severity}] ${d.factoryName}: ${d.defaultMessage} at ${d.textRanges.firstOrNull()}")
                }

                for (diagnostic in diagnostics) {
                    // Filter false UNRESOLVED_REFERENCE for virtual files: if the unresolved
                    // name is declared in the same file, it's a false positive caused by the
                    // standalone API's inability to resolve same-file declarations in non-session files.
                    if (isVirtualFile && diagnostic.factoryName == "UNRESOLVED_REFERENCE" && declaredNames.isNotEmpty()) {
                        // Extract the unresolved name from the message ("Unresolved reference 'name'")
                        // or from the text range as fallback
                        val unresolvedName = diagnostic.defaultMessage
                            ?.let { Regex("'(\\w+)'").find(it)?.groupValues?.get(1) }
                            ?: diagnostic.textRanges.firstOrNull()?.let { range ->
                                ktFile.text.substring(
                                    range.startOffset,
                                    minOf(range.endOffset, ktFile.text.length)
                                )
                            }
                        if (unresolvedName != null && unresolvedName in declaredNames) {
                            System.err.println("CompilerBridge: analyze($uri) — suppressing false UNRESOLVED_REFERENCE for '$unresolvedName' (declared in same file)")
                            continue
                        }
                    }

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
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: analysis failed for $uri: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace(System.err)
        }

        result.add("diagnostics", diagnosticsArray)
        return result
    }

    /**
     * Analyzes all source files in the session and returns diagnostics for each.
     * Used for project-wide diagnostics after initial startup.
     */
    fun analyzeAll(): JsonObject {
        val result = JsonObject()
        val filesArray = JsonArray()

        val currentSession = session
        if (currentSession == null) {
            System.err.println("CompilerBridge: analyzeAll() — session is NULL, returning empty")
            result.add("files", filesArray)
            return result
        }

        val allKtFiles = currentSession.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()

        System.err.println("CompilerBridge: analyzeAll() — ${allKtFiles.size} file(s) in session")

        var analyzedCount = 0
        var errorCount = 0
        var warningCount = 0

        for (ktFile in allKtFiles) {
            val filePath = ktFile.virtualFile.path

            // Skip Gradle build scripts (.gradle.kts and .kts in buildSrc/gradle dirs)
            if (filePath.endsWith(".gradle.kts") ||
                (filePath.endsWith(".kts") && ("/buildSrc/" in filePath || "/gradle/" in filePath))) {
                System.err.println("CompilerBridge: analyzeAll() — skipping build script: $filePath")
                continue
            }

            // Skip files in build/output directories
            if ("/build/" in filePath || "/.gradle/" in filePath) continue

            val fileUri = "file://$filePath"

            // Use virtual content if the file is open in the editor
            val effectiveKtFile = if (hasVirtualFile(fileUri)) {
                findKtFile(currentSession, fileUri) ?: ktFile
            } else {
                ktFile
            }

            val fileObj = JsonObject()
            fileObj.addProperty("uri", fileUri)
            val diagnosticsArray = JsonArray()

            try {
                analyze(effectiveKtFile) {
                    val analysisStart = System.currentTimeMillis()
                    val diagnostics = effectiveKtFile.collectDiagnostics(
                        KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
                    )
                    val analysisElapsed = System.currentTimeMillis() - analysisStart

                    if (diagnostics.isNotEmpty()) {
                        System.err.println("CompilerBridge: analyzeAll() — $filePath: ${diagnostics.size} diagnostic(s) in ${analysisElapsed}ms")
                    }

                    for (diagnostic in diagnostics) {
                        val diagObj = JsonObject()
                        diagObj.addProperty("severity", diagnostic.severity.name)
                        diagObj.addProperty("message", diagnostic.defaultMessage)
                        diagObj.addProperty("code", diagnostic.factoryName)

                        val textRange = diagnostic.textRanges.firstOrNull()
                        if (textRange != null) {
                            try {
                                val document = effectiveKtFile.viewProvider.document
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

                        when (diagnostic.severity.name) {
                            "ERROR" -> errorCount++
                            "WARNING" -> warningCount++
                        }
                    }
                }
            } catch (e: Throwable) {
                System.err.println("CompilerBridge: analyzeAll() — error analyzing $filePath: ${e.javaClass.name}: ${e.message}")
            }

            fileObj.add("diagnostics", diagnosticsArray)
            filesArray.add(fileObj)
            analyzedCount++
        }

        System.err.println("CompilerBridge: analyzeAll() — analyzed $analyzedCount files, $errorCount errors, $warningCount warnings")

        result.add("files", filesArray)
        result.addProperty("totalFiles", analyzedCount)
        result.addProperty("totalErrors", errorCount)
        result.addProperty("totalWarnings", warningCount)
        return result
    }

    /**
     * Provides hover information at the given position.
     * Returns type/signature information with KDoc documentation when available.
     */
    fun hover(uri: String, line: Int, character: Int): JsonObject {
        ensureSessionCurrent()
        val result = JsonObject()
        val perfStart = System.currentTimeMillis()
        var resolvedOffset: Int? = null
        val setFailureReason = { reason: String ->
            if (!result.has("reason")) {
                result.addProperty("reason", reason)
            }
        }

        val currentSession = session ?: run {
            setFailureReason("no-active-session")
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            setFailureReason("file-not-found")
            return result
        }

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character)
                if (offset == null) {
                    val reason = "line-col-offset-null (line=$line, char=$character, fileLines=${ktFile.text.lines().size})"
                    setFailureReason(reason)
                    System.err.println("CompilerBridge: hover — $reason")
                    return@analyze
                }
                resolvedOffset = offset

                val element = ktFile.findElementAt(offset) ?: ktFile.findElementAt(maxOf(0, offset - 1))
                if (element == null) {
                    val reason = "findElementAt=null (offset=$offset, textLen=${ktFile.text.length})"
                    setFailureReason(reason)
                    System.err.println("CompilerBridge: hover — $reason")
                    return@analyze
                }

                // Walk up to find the nearest meaningful element
                var current: PsiElement? = element
                while (current != null) {
                    if (current is PsiWhiteSpace || current is PsiComment) {
                        current = current.parent
                        continue
                    }

                    // Handle annotation entries specially — show the annotation CLASS info
                    if (current is org.jetbrains.kotlin.psi.KtAnnotationEntry) {
                        val hoverText = buildAnnotationEntryHover(current)
                        if (hoverText != null) {
                            result.addProperty("contents", hoverText)
                            result.remove("reason")
                            return@analyze
                        }
                    }

                    if (current is KtNamedDeclaration) {
                        val hoverText = buildDeclarationHover(current)
                        if (hoverText != null) {
                            result.addProperty("contents", hoverText)
                            result.remove("reason")
                            return@analyze
                        }
                    }

                    if (current is KtReferenceExpression) {
                        // If inside an annotation entry, let the annotation entry handler
                        // (found when walking further up) show annotation class info instead
                        val insideAnnotation = PsiTreeUtil.getParentOfType(current, org.jetbrains.kotlin.psi.KtAnnotationEntry::class.java) != null
                        if (!insideAnnotation) {
                            val hoverText = buildReferenceHover(current)
                            if (hoverText != null) {
                                result.addProperty("contents", hoverText)
                                result.remove("reason")
                                return@analyze
                            }
                        }
                    }

                    if (current is KtExpression) {
                        // If inside an annotation entry, skip — let the annotation handler deal with it
                        val insideAnnotationExpr = PsiTreeUtil.getParentOfType(current, org.jetbrains.kotlin.psi.KtAnnotationEntry::class.java) != null
                        if (!insideAnnotationExpr) {
                            try {
                                val type = current.expressionType
                                if (type != null) {
                                    val rendered = type.render(
                                        KaTypeRendererForSource.WITH_SHORT_NAMES,
                                        Variance.INVARIANT
                                    )
                                    result.addProperty("contents", "```kotlin\n$rendered\n```")
                                    result.remove("reason")
                                    return@analyze
                                }
                            } catch (_: Exception) {
                                // expressionType may not be available for all expressions
                            }
                        }
                    }

                    current = current.parent
                }

                if (!result.has("reason")) {
                    setFailureReason("no-hoverable-element (line=$line, char=$character, offset=${resolvedOffset ?: -1})")
                }
            }
        } catch (e: Throwable) {
            val reason = "hover failed: ${e.javaClass.name}: ${e.message}"
            setFailureReason(reason)
            System.err.println("CompilerBridge: $reason")
        }

        System.err.println("[PERF] method=hover uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
        return result
    }

    /**
     * Provides completion items at the given position.
     * Supports both scope-based completions and dot-member completions.
     */
    fun completion(uri: String, line: Int, character: Int): JsonObject {
        ensureSessionCurrent()
        val result = JsonObject()
        val itemsArray = JsonArray()
        val perfStart = System.currentTimeMillis()

        val currentSession = session ?: run {
            result.add("items", itemsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("items", itemsArray)
            return result
        }

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: run {
                    result.add("items", itemsArray)
                    return@analyze
                }

                val element = ktFile.findElementAt(offset)
                    ?: ktFile.findElementAt(maxOf(0, offset - 1))

                if (element == null) {
                    result.add("items", itemsArray)
                    return@analyze
                }

                // Check if we are in a dot-qualified expression (member completion)
                val dotCompletion = findDotCompletionReceiver(element, offset)
                if (dotCompletion != null) {
                    collectMemberCompletions(dotCompletion, itemsArray)
                } else {
                    // Scope-based completion: gather declarations from enclosing scopes
                    collectScopeCompletions(ktFile, element, itemsArray)

                    // Append unimported symbols from the index
                    val prefix = extractCompletionPrefix(element, offset)
                    if (prefix.isNotEmpty()) {
                        appendUnimportedCompletions(ktFile, prefix, itemsArray)
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: completion failed: ${e.javaClass.name}: ${e.message}")
        }

        System.err.println("[PERF] method=completion uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
        result.add("items", itemsArray)
        return result
    }

    /**
     * Provides go-to-definition locations.
     */
    fun definition(uri: String, line: Int, character: Int): JsonObject {
        ensureSessionCurrent()
        ensurePebbleSpringIndexCurrent()
        val result = JsonObject()
        val locationsArray = JsonArray()
        val perfStart = System.currentTimeMillis()

        val springTemplateUri = pebbleSpringIndex.definitionForKotlinView(uri, line, character)
        if (springTemplateUri != null) {
            val location = JsonObject()
            location.addProperty("uri", springTemplateUri)
            location.addProperty("line", 1)
            location.addProperty("column", 0)
            locationsArray.add(location)
            result.add("locations", locationsArray)
            return result
        }

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
                    val springProducer = findSpringModelProducerDeclaration(element)
                    if (springProducer != null) {
                        val declarationUri = ((springProducer.containingFile as? KtFile)?.virtualFile?.path)?.let { "file://$it" } ?: uri
                        for (usage in pebbleSpringIndex.pebbleUsagesForDeclaration(springProducer, declarationUri)) {
                            val location = JsonObject()
                            location.addProperty("uri", usage.uri)
                            location.addProperty("line", usage.line)
                            location.addProperty("column", usage.column)
                            locationsArray.add(location)
                        }
                        return@analyze
                    }

                    // Find the reference element
                    var current: PsiElement? = element
                    while (current != null) {
                        // Handle annotation entries: resolve to the annotation class
                        if (current is org.jetbrains.kotlin.psi.KtAnnotationEntry) {
                            try {
                                val typeRef = current.typeReference
                                val type = typeRef?.type
                                val classSymbol = (type as? org.jetbrains.kotlin.analysis.api.types.KaClassType)
                                    ?.symbol as? KaDeclarationSymbol
                                if (classSymbol != null) {
                                    val loc = buildDefinitionLocation(classSymbol, uri)
                                    if (loc != null) {
                                        locationsArray.add(loc)
                                    }
                                }
                            } catch (e: Throwable) {
                                System.err.println("CompilerBridge: definition annotation resolve failed: ${e.message?.take(100)}")
                            }
                            break
                        }

                        if (current is KtReferenceExpression) {
                            // First try Analysis API resolution (handles library symbols better)
                            var resolved = false
                            for (ref in current.references) {
                                if (ref is KtReference) {
                                    try {
                                        var symbol = ref.resolveToSymbol()
                                        // For constructor calls, navigate to the class
                                        if (symbol is KaConstructorSymbol) {
                                            val container = symbol.containingDeclaration
                                            if (container is KaDeclarationSymbol) {
                                                symbol = container
                                            }
                                        }
                                        if (symbol is KaDeclarationSymbol) {
                                            val loc = buildDefinitionLocation(symbol, uri)
                                            if (loc != null) {
                                                locationsArray.add(loc)
                                                resolved = true
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        System.err.println("CompilerBridge: definition resolveToSymbol failed: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                                    }
                                }
                            }

                            // Fall back to PSI-based resolve if Analysis API didn't work
                            if (!resolved) {
                                for (ref in current.references) {
                                    try {
                                        val psiTarget = ref.resolve()
                                        if (psiTarget != null) {
                                            val loc = buildPsiDefinitionLocation(psiTarget, uri)
                                            if (loc != null) {
                                                locationsArray.add(loc)
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        System.err.println("CompilerBridge: definition ref.resolve() failed: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                                    }
                                }
                            }
                            break
                        }
                        current = current.parent
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: definition failed: ${e.javaClass.name}: ${e.message}")
        }

        System.err.println("[PERF] method=definition uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
        result.add("locations", locationsArray)
        return result
    }

    /**
     * Builds a definition location from a KaDeclarationSymbol using the Analysis API.
     * Handles both source files and library symbols (inside JARs).
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildDefinitionLocation(symbol: KaDeclarationSymbol, fallbackUri: String): JsonObject? {
        val isLibrary = try {
            symbol.origin.name.contains("LIBRARY", ignoreCase = true)
        } catch (_: Throwable) { false }

        // For library symbols, try to build a location from PSI cautiously,
        // then fall back to rendering the symbol as a stub file
        if (isLibrary) {
            val psi = try { symbol.psi } catch (_: Throwable) { null }
            if (psi != null) {
                try {
                    val file = psi.containingFile
                    val vfPath = file?.virtualFile?.path
                    if (file != null && vfPath != null) {
                        val textOffset = try { psi.textOffset } catch (_: Throwable) { 0 }
                        // file.text is null for compiled .class files in standalone mode
                        val text = try { file.text } catch (_: Throwable) { null }
                        if (text != null && textOffset <= text.length) {
                            val beforeOffset = text.substring(0, textOffset)
                            val lineNumber = beforeOffset.count { it == '\n' } + 1
                            val lastNewline = beforeOffset.lastIndexOf('\n')
                            val column = if (lastNewline >= 0) textOffset - lastNewline - 1 else textOffset

                            val decompiledPath = writeDecompiledFile(vfPath, text)
                            val uri = if (decompiledPath != null) {
                                "file://${decompiledPath}"
                            } else {
                                buildFileUri(vfPath)
                            }

                            val loc = JsonObject()
                            loc.addProperty("uri", uri)
                            loc.addProperty("line", lineNumber)
                            loc.addProperty("column", column)
                            return loc
                        }

                        // For compiled classes, render the symbol as a stub file
                        val rendered = renderLibrarySymbolStub(symbol, vfPath)
                        if (rendered != null) return rendered
                    }
                } catch (e: Throwable) {
                    System.err.println("CompilerBridge: buildDefinitionLocation PSI access failed: ${e.javaClass.simpleName}")
                }
            }

            // Fallback: render symbol stub using classId for the file path
            try {
                val classId = when (symbol) {
                    is KaClassLikeSymbol -> symbol.classId
                    is KaCallableSymbol -> symbol.callableId?.classId
                    else -> null
                }
                if (classId != null) {
                    val pkg = classId.packageFqName.asString().replace('.', '/')
                    val className = classId.shortClassName.asString()
                    val syntheticPath = "$pkg/$className.class"
                    val rendered = renderLibrarySymbolStub(symbol, syntheticPath)
                    if (rendered != null) return rendered

                    // Last resort: return custom-scheme URI
                    val loc = JsonObject()
                    loc.addProperty("uri", "kotlin-analyzer:///library/$pkg/$className.class")
                    loc.addProperty("line", 1)
                    loc.addProperty("column", 0)
                    return loc
                }
            } catch (e: Throwable) {
                System.err.println("CompilerBridge: buildDefinitionLocation synthetic failed: ${e.message?.take(100)}")
            }

            return null
        }

        // For source symbols, use standard PSI location
        val psi = try { symbol.psi } catch (_: Throwable) { null }
        if (psi != null) {
            return buildPsiDefinitionLocation(psi, fallbackUri)
        }
        return null
    }

    /**
     * Builds a definition location from a resolved PsiElement (PSI-based fallback).
     */
    private fun buildPsiDefinitionLocation(psiTarget: PsiElement, fallbackUri: String): JsonObject? {
        try {
            val file = psiTarget.containingFile ?: return null
            val textOffset = psiTarget.textOffset
            val doc = file.viewProvider?.document
            val vfPath = file.virtualFile?.path

            if (doc != null) {
                val targetUri = if (vfPath != null && !vfPath.contains("dummy")) {
                    buildFileUri(vfPath)
                } else {
                    fallbackUri
                }
                val loc = JsonObject()
                loc.addProperty("uri", targetUri)
                loc.addProperty("line", doc.getLineNumber(textOffset) + 1)
                loc.addProperty("column", textOffset - doc.getLineStartOffset(doc.getLineNumber(textOffset)))
                return loc
            }

            // Fallback for JAR/library files
            if (vfPath != null && file.text != null) {
                val text = file.text
                val beforeOffset = text.substring(0, minOf(textOffset, text.length))
                val lineNumber = beforeOffset.count { it == '\n' } + 1
                val lastNewline = beforeOffset.lastIndexOf('\n')
                val column = if (lastNewline >= 0) textOffset - lastNewline - 1 else textOffset

                val loc = JsonObject()
                loc.addProperty("uri", buildFileUri(vfPath))
                loc.addProperty("line", lineNumber)
                loc.addProperty("column", column)
                return loc
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: buildPsiDefinitionLocation failed: ${e.message?.take(100)}")
        }
        return null
    }

    /**
     * Builds a file:// URI from a virtual file path, handling JAR paths.
     */
    private fun buildFileUri(vfPath: String): String {
        return if (vfPath.contains("!/")) {
            // JAR path: convert jar:///path/to/file.jar!/com/Foo.class to file URI
            // Zed can open decompiled class files if the path is a real file
            "file://$vfPath"
        } else {
            "file://$vfPath"
        }
    }

    /**
     * Provides find-references locations within the same file.
     * In standalone mode without full indexing, we search for references
     * to the target declaration by walking the PSI tree of the containing file.
     */
    fun references(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val locationsArray = JsonArray()
        val perfStart = System.currentTimeMillis()

        val currentSession = session ?: run {
            result.add("locations", locationsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("locations", locationsArray)
            return result
        }

        try {
            ensurePebbleSpringIndexCurrent()

            // Step 1: Find the target declaration in the current file and extract its identity
            var targetName: String? = null
            var targetFilePath: String? = null
            var targetOffset: Int? = null
            var targetDeclaration: PsiElement? = null

            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: return@analyze
                val element = ktFile.findElementAt(offset) ?: return@analyze

                val springProducer = findSpringModelProducerDeclaration(element)
                if (springProducer != null) {
                    targetDeclaration = springProducer
                } else {
                    targetDeclaration = findTargetDeclaration(element) ?: return@analyze
                }

                // Extract identity: the declaration's name, containing file, and offset
                targetName = (targetDeclaration as? KtNamedDeclaration)?.name
                    ?: targetDeclaration?.text?.take(50)
                val containingFile = targetDeclaration?.containingFile as? KtFile
                targetFilePath = containingFile?.virtualFile?.path
                targetOffset = targetDeclaration?.textOffset

                // Also add the declaration itself as a reference location
                val resolvedDeclaration = targetDeclaration ?: return@analyze
                val declDocument = containingFile?.viewProvider?.document
                if (declDocument != null) {
                    val declLine = declDocument.getLineNumber(resolvedDeclaration.textOffset) + 1
                    val declLineStart = declDocument.getLineStartOffset(declDocument.getLineNumber(resolvedDeclaration.textOffset))
                    val declCol = resolvedDeclaration.textOffset - declLineStart
                    val declLoc = JsonObject()
                    declLoc.addProperty("uri", "file://${containingFile.virtualFile.path}")
                    declLoc.addProperty("line", declLine)
                    declLoc.addProperty("column", declCol)
                    locationsArray.add(declLoc)
                }
            }

            if (targetName == null || targetFilePath == null) {
                result.add("locations", locationsArray)
                return result
            }

            // Step 2: Search ALL session files for references to the target
            val allSessionFiles = currentSession.modulesWithFiles.entries
                .flatMap { (_, files) -> files }
                .filterIsInstance<KtFile>()

            for (sessionFile in allSessionFiles) {
                try {
                    analyze(sessionFile) {
                        val nameExprs = PsiTreeUtil.collectElementsOfType(
                            sessionFile, KtSimpleNameExpression::class.java
                        )

                        for (nameExpr in nameExprs) {
                            if (nameExpr.getReferencedName() != targetName) continue

                            try {
                                for (ref in nameExpr.references) {
                                    val resolved = ref.resolve() ?: continue
                                    val resolvedFile = resolved.containingFile as? KtFile ?: continue
                                    // Match by file path + offset to identify the same declaration
                                    if (resolvedFile.virtualFile.path == targetFilePath &&
                                        resolved.textOffset == targetOffset) {
                                        val doc = sessionFile.viewProvider.document ?: continue
                                        val refOffset = nameExpr.textOffset
                                        val refLine = doc.getLineNumber(refOffset) + 1
                                        val refLineStart = doc.getLineStartOffset(doc.getLineNumber(refOffset))
                                        val refCol = refOffset - refLineStart

                                        val loc = JsonObject()
                                        loc.addProperty("uri", "file://${sessionFile.virtualFile.path}")
                                        loc.addProperty("line", refLine)
                                        loc.addProperty("column", refCol)

                                        // Avoid duplicates
                                        val isDup = (0 until locationsArray.size()).any { i ->
                                            val existing = locationsArray[i].asJsonObject
                                            existing.get("uri")?.asString == loc.get("uri")?.asString &&
                                                existing.get("line")?.asInt == refLine &&
                                                existing.get("column")?.asInt == refCol
                                        }
                                        if (!isDup) locationsArray.add(loc)
                                    }
                                }
                            } catch (_: Exception) {
                                // Skip references that fail to resolve
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip files that fail to analyze
                }
            }

            targetDeclaration?.let { declaration ->
                val declarationKtFile = declaration.containingFile as? KtFile
                val declarationUri = declarationKtFile?.virtualFile?.path?.let { "file://$it" } ?: uri
                for (usage in pebbleSpringIndex.pebbleUsagesForDeclaration(declaration, declarationUri)) {
                    val isDup = (0 until locationsArray.size()).any { i ->
                        val existing = locationsArray[i].asJsonObject
                        existing.get("uri")?.asString == usage.uri &&
                            existing.get("line")?.asInt == usage.line &&
                            existing.get("column")?.asInt == usage.column
                    }
                    if (!isDup) {
                        val loc = JsonObject()
                        loc.addProperty("uri", usage.uri)
                        loc.addProperty("line", usage.line)
                        loc.addProperty("column", usage.column)
                        locationsArray.add(loc)
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: references failed: ${e.javaClass.name}: ${e.message}")
        }

        System.err.println("[PERF] method=references uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
        result.add("locations", locationsArray)
        return result
    }

    /**
     * Provides signature help for function calls at the given position.
     * Finds the enclosing call expression, resolves the callee, and returns
     * parameter information with the active parameter index.
     */
    fun signatureHelp(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val signaturesArray = JsonArray()

        val currentSession = session ?: run {
            result.add("signatures", signaturesArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("signatures", signaturesArray)
            return result
        }

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: run {
                    result.add("signatures", signaturesArray)
                    return@analyze
                }

                val element = ktFile.findElementAt(offset)
                    ?: ktFile.findElementAt(maxOf(0, offset - 1))

                if (element == null) {
                    result.add("signatures", signaturesArray)
                    return@analyze
                }

                // Find the enclosing call expression
                val callExpression = PsiTreeUtil.getParentOfType(
                    element, KtCallExpression::class.java, false
                ) ?: run {
                    result.add("signatures", signaturesArray)
                    return@analyze
                }

                // Determine the active parameter index based on cursor position
                val activeParam = computeActiveParameter(callExpression, offset)

                // Resolve the call to get signature information
                val callInfo = callExpression.resolveToCall()
                if (callInfo is KaSuccessCallInfo) {
                    val call = callInfo.call
                    if (call is KaFunctionCall<*>) {
                        val signature = call.partiallyAppliedSymbol.signature
                        val functionSymbol = signature.symbol

                        // Build the signature label
                        val paramLabels = mutableListOf<String>()
                        val paramObjects = JsonArray()

                        for (paramSig in signature.valueParameters) {
                            val paramName = paramSig.name.asString()
                            val paramType = paramSig.returnType.render(
                                KaTypeRendererForSource.WITH_SHORT_NAMES,
                                Variance.INVARIANT
                            )
                            val paramLabel = "$paramName: $paramType"
                            paramLabels.add(paramLabel)

                            val paramObj = JsonObject()
                            paramObj.addProperty("label", paramLabel)
                            paramObjects.add(paramObj)
                        }

                        val functionName = when (functionSymbol) {
                            is KaNamedFunctionSymbol -> functionSymbol.name.asString()
                            is KaConstructorSymbol -> {
                                val containingClass = functionSymbol.containingDeclaration
                                if (containingClass is KaNamedSymbol) {
                                    containingClass.name.asString()
                                } else {
                                    "<init>"
                                }
                            }
                            else -> "invoke"
                        }

                        val returnType = signature.returnType.render(
                            KaTypeRendererForSource.WITH_SHORT_NAMES,
                            Variance.INVARIANT
                        )
                        val signatureLabel = "$functionName(${paramLabels.joinToString(", ")}): $returnType"

                        val sigObj = JsonObject()
                        sigObj.addProperty("label", signatureLabel)
                        sigObj.add("parameters", paramObjects)
                        sigObj.addProperty("activeParameter", activeParam)

                        // Add KDoc documentation if available
                        val psi = functionSymbol.psi
                        if (psi is KtDeclaration) {
                            val kdocText = extractKDocText(psi)
                            if (kdocText != null) {
                                sigObj.addProperty("documentation", kdocText)
                            }
                        }

                        signaturesArray.add(sigObj)
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: signatureHelp failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("signatures", signaturesArray)
        result.addProperty("activeSignature", 0)
        return result
    }

    /**
     * Provides rename edits for a symbol at the given position.
     * Finds the declaration and all references within the file,
     * then returns edits to replace each occurrence with the new name.
     */
    fun rename(uri: String, line: Int, character: Int, newName: String): JsonObject {
        val result = JsonObject()
        val editsArray = JsonArray()

        val currentSession = session ?: run {
            result.add("edits", editsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("edits", editsArray)
            return result
        }

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: run {
                    result.add("edits", editsArray)
                    return@analyze
                }

                val element = ktFile.findElementAt(offset) ?: run {
                    result.add("edits", editsArray)
                    return@analyze
                }

                val targetDeclaration = findTargetDeclaration(element)
                if (targetDeclaration == null) {
                    result.add("edits", editsArray)
                    return@analyze
                }

                val document = ktFile.viewProvider.document
                if (document == null) {
                    result.add("edits", editsArray)
                    return@analyze
                }

                val fileUri = uri

                // Collect all locations that need renaming (references + declaration)
                val renameOffsets = mutableSetOf<Int>()

                // Find the declaration's name element offset and length
                val declNameOffset: Int
                val declNameLength: Int
                if (targetDeclaration is KtNamedDeclaration) {
                    val nameIdentifier = targetDeclaration.nameIdentifier
                    if (nameIdentifier != null) {
                        declNameOffset = nameIdentifier.textOffset
                        declNameLength = nameIdentifier.textLength
                        renameOffsets.add(declNameOffset)
                    } else {
                        declNameOffset = targetDeclaration.textOffset
                        declNameLength = targetDeclaration.name?.length ?: 0
                        renameOffsets.add(declNameOffset)
                    }
                } else {
                    declNameOffset = targetDeclaration.textOffset
                    declNameLength = targetDeclaration.textLength
                    renameOffsets.add(declNameOffset)
                }

                // Walk the file PSI tree for all references to the target
                val allNameExprs = PsiTreeUtil.collectElementsOfType(
                    ktFile, KtSimpleNameExpression::class.java
                )

                for (nameExpr in allNameExprs) {
                    try {
                        for (ref in nameExpr.references) {
                            val resolved = ref.resolve()
                            if (resolved != null && resolved == targetDeclaration) {
                                renameOffsets.add(nameExpr.textOffset)
                            }
                        }
                    } catch (_: Exception) {
                        // Skip references that fail to resolve
                    }
                }

                // Build edit objects for each location
                for (renameOffset in renameOffsets) {
                    val startLine = document.getLineNumber(renameOffset) + 1
                    val startLineStart = document.getLineStartOffset(document.getLineNumber(renameOffset))
                    val startCol = renameOffset - startLineStart

                    // Determine the length of the name at this offset
                    val nameLen = if (renameOffset == declNameOffset) {
                        declNameLength
                    } else {
                        // For references, use the name expression text length
                        val refElement = ktFile.findElementAt(renameOffset)
                        refElement?.textLength ?: newName.length
                    }

                    val endOffset = renameOffset + nameLen
                    val endLine = document.getLineNumber(endOffset) + 1
                    val endLineStart = document.getLineStartOffset(document.getLineNumber(endOffset))
                    val endCol = endOffset - endLineStart

                    val edit = JsonObject()
                    edit.addProperty("uri", fileUri)
                    val range = JsonObject()
                    range.addProperty("startLine", startLine)
                    range.addProperty("startColumn", startCol)
                    range.addProperty("endLine", endLine)
                    range.addProperty("endColumn", endCol)
                    edit.add("range", range)
                    edit.addProperty("newText", newName)
                    editsArray.add(edit)
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: rename failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("edits", editsArray)
        return result
    }

    /**
     * Provides code actions at the given position.
     * Includes:
     * - Diagnostic-based quick-fixes (suppress warning, add import)
     * - Context-aware refactoring actions (add/remove explicit type, convert body style)
     */
    fun codeActions(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val actionsArray = JsonArray()
        val perfStart = System.currentTimeMillis()

        System.err.println("CompilerBridge: codeActions($uri, line=$line, char=$character)")

        val currentSession = session ?: run {
            System.err.println("CompilerBridge: codeActions — session is NULL")
            result.add("actions", actionsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            System.err.println("CompilerBridge: codeActions — file not found")
            result.add("actions", actionsArray)
            return result
        }

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: run {
                    result.add("actions", actionsArray)
                    return@analyze
                }

                val document = ktFile.viewProvider.document
                if (document == null) {
                    result.add("actions", actionsArray)
                    return@analyze
                }

                val fileUri = uri

                // 1. Diagnostic-based code actions
                try {
                    val diagnostics = ktFile.collectDiagnostics(
                        KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
                    )

                    for (diagnostic in diagnostics) {
                        val textRange = diagnostic.textRanges.firstOrNull() ?: continue

                        // Use line-level matching: check if the cursor line overlaps the diagnostic
                        val diagStartLine = document.getLineNumber(textRange.startOffset)
                        val diagEndLine = document.getLineNumber(textRange.endOffset)
                        val cursorLine = line - 1 // Convert to 0-based

                        if (cursorLine < diagStartLine || cursorLine > diagEndLine) continue

                        val factoryName = diagnostic.factoryName ?: continue
                        val severityName = diagnostic.severity.name

                        // Suppress warning action for warnings
                        if (severityName == "WARNING") {
                            val action = JsonObject()
                            action.addProperty("title", "Suppress warning '$factoryName'")
                            action.addProperty("kind", "quickfix")

                            val actionEdits = JsonArray()

                            val element = ktFile.findElementAt(textRange.startOffset)
                            val enclosingDeclaration = findEnclosingDeclaration(element)

                            if (enclosingDeclaration != null) {
                                val declOffset = enclosingDeclaration.textOffset
                                val declLine = document.getLineNumber(declOffset) + 1
                                val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
                                val declCol = declOffset - declLineStart

                                val lineEndOffset = document.getLineEndOffset(document.getLineNumber(declOffset))
                                val lineText = document.charsSequence.subSequence(declLineStart, lineEndOffset).toString()
                                val indent = lineText.takeWhile { it == ' ' || it == '\t' }

                                val suppressEdit = JsonObject()
                                suppressEdit.addProperty("uri", fileUri)
                                val suppressRange = JsonObject()
                                suppressRange.addProperty("startLine", declLine)
                                suppressRange.addProperty("startColumn", declCol)
                                suppressRange.addProperty("endLine", declLine)
                                suppressRange.addProperty("endColumn", declCol)
                                suppressEdit.add("range", suppressRange)
                                suppressEdit.addProperty("newText", "@Suppress(\"$factoryName\")\n$indent")
                                actionEdits.add(suppressEdit)
                            }

                            action.add("edits", actionEdits)
                            actionsArray.add(action)
                        }

                        // Add import action for unresolved references
                        if (factoryName == "UNRESOLVED_REFERENCE") {
                            val unresolvedText = ktFile.text.substring(
                                textRange.startOffset,
                                minOf(textRange.endOffset, ktFile.text.length)
                            )

                            val importCandidates = findImportCandidates(unresolvedText)
                            val importInsertLine = findImportInsertLine(ktFile, document)

                            if (importCandidates.isNotEmpty()) {
                                for (fqn in importCandidates) {
                                    val action = JsonObject()
                                    action.addProperty("title", "Import '$fqn'")
                                    action.addProperty("kind", "quickfix")

                                    val actionEdits = JsonArray()
                                    val importEdit = JsonObject()
                                    importEdit.addProperty("uri", fileUri)
                                    val importRange = JsonObject()
                                    importRange.addProperty("startLine", importInsertLine)
                                    importRange.addProperty("startColumn", 0)
                                    importRange.addProperty("endLine", importInsertLine)
                                    importRange.addProperty("endColumn", 0)
                                    importEdit.add("range", importRange)
                                    importEdit.addProperty("newText", "import $fqn\n")
                                    actionEdits.add(importEdit)

                                    action.add("edits", actionEdits)
                                    actionsArray.add(action)
                                }
                            } else {
                                // Fallback: offer the short name import
                                val action = JsonObject()
                                action.addProperty("title", "Add import for '$unresolvedText'")
                                action.addProperty("kind", "quickfix")

                                val actionEdits = JsonArray()
                                val importEdit = JsonObject()
                                importEdit.addProperty("uri", fileUri)
                                val importRange = JsonObject()
                                importRange.addProperty("startLine", importInsertLine)
                                importRange.addProperty("startColumn", 0)
                                importRange.addProperty("endLine", importInsertLine)
                                importRange.addProperty("endColumn", 0)
                                importEdit.add("range", importRange)
                                importEdit.addProperty("newText", "import $unresolvedText\n")
                                actionEdits.add(importEdit)

                                action.add("edits", actionEdits)
                                actionsArray.add(action)
                            }
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("CompilerBridge: diagnostic code actions failed: ${e.message}")
                }

                // 2. Context-aware refactoring actions
                val element = ktFile.findElementAt(offset)
                if (element != null) {
                    // Add explicit type annotation for properties without one
                    addExplicitTypeAction(element, document, fileUri, actionsArray)

                    // Convert between expression body and block body for functions
                    addConvertBodyAction(element, document, fileUri, actionsArray)

                    // Structural refactoring actions
                    addMoveToCompanionAction(element, document, fileUri, actionsArray)
                    addMoveFromCompanionAction(element, document, fileUri, actionsArray)
                    addMoveToTopLevelAction(element, document, fileUri, actionsArray, ktFile)
                    addConvertToExtensionFunctionAction(element, document, fileUri, actionsArray)
                }

                // 3. Source actions: Organize imports
                addOrganizeImportsAction(ktFile, document, fileUri, actionsArray)
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: codeActions failed: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace(System.err)
        }

        System.err.println("CompilerBridge: codeActions($uri) — returning ${actionsArray.size()} action(s)")
        for (i in 0 until actionsArray.size()) {
            val action = actionsArray[i].asJsonObject
            System.err.println("  action[$i]: ${action.get("title")?.asString} (kind=${action.get("kind")?.asString})")
        }

        System.err.println("[PERF] method=codeActions uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
        result.add("actions", actionsArray)
        return result
    }

    /**
     * Adds "Add explicit type" code action for properties/variables that lack a type annotation.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.addExplicitTypeAction(
        element: PsiElement,
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        actionsArray: JsonArray,
    ) {
        try {
            val property = PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
            if (property != null && property.typeReference == null && property.initializer != null) {
                val nameIdentifier = property.nameIdentifier ?: return
                val symbol = property.symbol
                val returnType = symbol.returnType
                val rendered = returnType.render(
                    KaTypeRendererForSource.WITH_SHORT_NAMES,
                    Variance.INVARIANT
                )

                if (rendered.isNotEmpty() && rendered != "Unit" && rendered != "Nothing") {
                    val action = JsonObject()
                    action.addProperty("title", "Add explicit type '$rendered'")
                    action.addProperty("kind", "refactor")

                    val actionEdits = JsonArray()
                    val insertOffset = nameIdentifier.textRange.endOffset
                    val insertLine = document.getLineNumber(insertOffset) + 1
                    val insertLineStart = document.getLineStartOffset(document.getLineNumber(insertOffset))
                    val insertCol = insertOffset - insertLineStart

                    val edit = JsonObject()
                    edit.addProperty("uri", fileUri)
                    val range = JsonObject()
                    range.addProperty("startLine", insertLine)
                    range.addProperty("startColumn", insertCol)
                    range.addProperty("endLine", insertLine)
                    range.addProperty("endColumn", insertCol)
                    edit.add("range", range)
                    edit.addProperty("newText", ": $rendered")
                    actionEdits.add(edit)

                    action.add("edits", actionEdits)
                    actionsArray.add(action)
                }
            }
        } catch (_: Exception) {
            // Ignore failures in type resolution
        }
    }

    /**
     * Adds "Convert to expression body" / "Convert to block body" code action for functions.
     */
    private fun addConvertBodyAction(
        element: PsiElement,
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        actionsArray: JsonArray,
    ) {
        try {
            val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
                ?: return

            val body = function.bodyBlockExpression
            val expressionBody = function.bodyExpression

            if (body != null && body.statements.size == 1) {
                // Function has block body with single statement — offer conversion to expression body
                val singleStatement = body.statements.first()
                // Only convert if the statement is a return or a simple expression
                val expr = if (singleStatement is KtReturnExpression) {
                    singleStatement.returnedExpression
                } else {
                    singleStatement
                }

                if (expr != null) {
                    val action = JsonObject()
                    action.addProperty("title", "Convert to expression body")
                    action.addProperty("kind", "refactor")

                    val actionEdits = JsonArray()

                    // Replace from the opening brace to the closing brace with "= expr"
                    val bodyStart = body.textOffset
                    val bodyEnd = body.textRange.endOffset

                    val startLine = document.getLineNumber(bodyStart) + 1
                    val startLineStart = document.getLineStartOffset(document.getLineNumber(bodyStart))
                    val startCol = bodyStart - startLineStart

                    val endLine = document.getLineNumber(bodyEnd) + 1
                    val endLineStart = document.getLineStartOffset(document.getLineNumber(bodyEnd))
                    val endCol = bodyEnd - endLineStart

                    val edit = JsonObject()
                    edit.addProperty("uri", fileUri)
                    val range = JsonObject()
                    range.addProperty("startLine", startLine)
                    range.addProperty("startColumn", startCol)
                    range.addProperty("endLine", endLine)
                    range.addProperty("endColumn", endCol)
                    edit.add("range", range)
                    edit.addProperty("newText", "= ${expr.text}")
                    actionEdits.add(edit)

                    action.add("edits", actionEdits)
                    actionsArray.add(action)
                }
            } else if (expressionBody != null && body == null) {
                // Function has expression body — offer conversion to block body
                val equalsToken = function.equalsToken ?: return

                val action = JsonObject()
                action.addProperty("title", "Convert to block body")
                action.addProperty("kind", "refactor")

                val actionEdits = JsonArray()

                val exprStart = equalsToken.textOffset
                val exprEnd = expressionBody.textRange.endOffset

                val startLine = document.getLineNumber(exprStart) + 1
                val startLineStart = document.getLineStartOffset(document.getLineNumber(exprStart))
                val startCol = exprStart - startLineStart

                val endLine = document.getLineNumber(exprEnd) + 1
                val endLineStart = document.getLineStartOffset(document.getLineNumber(exprEnd))
                val endCol = exprEnd - endLineStart

                // Determine indentation
                val funcOffset = function.textOffset
                val funcLineStart = document.getLineStartOffset(document.getLineNumber(funcOffset))
                val funcLineEnd = document.getLineEndOffset(document.getLineNumber(funcOffset))
                val funcLineText = document.charsSequence.subSequence(funcLineStart, funcLineEnd).toString()
                val indent = funcLineText.takeWhile { it == ' ' || it == '\t' }

                val hasReturnType = function.typeReference != null

                val edit = JsonObject()
                edit.addProperty("uri", fileUri)
                val range = JsonObject()
                range.addProperty("startLine", startLine)
                range.addProperty("startColumn", startCol)
                range.addProperty("endLine", endLine)
                range.addProperty("endColumn", endCol)
                edit.add("range", range)

                val returnKeyword = if (hasReturnType) "return " else ""
                edit.addProperty("newText", "{\n$indent    ${returnKeyword}${expressionBody.text}\n$indent}")
                actionEdits.add(edit)

                action.add("edits", actionEdits)
                actionsArray.add(action)
            }
        } catch (_: Exception) {
            // Ignore failures in function body analysis
        }
    }

    /**
     * Adds "Organize imports" source action that sorts and deduplicates import statements.
     */
    private fun addOrganizeImportsAction(
        ktFile: KtFile,
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        actionsArray: JsonArray,
    ) {
        val importList = ktFile.importList ?: return
        val imports = importList.imports
        if (imports.isEmpty()) return

        // Collect and sort unique import paths
        val importPaths = imports.mapNotNull { it.importedFqName?.asString() }.distinct().sorted()

        // Check if already organized
        val currentPaths = imports.mapNotNull { it.importedFqName?.asString() }
        if (currentPaths == importPaths) return  // Already organized

        // Build the new import block text
        val newImportText = importPaths.joinToString("\n") { "import $it" }

        // Calculate the range of the existing import block
        val firstImport = imports.first()
        val lastImport = imports.last()
        val startLine = document.getLineNumber(firstImport.textRange.startOffset) + 1
        val startCol = 0
        val endLine = document.getLineNumber(lastImport.textRange.endOffset) + 1
        val endLineStart = document.getLineStartOffset(document.getLineNumber(lastImport.textRange.endOffset))
        val endCol = lastImport.textRange.endOffset - endLineStart

        val action = JsonObject()
        action.addProperty("title", "Organize imports")
        action.addProperty("kind", "source.organizeImports")

        val editsArray = JsonArray()
        val edit = JsonObject()
        edit.addProperty("uri", fileUri)
        val range = JsonObject()
        range.addProperty("startLine", startLine)
        range.addProperty("startColumn", startCol)
        range.addProperty("endLine", endLine)
        range.addProperty("endColumn", endCol)
        edit.add("range", range)
        edit.addProperty("newText", newImportText)
        editsArray.add(edit)

        action.add("edits", editsArray)
        actionsArray.add(action)
    }

    /**
     * Result of analyzing a declaration body for references to members of its containing class.
     */
    private data class BodyAnalysis(val referencesThis: Boolean, val accessesPrivateMembers: Boolean)

    /**
     * Analyzes a declaration body in a single pass for both `this` references and private member access.
     * Explicit `this`: KtThisExpression nodes.
     * Implicit `this`: KtSimpleNameExpression that resolves to a member of [containingClass].
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.analyzeBodyReferences(
        declaration: KtDeclaration,
        containingClass: KtClass,
    ): BodyAnalysis {
        val body = when (declaration) {
            is KtNamedFunction -> declaration.bodyExpression
            is KtProperty -> declaration.initializer
            else -> return BodyAnalysis(false, false)
        } ?: return BodyAnalysis(false, false)

        // Explicit this
        val hasExplicitThis = PsiTreeUtil.findChildrenOfType(body, KtThisExpression::class.java).isNotEmpty()

        var refsThis = hasExplicitThis
        var accessesPrivate = false

        // Single pass over name expressions for implicit this + private member access
        try {
            val classSymbol = containingClass.symbol
            for (nameExpr in PsiTreeUtil.findChildrenOfType(body, KtSimpleNameExpression::class.java)) {
                if (nameExpr is KtOperationReferenceExpression) continue
                try {
                    for (ref in nameExpr.references.filterIsInstance<KtReference>()) {
                        for (target in ref.resolveToSymbols()) {
                            if (target is KaCallableSymbol && target.containingSymbol == classSymbol) {
                                refsThis = true
                                if (target.visibility == KaSymbolVisibility.PRIVATE) {
                                    accessesPrivate = true
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip unresolvable references
                }
                if (refsThis && accessesPrivate) break
            }
        } catch (_: Exception) {
            // Symbol resolution not available — fall back to PSI name matching
            val memberNames = mutableSetOf<String>()
            val privateNames = mutableSetOf<String>()
            containingClass.body?.declarations?.forEach { decl ->
                if (decl != declaration && decl is KtNamedDeclaration) {
                    decl.name?.let { memberNames.add(it) }
                    if (decl.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
                        decl.name?.let { privateNames.add(it) }
                    }
                }
            }
            for (nameExpr in PsiTreeUtil.findChildrenOfType(body, KtSimpleNameExpression::class.java)) {
                if (nameExpr is KtOperationReferenceExpression) continue
                val name = nameExpr.getReferencedName()
                if (name in memberNames) refsThis = true
                if (name in privateNames) accessesPrivate = true
                if (refsThis && accessesPrivate) break
            }
        }

        return BodyAnalysis(refsThis, accessesPrivate)
    }

    /**
     * Builds a single edit JSON object.
     */
    private fun makeEdit(
        uri: String,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        newText: String,
    ): JsonObject {
        val edit = JsonObject()
        edit.addProperty("uri", uri)
        val range = JsonObject()
        range.addProperty("startLine", startLine)
        range.addProperty("startColumn", startCol)
        range.addProperty("endLine", endLine)
        range.addProperty("endColumn", endCol)
        edit.add("range", range)
        edit.addProperty("newText", newText)
        return edit
    }

    /**
     * Returns (1-based line, column) for a given offset in the document.
     */
    private fun offsetToLineCol(
        document: com.intellij.openapi.editor.Document,
        offset: Int,
    ): Pair<Int, Int> {
        val lineIndex = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineIndex)
        return Pair(lineIndex + 1, offset - lineStart)
    }

    /**
     * Creates an edit that deletes a PSI element, consuming a trailing newline if present.
     */
    private fun makeDeleteEdit(
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        element: PsiElement,
    ): JsonObject {
        val (startLine, startCol) = offsetToLineCol(document, element.textOffset)
        var endOffset = element.textRange.endOffset
        val docText = document.charsSequence
        if (endOffset < docText.length && docText[endOffset] == '\n') {
            endOffset++
        }
        val (endLine, endCol) = offsetToLineCol(document, endOffset)
        return makeEdit(fileUri, startLine, startCol, endLine, endCol, "")
    }

    /**
     * Creates an edit that appends text at the end of the file.
     */
    private fun makeAppendToFileEdit(
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        text: String,
    ): JsonObject {
        val lastLineEnd = document.getLineEndOffset(document.lineCount - 1)
        val (endLine, endCol) = offsetToLineCol(document, lastLineEnd)
        return makeEdit(fileUri, endLine, endCol, endLine, endCol, "\n\n$text")
    }

    /**
     * Returns the leading whitespace of the line containing the given offset.
     */
    private fun indentAt(document: com.intellij.openapi.editor.Document, offset: Int): String {
        val lineNum = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNum)
        val lineEnd = document.getLineEndOffset(lineNum)
        return document.charsSequence.subSequence(lineStart, lineEnd).toString()
            .takeWhile { it == ' ' || it == '\t' }
    }

    /**
     * Builds a clean modifier string for a function moved to top level,
     * removing class-level visibility modifiers (private, protected).
     */
    private fun cleanModifiersForTopLevel(function: KtNamedFunction): String {
        val modifierList = function.modifierList ?: return ""
        val kept = modifierList.node.getChildren(null).filter { child ->
            val type = child.elementType
            type != KtTokens.PRIVATE_KEYWORD && type != KtTokens.PROTECTED_KEYWORD &&
                type != KtTokens.WHITE_SPACE
        }
        if (kept.isEmpty()) return ""
        return kept.joinToString(" ") { it.text } + " "
    }

    /**
     * Adds "Move to companion object" code action for class members.
     * Offers to move a function or property into the companion object of its containing class.
     * Creates a companion object if none exists. Not offered when the body references `this`.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.addMoveToCompanionAction(
        element: PsiElement,
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        actionsArray: JsonArray,
    ) {
        try {
            val declaration = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
                ?: PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
                ?: return

            // Must be a direct member of a class (not in a companion)
            val parentClassBody = declaration.parent as? KtClassBody ?: return
            val parentClassOrObject = parentClassBody.parent
            if (parentClassOrObject is KtObjectDeclaration && parentClassOrObject.isCompanion()) return
            val containingClass = parentClassOrObject as? KtClass ?: return

            // Don't offer if body references this
            if (analyzeBodyReferences(declaration, containingClass).referencesThis) return

            val action = JsonObject()
            action.addProperty("title", "Move to companion object")
            action.addProperty("kind", "refactor")
            val actionEdits = JsonArray()

            val memberText = declaration.text
            val memberIndent = indentAt(document, declaration.textOffset)

            actionEdits.add(makeDeleteEdit(document, fileUri, declaration))

            // Find or create companion object
            val companion = containingClass.companionObjects.firstOrNull()
            if (companion != null) {
                // Insert into existing companion — before the closing brace
                val companionBody = companion.body
                if (companionBody != null) {
                    val closingBrace = companionBody.rBrace
                    if (closingBrace != null) {
                        val (insertLine, insertCol) = offsetToLineCol(document, closingBrace.textOffset)
                        val companionIndent = memberIndent + "    "
                        val reindentedText = memberText.lines().joinToString("\n") { line ->
                            if (line.isBlank()) line else "$companionIndent$line".trimEnd()
                        }
                        actionEdits.add(makeEdit(fileUri, insertLine, insertCol, insertLine, insertCol,
                            "$reindentedText\n$memberIndent"))
                    }
                }
            } else {
                // Create companion object — insert before closing brace of the class
                val classBody = containingClass.body
                if (classBody != null) {
                    val closingBrace = classBody.rBrace
                    if (closingBrace != null) {
                        val (insertLine, insertCol) = offsetToLineCol(document, closingBrace.textOffset)
                        val companionIndent = memberIndent + "    "
                        val reindentedMember = memberText.lines().joinToString("\n") { line ->
                            if (line.isBlank()) line else "$companionIndent    $line".trimEnd()
                        }
                        val companionText = "${memberIndent}companion object {\n$reindentedMember\n$memberIndent}\n"
                        actionEdits.add(makeEdit(fileUri, insertLine, insertCol, insertLine, insertCol,
                            "\n$companionText$memberIndent"))
                    }
                }
            }

            action.add("edits", actionEdits)
            actionsArray.add(action)
        } catch (_: Exception) {
            // Ignore failures
        }
    }

    /**
     * Adds "Move from companion object" code action for companion object members.
     * Moves a function or property from the companion object to the enclosing class body.
     * If the companion becomes empty after the move, it is deleted.
     */
    private fun addMoveFromCompanionAction(
        element: PsiElement,
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        actionsArray: JsonArray,
    ) {
        try {
            val declaration = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
                ?: PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
                ?: return

            // Must be inside a companion object
            val companionBody = declaration.parent as? KtClassBody ?: return
            val companion = companionBody.parent as? KtObjectDeclaration ?: return
            if (!companion.isCompanion()) return

            // Must have an enclosing class
            val containingClass = companion.parent?.parent as? KtClass ?: return

            val action = JsonObject()
            action.addProperty("title", "Move from companion object")
            action.addProperty("kind", "refactor")
            val actionEdits = JsonArray()

            val memberText = declaration.text

            // Calculate indent of the containing class members
            val classBody = containingClass.body ?: return
            val classIndent = indentAt(document, containingClass.textOffset)
            val memberIndent = classIndent + "    "

            // Check if companion will be empty after removal
            val companionDeclarations = companionBody.declarations
            val willBeEmpty = companionDeclarations.size == 1 && companionDeclarations.first() == declaration

            if (willBeEmpty) {
                // Delete the entire companion object (including preceding whitespace)
                val docText = document.charsSequence
                var companionDeleteStart = companion.textOffset
                var companionDeleteEnd = companion.textRange.endOffset
                val companionLine = document.getLineNumber(companionDeleteStart)
                if (companionLine > 0) {
                    companionDeleteStart = document.getLineStartOffset(companionLine)
                }
                if (companionDeleteEnd < docText.length && docText[companionDeleteEnd] == '\n') {
                    companionDeleteEnd++
                }
                val (compStartLine, compStartCol) = offsetToLineCol(document, companionDeleteStart)
                val (compEndLine, compEndCol) = offsetToLineCol(document, companionDeleteEnd)
                actionEdits.add(makeEdit(fileUri, compStartLine, compStartCol, compEndLine, compEndCol, ""))
            } else {
                // Just delete the member from the companion
                actionEdits.add(makeDeleteEdit(document, fileUri, declaration))
            }

            // Insert the member into the class body — before the companion object
            val companionOffset = companion.textOffset
            val companionLine = document.getLineNumber(companionOffset)
            val insertOffset = document.getLineStartOffset(companionLine)
            val (insertLine, insertCol) = offsetToLineCol(document, insertOffset)

            val reindentedText = memberText.lines().joinToString("\n") { line ->
                if (line.isBlank()) line else "$memberIndent${line.trimStart()}".trimEnd()
            }
            actionEdits.add(makeEdit(fileUri, insertLine, insertCol, insertLine, insertCol,
                "$reindentedText\n\n"))

            action.add("edits", actionEdits)
            actionsArray.add(action)
        } catch (_: Exception) {
            // Ignore failures
        }
    }

    /**
     * Adds "Move to top level" code action for class member functions.
     * If the function body references `this`, a `self: ClassName` parameter is added and
     * `this` references are rewritten to `self`. Same-file call sites are updated.
     * Not offered for `override` functions.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.addMoveToTopLevelAction(
        element: PsiElement,
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        actionsArray: JsonArray,
        ktFile: KtFile,
    ) {
        try {
            val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
                ?: return

            // Must be a class member
            val containingClassBody = function.parent as? KtClassBody ?: return
            val containingClass = containingClassBody.parent as? KtClass ?: return

            // Don't offer for override functions
            if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return

            val funcName = function.name ?: return
            val className = containingClass.name ?: return

            // Don't offer if class has type parameters (complex generic propagation)
            if (containingClass.typeParameters.isNotEmpty()) return

            val action = JsonObject()
            action.addProperty("title", "Move to top level")
            action.addProperty("kind", "refactor")
            val actionEdits = JsonArray()

            val usesThis = analyzeBodyReferences(function, containingClass).referencesThis

            // Build the top-level function signature
            val cleanModifiers = cleanModifiersForTopLevel(function)
            val typeParams = function.typeParameterList?.text?.let { "$it " } ?: ""
            val returnType = function.typeReference?.text?.let { ": $it" } ?: ""
            val paramList = function.valueParameterList?.text ?: "()"
            val receiverType = function.receiverTypeReference?.text

            val newParamList = if (usesThis) {
                val existingParams = paramList.removeSurrounding("(", ")")
                if (existingParams.isBlank()) "(self: $className)" else "(self: $className, $existingParams)"
            } else {
                paramList
            }

            val bodyExpr = function.bodyExpression ?: return
            var bodyText = bodyExpr.text
            if (usesThis) {
                // Replace KtThisExpression nodes with "self" using PSI offsets
                val thisExprs = PsiTreeUtil.findChildrenOfType(bodyExpr, KtThisExpression::class.java)
                    .sortedByDescending { it.textOffset }
                val bodyStart = bodyExpr.textOffset
                val sb = StringBuilder(bodyText)
                for (thisExpr in thisExprs) {
                    val relStart = thisExpr.textOffset - bodyStart
                    val relEnd = thisExpr.textRange.endOffset - bodyStart
                    sb.replace(relStart, relEnd, "self")
                }
                bodyText = sb.toString()
            }

            val receiverPrefix = if (receiverType != null) "$receiverType." else ""
            val newFuncText = "${cleanModifiers}fun $typeParams$receiverPrefix$funcName$newParamList$returnType $bodyText"

            actionEdits.add(makeDeleteEdit(document, fileUri, function))
            actionEdits.add(makeAppendToFileEdit(document, fileUri, newFuncText))

            // Update same-file call sites (scoped to containing class body)
            if (usesThis) {
                val callSites = mutableListOf<KtCallExpression>()
                containingClassBody.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                    override fun visitCallExpression(expression: KtCallExpression) {
                        super.visitCallExpression(expression)
                        val callee = expression.calleeExpression
                        if (callee is KtSimpleNameExpression && callee.getReferencedName() == funcName) {
                            // Don't update the call inside the function itself
                            if (!PsiTreeUtil.isAncestor(function, expression, true)) {
                                callSites.add(expression)
                            }
                        }
                    }
                })

                // Process call sites in reverse order to preserve offsets
                for (call in callSites.sortedByDescending { it.textOffset }) {
                    val parent = call.parent
                    val argList = call.valueArgumentList
                    val existingArgs = argList?.arguments?.joinToString(", ") { it.text } ?: ""

                    if (parent is KtDotQualifiedExpression) {
                        // obj.foo(a) → foo(obj, a)
                        val receiver = parent.receiverExpression.text
                        val newArgs = if (existingArgs.isBlank()) receiver else "$receiver, $existingArgs"
                        val newCallText = "$funcName($newArgs)"
                        val (csStartLine, csStartCol) = offsetToLineCol(document, parent.textOffset)
                        val (csEndLine, csEndCol) = offsetToLineCol(document, parent.textRange.endOffset)
                        actionEdits.add(makeEdit(fileUri, csStartLine, csStartCol, csEndLine, csEndCol, newCallText))
                    } else {
                        // foo(a) → foo(this, a) (implicit receiver)
                        val newArgs = if (existingArgs.isBlank()) "this" else "this, $existingArgs"
                        val newCallText = "$funcName($newArgs)"
                        val (csStartLine, csStartCol) = offsetToLineCol(document, call.textOffset)
                        val (csEndLine, csEndCol) = offsetToLineCol(document, call.textRange.endOffset)
                        actionEdits.add(makeEdit(fileUri, csStartLine, csStartCol, csEndLine, csEndCol, newCallText))
                    }
                }
            }

            action.add("edits", actionEdits)
            actionsArray.add(action)
        } catch (_: Exception) {
            // Ignore failures
        }
    }

    /**
     * Adds "Convert to extension function" code action for class member functions.
     * Converts `fun foo(x: Int)` in `class Bar` to `fun Bar.foo(x: Int)` at top level.
     * Not offered when body accesses private members, function is `override`,
     * or containing class has type parameters.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.addConvertToExtensionFunctionAction(
        element: PsiElement,
        document: com.intellij.openapi.editor.Document,
        fileUri: String,
        actionsArray: JsonArray,
    ) {
        try {
            val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
                ?: return

            // Must be a class member, not in a companion object
            val containingClassBody = function.parent as? KtClassBody ?: return
            val parentClassOrObject = containingClassBody.parent
            if (parentClassOrObject is KtObjectDeclaration && parentClassOrObject.isCompanion()) return
            val containingClass = parentClassOrObject as? KtClass ?: return

            // Don't offer for override functions
            if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return

            // Don't offer if class has type parameters
            if (containingClass.typeParameters.isNotEmpty()) return

            // Don't offer if body accesses private members
            if (analyzeBodyReferences(function, containingClass).accessesPrivateMembers) return

            val funcName = function.name ?: return
            val className = containingClass.name ?: return

            val action = JsonObject()
            action.addProperty("title", "Convert to extension function")
            action.addProperty("kind", "refactor")
            val actionEdits = JsonArray()

            // Build extension function signature
            val cleanModifiers = cleanModifiersForTopLevel(function)
            val typeParams = function.typeParameterList?.text?.let { "$it " } ?: ""
            val paramList = function.valueParameterList?.text ?: "()"
            val returnType = function.typeReference?.text?.let { ": $it" } ?: ""
            val bodyText = function.bodyExpression?.text ?: return

            val extFuncText = "${cleanModifiers}fun $typeParams$className.$funcName$paramList$returnType $bodyText"

            actionEdits.add(makeDeleteEdit(document, fileUri, function))
            actionEdits.add(makeAppendToFileEdit(document, fileUri, extFuncText))

            action.add("edits", actionEdits)
            actionsArray.add(action)
        } catch (_: Exception) {
            // Ignore failures
        }
    }

    /**
     * Provides workspace-wide symbol search.
     * Walks all KtFiles in the session and collects declarations matching the query.
     * Results are limited to 100 symbols.
     */
    fun workspaceSymbols(query: String): JsonObject {
        val result = JsonObject()
        val symbolsArray = JsonArray()
        val perfStart = System.currentTimeMillis()

        try {
            val indexed = symbolIndex.searchSymbols(query)
            for (decl in indexed) {
                val symbolObj = JsonObject()
                symbolObj.addProperty("name", decl.shortName)
                symbolObj.addProperty("kind", decl.kind)
                symbolObj.addProperty("uri", decl.uri)
                symbolObj.addProperty("line", decl.line)
                symbolObj.addProperty("column", decl.column)
                symbolsArray.add(symbolObj)
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: workspaceSymbols failed: ${e.javaClass.name}: ${e.message}")
        }

        System.err.println("[PERF] method=workspaceSymbols elapsed=${System.currentTimeMillis() - perfStart}ms")
        result.add("symbols", symbolsArray)
        return result
    }

    /**
     * Provides inlay hints for a file within the given line range.
     * Returns type hints for declarations without explicit type annotations
     * and parameter name hints for function call arguments.
     */
    fun inlayHints(uri: String, startLine: Int, endLine: Int): JsonObject {
        val result = JsonObject()
        val hintsArray = JsonArray()

        val currentSession = session ?: run {
            result.add("hints", hintsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("hints", hintsArray)
            return result
        }

        try {
            analyze(ktFile) {
                val document = ktFile.viewProvider.document
                if (document == null) {
                    result.add("hints", hintsArray)
                    return@analyze
                }

                // Calculate offset range for the requested lines
                val startOffset = if (startLine >= 1 && startLine <= document.lineCount) {
                    document.getLineStartOffset(startLine - 1)
                } else {
                    0
                }
                val endOffset = if (endLine >= 1 && endLine <= document.lineCount) {
                    document.getLineEndOffset(endLine - 1)
                } else {
                    document.textLength
                }

                // Collect type hints for val/var declarations without explicit types
                val properties = PsiTreeUtil.collectElementsOfType(
                    ktFile, KtProperty::class.java
                )
                for (property in properties) {
                    val propOffset = property.textOffset
                    if (propOffset < startOffset || propOffset > endOffset) continue
                    // Only hint if there is no explicit type annotation
                    if (property.typeReference != null) continue

                    val nameIdentifier = property.nameIdentifier ?: continue

                    try {
                        val symbol = property.symbol
                        val returnType = symbol.returnType
                        val rendered = returnType.render(
                            KaTypeRendererForSource.WITH_SHORT_NAMES,
                            Variance.INVARIANT
                        )

                        val hintOffset = nameIdentifier.textRange.endOffset
                        val hintLine = document.getLineNumber(hintOffset) + 1
                        val hintLineStart = document.getLineStartOffset(document.getLineNumber(hintOffset))
                        val hintCol = hintOffset - hintLineStart

                        val hint = JsonObject()
                        hint.addProperty("line", hintLine)
                        hint.addProperty("character", hintCol)
                        hint.addProperty("label", ": $rendered")
                        hint.addProperty("kind", "type")
                        hint.addProperty("paddingLeft", false)
                        hint.addProperty("paddingRight", true)
                        hintsArray.add(hint)
                    } catch (_: Exception) {
                        // Skip if type cannot be resolved
                    }
                }

                // Collect type hints for lambda parameters without explicit types
                val lambdaParams = PsiTreeUtil.collectElementsOfType(
                    ktFile, KtLambdaExpression::class.java
                )
                for (lambda in lambdaParams) {
                    val lambdaOffset = lambda.textOffset
                    if (lambdaOffset < startOffset || lambdaOffset > endOffset) continue

                    for (param in lambda.valueParameters) {
                        if (param.typeReference != null) continue
                        val nameIdentifier = param.nameIdentifier ?: continue

                        try {
                            val symbol = param.symbol
                            val returnType = symbol.returnType
                            val rendered = returnType.render(
                                KaTypeRendererForSource.WITH_SHORT_NAMES,
                                Variance.INVARIANT
                            )

                            val hintOffset = nameIdentifier.textRange.endOffset
                            val hintLine = document.getLineNumber(hintOffset) + 1
                            val hintLineStart = document.getLineStartOffset(document.getLineNumber(hintOffset))
                            val hintCol = hintOffset - hintLineStart

                            val hint = JsonObject()
                            hint.addProperty("line", hintLine)
                            hint.addProperty("character", hintCol)
                            hint.addProperty("label", ": $rendered")
                            hint.addProperty("kind", "type")
                            hint.addProperty("paddingLeft", false)
                            hint.addProperty("paddingRight", true)
                            hintsArray.add(hint)
                        } catch (_: Exception) {
                            // Skip if type cannot be resolved
                        }
                    }
                }

                // Collect parameter name hints for function call arguments
                val callExpressions = PsiTreeUtil.collectElementsOfType(
                    ktFile, KtCallExpression::class.java
                )
                for (callExpr in callExpressions) {
                    val callOffset = callExpr.textOffset
                    if (callOffset < startOffset || callOffset > endOffset) continue

                    val argList = callExpr.valueArgumentList ?: continue
                    val args = argList.arguments
                    if (args.isEmpty()) continue

                    // Skip if all args are already named
                    if (args.all { it.isNamed() }) continue

                    try {
                        val callInfo = callExpr.resolveToCall()
                        if (callInfo is KaSuccessCallInfo) {
                            val call = callInfo.call
                            if (call is KaFunctionCall<*>) {
                                val signature = call.partiallyAppliedSymbol.signature
                                val valueParams = signature.valueParameters

                                for ((index, arg) in args.withIndex()) {
                                    // Skip named arguments (they already show the name)
                                    if (arg.isNamed()) continue
                                    if (index >= valueParams.size) break

                                    val paramName = valueParams[index].name.asString()
                                    val argExpr = arg.getArgumentExpression() ?: continue

                                    val argOffset = argExpr.textOffset
                                    val argLine = document.getLineNumber(argOffset) + 1
                                    val argLineStart = document.getLineStartOffset(document.getLineNumber(argOffset))
                                    val argCol = argOffset - argLineStart

                                    val hint = JsonObject()
                                    hint.addProperty("line", argLine)
                                    hint.addProperty("character", argCol)
                                    hint.addProperty("label", "$paramName:")
                                    hint.addProperty("kind", "parameter")
                                    hint.addProperty("paddingLeft", false)
                                    hint.addProperty("paddingRight", true)
                                    hintsArray.add(hint)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Skip calls that fail to resolve
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: inlayHints failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("hints", hintsArray)
        return result
    }

    /**
     * Provides code lenses for a file.
     * Returns reference counts for classes/interfaces/objects,
     * "Run test" for @Test-annotated functions, and "Run" for main functions.
     */
    fun codeLens(uri: String): JsonObject {
        val result = JsonObject()
        val lensesArray = JsonArray()

        val currentSession = session ?: run {
            result.add("lenses", lensesArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("lenses", lensesArray)
            return result
        }

        try {
            analyze(ktFile) {
                val document = ktFile.viewProvider.document
                if (document == null) {
                    result.add("lenses", lensesArray)
                    return@analyze
                }

                val fileUri = uri

                // Collect classes, interfaces, and objects for "N references" lenses
                val classOrObjects = PsiTreeUtil.collectElementsOfType(
                    ktFile, KtClassOrObject::class.java
                )
                for (classOrObj in classOrObjects) {
                    val name = classOrObj.name ?: continue
                    val nameIdentifier = classOrObj.nameIdentifier ?: continue

                    // Count references to this class within the file
                    val refCount = countReferencesInFile(ktFile, classOrObj)

                    val declOffset = nameIdentifier.textOffset
                    val declLine = document.getLineNumber(declOffset) + 1
                    val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
                    val declCol = declOffset - declLineStart

                    val lens = JsonObject()
                    lens.addProperty("line", declLine)
                    lens.addProperty("character", declCol)
                    lens.addProperty("command", "$refCount references")
                    val data = JsonObject()
                    data.addProperty("type", "references")
                    data.addProperty("uri", fileUri)
                    data.addProperty("name", name)
                    lens.add("data", data)
                    lensesArray.add(lens)
                }

                // Collect functions for "Run test" and "Run" lenses
                val functions = PsiTreeUtil.collectElementsOfType(
                    ktFile, KtNamedFunction::class.java
                )
                for (function in functions) {
                    val name = function.name ?: continue
                    val nameIdentifier = function.nameIdentifier ?: continue

                    val declOffset = nameIdentifier.textOffset
                    val declLine = document.getLineNumber(declOffset) + 1
                    val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
                    val declCol = declOffset - declLineStart

                    // Check for @Test annotation
                    val hasTestAnnotation = function.annotationEntries.any { annotation ->
                        val annotationName = annotation.shortName?.asString()
                        annotationName == "Test"
                    }

                    if (hasTestAnnotation) {
                        val lens = JsonObject()
                        lens.addProperty("line", declLine)
                        lens.addProperty("character", declCol)
                        lens.addProperty("command", "Run test")
                        val data = JsonObject()
                        data.addProperty("type", "test")
                        data.addProperty("uri", fileUri)
                        data.addProperty("name", name)
                        lens.add("data", data)
                        lensesArray.add(lens)
                    }

                    // Check for main function
                    if (name == "main" && function.isTopLevel) {
                        val lens = JsonObject()
                        lens.addProperty("line", declLine)
                        lens.addProperty("character", declCol)
                        lens.addProperty("command", "Run")
                        val data = JsonObject()
                        data.addProperty("type", "run")
                        data.addProperty("uri", fileUri)
                        data.addProperty("name", name)
                        lens.add("data", data)
                        lensesArray.add(lens)
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: codeLens failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("lenses", lensesArray)
        return result
    }

    /**
     * Provides semantic tokens for a file using delta encoding.
     * Walks the PSI tree to classify identifiers, keywords, comments, strings,
     * and number literals into semantic token types.
     * Returns a JSON object with `data` (int array) and `legend` (token type/modifier arrays).
     */
    fun semanticTokens(uri: String): JsonObject {
        val result = JsonObject()
        val dataArray = JsonArray()

        // Standard LSP semantic token types legend
        val tokenTypes = listOf(
            "namespace",     // 0
            "type",          // 1
            "class",         // 2
            "enum",          // 3
            "interface",     // 4
            "struct",        // 5
            "typeParameter", // 6
            "parameter",     // 7
            "variable",      // 8
            "property",      // 9
            "enumMember",    // 10
            "event",         // 11
            "function",      // 12
            "method",        // 13
            "macro",         // 14
            "keyword",       // 15
            "modifier",      // 16
            "comment",       // 17
            "string",        // 18
            "number",        // 19
            "regexp",        // 20
            "operator",      // 21
            "decorator",     // 22
        )
        val tokenModifiers = listOf(
            "declaration",
            "definition",
            "readonly",
            "static",
            "deprecated",
            "abstract",
            "async",
            "modification",
            "documentation",
            "defaultLibrary",
        )

        val currentSession = session ?: run {
            result.add("data", dataArray)
            result.add("legend", buildLegend(tokenTypes, tokenModifiers))
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("data", dataArray)
            result.add("legend", buildLegend(tokenTypes, tokenModifiers))
            return result
        }

        try {
            val document = ktFile.viewProvider.document
            if (document == null) {
                result.add("data", dataArray)
                result.add("legend", buildLegend(tokenTypes, tokenModifiers))
                return result
            }

            // Collect all tokens as (line, col, length, tokenTypeIndex, modifiers)
            data class RawToken(
                val line: Int,      // 0-based
                val col: Int,       // 0-based
                val length: Int,
                val tokenType: Int,
                val modifiers: Int,
            )

            val tokens = mutableListOf<RawToken>()

            fun addToken(offset: Int, length: Int, tokenType: Int, modifiers: Int = 0) {
                if (length <= 0) return
                val clampedOffset = offset.coerceIn(0, document.textLength)
                val line = document.getLineNumber(clampedOffset)
                val lineStart = document.getLineStartOffset(line)
                val col = clampedOffset - lineStart
                tokens.add(RawToken(line, col, length, tokenType, modifiers))
            }

            // Walk the entire PSI tree
            fun walkPsi(element: PsiElement) {
                when (element) {
                    // Comments
                    is PsiComment -> {
                        addToken(element.textOffset, element.textLength, 17) // comment
                    }
                    // String templates
                    is KtStringTemplateExpression -> {
                        addToken(element.textOffset, element.textLength, 18) // string
                    }
                    // Number literals
                    is KtConstantExpression -> {
                        val elementType = element.node.elementType
                        if (elementType == KtTokens.INTEGER_LITERAL ||
                            elementType == KtTokens.FLOAT_LITERAL
                        ) {
                            addToken(element.textOffset, element.textLength, 19) // number
                        }
                    }
                    // Annotation entries
                    is KtAnnotationEntry -> {
                        // Mark the entire annotation as decorator
                        addToken(element.textOffset, element.textLength, 22) // decorator
                    }
                    else -> {
                        // Check for keyword tokens at the leaf level
                        if (element.children.isEmpty() && element !is PsiWhiteSpace) {
                            val elementType = element.node.elementType
                            if (KEYWORD_TOKENS.contains(elementType)) {
                                addToken(element.textOffset, element.textLength, 15) // keyword
                            } else if (elementType == KtTokens.IDENTIFIER) {
                                // Classify the identifier by its parent PSI node
                                val parent = element.parent
                                val tokenType = classifyIdentifierByParent(parent)
                                val modifiers = classifyIdentifierModifiers(parent)
                                if (tokenType >= 0) {
                                    addToken(element.textOffset, element.textLength, tokenType, modifiers)
                                }
                            }
                        }

                        // Recurse into children (but skip string templates and annotations
                        // since we already handled them as a whole)
                        if (element !is KtStringTemplateExpression && element !is KtAnnotationEntry) {
                            for (child in element.children) {
                                walkPsi(child)
                            }
                        }
                    }
                }
            }

            walkPsi(ktFile)

            // Sort tokens by (line, col) for delta encoding
            tokens.sortWith(compareBy({ it.line }, { it.col }))

            // Delta-encode
            var prevLine = 0
            var prevCol = 0
            for (token in tokens) {
                val deltaLine = token.line - prevLine
                val deltaCol = if (deltaLine == 0) token.col - prevCol else token.col
                dataArray.add(deltaLine)
                dataArray.add(deltaCol)
                dataArray.add(token.length)
                dataArray.add(token.tokenType)
                dataArray.add(token.modifiers)
                prevLine = token.line
                prevCol = token.col
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: semanticTokens failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("data", dataArray)
        result.add("legend", buildLegend(tokenTypes, tokenModifiers))
        return result
    }

    /**
     * Prepares call hierarchy items at the given cursor position.
     * Finds the function/method declaration at the cursor and returns its info.
     */
    fun callHierarchyPrepare(uri: String, line: Int, character: Int): JsonObject {
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

        try {
            val offset = lineColToOffset(ktFile, line, character) ?: run {
                result.add("items", itemsArray)
                return result
            }

            val element = ktFile.findElementAt(offset)
            if (element != null) {
                // Find the enclosing function declaration
                val function = PsiTreeUtil.getParentOfType(
                    element, KtNamedFunction::class.java, false
                )
                if (function != null) {
                    val document = ktFile.viewProvider.document
                    if (document != null) {
                        val item = buildCallHierarchyItem(function, ktFile, document)
                        if (item != null) {
                            itemsArray.add(item)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: callHierarchyPrepare failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("items", itemsArray)
        return result
    }

    /**
     * Finds incoming calls to the function at the given position.
     * Searches all KtFiles in the session for call expressions that reference the target function.
     */
    fun callHierarchyIncoming(uri: String, line: Int, character: Int, name: String): JsonObject {
        val result = JsonObject()
        val callsArray = JsonArray()

        val currentSession = session ?: run {
            result.add("calls", callsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("calls", callsArray)
            return result
        }

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: run {
                    result.add("calls", callsArray)
                    return@analyze
                }

                val element = ktFile.findElementAt(offset) ?: run {
                    result.add("calls", callsArray)
                    return@analyze
                }

                // Find the target function declaration
                val targetFunction = PsiTreeUtil.getParentOfType(
                    element, KtNamedFunction::class.java, false
                ) ?: run {
                    result.add("calls", callsArray)
                    return@analyze
                }

                // Search all KtFiles for call sites referencing the target function
                val allKtFiles = currentSession.modulesWithFiles.entries
                    .flatMap { (_, files) -> files }
                    .filterIsInstance<KtFile>()

                for (searchFile in allKtFiles) {
                    val searchDoc = searchFile.viewProvider.document ?: continue
                    val fileUri = "file://${searchFile.virtualFile.path}"

                    // Find all call expressions in this file
                    val callExprs = PsiTreeUtil.collectElementsOfType(
                        searchFile, KtCallExpression::class.java
                    )

                    for (callExpr in callExprs) {
                        try {
                            // Check if the callee name matches
                            val calleeExpr = callExpr.calleeExpression
                            if (calleeExpr == null) continue
                            val calleeName = calleeExpr.text
                            if (calleeName != name) continue

                            // Try to resolve the call to see if it points to our target
                            val calleeRef = calleeExpr as? KtReferenceExpression ?: continue
                            var resolvedToTarget = false
                            for (ref in calleeRef.references) {
                                val resolved = ref.resolve()
                                if (resolved != null && resolved == targetFunction) {
                                    resolvedToTarget = true
                                    break
                                }
                            }

                            if (!resolvedToTarget) continue

                            // Find the enclosing function that contains this call
                            val enclosingFunction = PsiTreeUtil.getParentOfType(
                                callExpr, KtNamedFunction::class.java, false
                            )

                            val callObj = JsonObject()

                            // Build the "from" item
                            val fromObj = JsonObject()
                            if (enclosingFunction != null) {
                                val encName = enclosingFunction.name ?: "<anonymous>"
                                val encNameId = enclosingFunction.nameIdentifier
                                val encOffset = (encNameId ?: enclosingFunction).textOffset
                                val encLine = searchDoc.getLineNumber(encOffset) + 1
                                val encLineStart = searchDoc.getLineStartOffset(searchDoc.getLineNumber(encOffset))
                                val encCol = encOffset - encLineStart

                                fromObj.addProperty("name", encName)
                                fromObj.addProperty("kind", "function")
                                fromObj.addProperty("uri", fileUri)
                                fromObj.addProperty("line", encLine)
                                fromObj.addProperty("column", encCol)
                            } else {
                                // Top-level call (not inside a function)
                                fromObj.addProperty("name", searchFile.name)
                                fromObj.addProperty("kind", "file")
                                fromObj.addProperty("uri", fileUri)
                                fromObj.addProperty("line", 1)
                                fromObj.addProperty("column", 0)
                            }
                            callObj.add("from", fromObj)

                            // Build the fromRanges array with the call site position
                            val fromRanges = JsonArray()
                            val callOffset = calleeExpr.textOffset
                            val callLine = searchDoc.getLineNumber(callOffset) + 1
                            val callLineStart = searchDoc.getLineStartOffset(searchDoc.getLineNumber(callOffset))
                            val callCol = callOffset - callLineStart

                            val rangeObj = JsonObject()
                            rangeObj.addProperty("line", callLine)
                            rangeObj.addProperty("character", callCol)
                            fromRanges.add(rangeObj)

                            callObj.add("fromRanges", fromRanges)
                            callsArray.add(callObj)
                        } catch (_: Exception) {
                            // Skip calls that fail to resolve
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: callHierarchyIncoming failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("calls", callsArray)
        return result
    }

    /**
     * Prepares type hierarchy items at the given cursor position.
     * Finds the class/interface declaration at the cursor and returns its info.
     */
    fun typeHierarchyPrepare(uri: String, line: Int, character: Int): JsonObject {
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

        try {
            val offset = lineColToOffset(ktFile, line, character) ?: run {
                result.add("items", itemsArray)
                return result
            }

            val element = ktFile.findElementAt(offset)
            if (element != null) {
                val classOrObject = PsiTreeUtil.getParentOfType(
                    element, KtClassOrObject::class.java, false
                )
                if (classOrObject != null) {
                    val document = ktFile.viewProvider.document
                    if (document != null) {
                        val item = buildTypeHierarchyItem(classOrObject, ktFile, document)
                        if (item != null) {
                            itemsArray.add(item)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: typeHierarchyPrepare failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("items", itemsArray)
        return result
    }

    /**
     * Finds supertypes for the class/interface at the given position.
     * Uses the Analysis API to enumerate the superTypes of the class symbol.
     * For supertypes from dependencies (no source file), the name is included
     * but the uri is empty.
     */
    fun typeHierarchySupertypes(uri: String, line: Int, character: Int, name: String): JsonObject {
        val result = JsonObject()
        val supertypesArray = JsonArray()

        val currentSession = session ?: run {
            result.add("supertypes", supertypesArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
            result.add("supertypes", supertypesArray)
            return result
        }

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: run {
                    result.add("supertypes", supertypesArray)
                    return@analyze
                }

                val element = ktFile.findElementAt(offset) ?: run {
                    result.add("supertypes", supertypesArray)
                    return@analyze
                }

                val classOrObject = PsiTreeUtil.getParentOfType(
                    element, KtClassOrObject::class.java, false
                ) ?: run {
                    result.add("supertypes", supertypesArray)
                    return@analyze
                }

                val classSymbol = classOrObject.symbol as? KaNamedClassSymbol ?: run {
                    result.add("supertypes", supertypesArray)
                    return@analyze
                }

                for (superType in classSymbol.superTypes) {
                    if (superType is KaClassType) {
                        val superSymbol = superType.symbol
                        val superName = if (superSymbol is KaNamedSymbol) {
                            superSymbol.name.asString()
                        } else {
                            superType.render(
                                KaTypeRendererForSource.WITH_SHORT_NAMES,
                                Variance.INVARIANT
                            )
                        }

                        // Skip kotlin.Any unless it is the only supertype
                        if (superName == "Any" && classSymbol.superTypes.size > 1) continue

                        val superObj = JsonObject()
                        superObj.addProperty("name", superName)

                        val kind = when {
                            superSymbol is KaNamedClassSymbol && superSymbol.classKind == KaClassKind.INTERFACE -> "interface"
                            superSymbol is KaNamedClassSymbol && superSymbol.classKind == KaClassKind.ENUM_CLASS -> "enum"
                            superSymbol is KaNamedClassSymbol && superSymbol.classKind == KaClassKind.OBJECT -> "object"
                            else -> "class"
                        }
                        superObj.addProperty("kind", kind)

                        // Try to get source location for the supertype
                        val superPsi = superSymbol.psi
                        if (superPsi != null) {
                            val superFile = superPsi.containingFile
                            val superDoc = superFile?.viewProvider?.document
                            if (superFile != null && superDoc != null) {
                                val superOffset = superPsi.textOffset
                                val superLine = superDoc.getLineNumber(superOffset) + 1
                                val superLineStart = superDoc.getLineStartOffset(superDoc.getLineNumber(superOffset))
                                val superCol = superOffset - superLineStart

                                superObj.addProperty("uri", "file://${superFile.virtualFile.path}")
                                superObj.addProperty("line", superLine)
                                superObj.addProperty("column", superCol)
                            } else {
                                superObj.addProperty("uri", "")
                                superObj.addProperty("line", 0)
                                superObj.addProperty("column", 0)
                            }
                        } else {
                            // Supertype from dependency, no source available
                            superObj.addProperty("uri", "")
                            superObj.addProperty("line", 0)
                            superObj.addProperty("column", 0)
                        }

                        supertypesArray.add(superObj)
                    }
                }
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: typeHierarchySupertypes failed: ${e.javaClass.name}: ${e.message}")
        }

        result.add("supertypes", supertypesArray)
        return result
    }

    /**
     * Shuts down the Analysis API session.
     */
    fun shutdown() {
        pebbleSpringIndexDirty = true
        session = null
        Disposer.dispose(disposable)
        // Clean up virtual file temp directory
        virtualFileTempDir?.let { dir ->
            try {
                dir.toFile().deleteRecursively()
                System.err.println("CompilerBridge: deleted virtual file temp dir: $dir")
            } catch (e: Exception) {
                System.err.println("CompilerBridge: failed to delete temp dir: ${e.message}")
            }
        }
        virtualFileTempDir = null
        virtualFilesOnDisk.clear()
        virtualFileDiskPaths.clear()
        decompiledFileCache.clear()
        System.err.println("CompilerBridge: session disposed")
    }

    // --- Private helpers: hover ---

    /**
     * Builds hover text for a named declaration, including its rendered signature and KDoc.
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildDeclarationHover(
        declaration: KtNamedDeclaration,
    ): String? {
        val symbol = try {
            declaration.symbol
        } catch (_: Exception) {
            null
        }

        val rendered = if (symbol != null) {
            try {
                symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
            } catch (_: Exception) {
                val text = declaration.text?.take(300) ?: return null
                text.lines().first()
            }
        } else {
            // Symbol resolution failed (e.g., virtual file) — use PSI text
            val text = declaration.text?.take(300) ?: return null
            text.lines().first()
        }

        val kdocText = extractKDocText(declaration)

        return buildString {
            if (symbol != null) {
                // Show containing package/class for context
                val containerInfo = buildContainerInfo(symbol)
                if (containerInfo != null) {
                    append(containerInfo)
                    append("\n\n")
                }

                // Build code block with optional annotations prefix
                val annotationsBlock = buildAnnotationsBlock(symbol)
                val supertypesLine = buildSupertypesLine(symbol)

                // Strip annotations from the rendered output to avoid duplication
                // when buildAnnotationsBlock provides cleaner annotation rendering
                val cleanRendered = if (annotationsBlock != null) {
                    rendered.lines()
                        .dropWhile { it.trimStart().startsWith("@") }
                        .joinToString("\n")
                } else {
                    rendered
                }

                append("```kotlin\n")
                if (annotationsBlock != null) {
                    append(annotationsBlock)
                    append("\n")
                }
                append(cleanRendered)
                // Only add supertypes line if the rendered declaration doesn't already contain them
                val declarationLine = cleanRendered.lines().lastOrNull { it.isNotBlank() } ?: ""
                if (supertypesLine != null && !declarationLine.contains(":")) {
                    append("\n")
                    append(supertypesLine)
                }
                append("\n```")

                // Documentation before source origin (matches IntelliJ layout)
                if (kdocText != null) {
                    append("\n\n---\n\n")
                    append(enrichKDocText(kdocText))
                }

                val sourceOrigin = buildSourceOrigin(symbol)
                if (sourceOrigin != null) {
                    append("\n\n---\n\n")
                    append(sourceOrigin)
                }
            } else {
                append("```kotlin\n")
                append(rendered)
                append("\n```")

                if (kdocText != null) {
                    append("\n\n---\n\n")
                    append(enrichKDocText(kdocText))
                }
            }
        }
    }

    /**
     * Builds hover text for a reference expression by resolving it to the target declaration.
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildReferenceHover(
        refExpr: KtReferenceExpression,
    ): String? {
        // Try to resolve via references
        for (ref in refExpr.references) {
            if (ref is KtReference) {
                var symbol = ref.resolveToSymbol()
                // For constructor calls, show the containing class instead of the constructor
                if (symbol is KaConstructorSymbol) {
                    val containingClass = symbol.containingDeclaration
                    if (containingClass is KaDeclarationSymbol) {
                        symbol = containingClass
                    }
                }
                if (symbol is KaDeclarationSymbol) {
                    val rendered = try {
                        symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
                    } catch (_: Exception) {
                        symbol.toString()
                    }

                    // Try to get documentation from the PSI
                    val kdocText = extractSymbolDocumentation(symbol)

                    return buildString {
                        // Show containing package/class for context
                        val containerInfo = buildContainerInfo(symbol)
                        if (containerInfo != null) {
                            append(containerInfo)
                            append("\n\n")
                        }

                        val annotationsBlock = buildAnnotationsBlock(symbol)
                        val supertypesLine = buildSupertypesLine(symbol)

                        // Strip annotations from the rendered output to avoid duplication
                        val cleanRendered = if (annotationsBlock != null) {
                            rendered.lines()
                                .dropWhile { it.trimStart().startsWith("@") }
                                .joinToString("\n")
                        } else {
                            rendered
                        }

                        append("```kotlin\n")
                        if (annotationsBlock != null) {
                            append(annotationsBlock)
                            append("\n")
                        }
                        append(cleanRendered)
                        // Only add supertypes line if the rendered declaration doesn't already contain them
                        val refDeclLine = cleanRendered.lines().lastOrNull { it.isNotBlank() } ?: ""
                        if (supertypesLine != null && !refDeclLine.contains(":")) {
                            append("\n")
                            append(supertypesLine)
                        }
                        append("\n```")
                        if (kdocText != null) {
                            append("\n\n---\n\n")
                            append(enrichKDocText(kdocText))
                        }

                        val sourceOrigin = buildSourceOrigin(symbol)
                        if (sourceOrigin != null) {
                            append("\n\n---\n\n")
                            append(sourceOrigin)
                        }
                    }
                }
            }
        }

        // Fallback: try expression type
        val type = refExpr.expressionType
        if (type != null) {
            val rendered = type.render(
                KaTypeRendererForSource.WITH_SHORT_NAMES,
                Variance.INVARIANT
            )
            return "```kotlin\n$rendered\n```"
        }

        return null
    }

    /**
     * Builds hover text for an annotation entry (e.g., @RestController, @Deprecated).
     * Resolves to the annotation CLASS and shows its meta-annotations, documentation,
     * supertypes, and source origin — matching IntelliJ's annotation hover behavior.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildAnnotationEntryHover(
        annotationEntry: org.jetbrains.kotlin.psi.KtAnnotationEntry,
    ): String? {
        try {
            // Resolve the annotation type reference to the annotation class
            val typeRef = annotationEntry.typeReference ?: return null
            val type = typeRef.type
            val classSymbol = (type as? org.jetbrains.kotlin.analysis.api.types.KaClassType)
                ?.symbol as? KaNamedClassSymbol ?: return null

            // Get the annotation class PSI for KDoc extraction
            val psi = classSymbol.psi as? KtDeclaration

            // Render the annotation class declaration
            val rendered = classSymbol.render(
                KaDeclarationRendererForSource.WITH_SHORT_NAMES
            )

            // Build container info (package)
            val containerInfo = buildContainerInfo(classSymbol)

            // Build meta-annotations (e.g., @Target, @Retention for annotation classes)
            val annotationsBlock = buildAnnotationsBlock(classSymbol)

            // Build supertypes
            val supertypesLine = buildSupertypesLine(classSymbol)

            // Strip annotations from the rendered output to avoid duplication
            val cleanRendered = if (annotationsBlock != null) {
                rendered.lines()
                    .dropWhile { it.trimStart().startsWith("@") }
                    .joinToString("\n")
            } else {
                rendered
            }

            // Extract documentation (KDoc, decompiled stubs, Javadoc)
            val kdocText = if (psi != null) extractKDocText(psi) else null
            val symbolDoc = extractSymbolDocumentation(classSymbol)
            val documentation = kdocText ?: symbolDoc

            // Build source origin
            val sourceOrigin = buildSourceOrigin(classSymbol)

            return buildString {
                if (containerInfo != null) {
                    append(containerInfo)
                    append("\n\n")
                }

                append("```kotlin\n")
                if (annotationsBlock != null) {
                    append(annotationsBlock)
                    append("\n")
                }
                append(cleanRendered)
                // Only add supertypes line if the rendered declaration doesn't already contain them
                val annotDeclLine = cleanRendered.lines().lastOrNull { it.isNotBlank() } ?: ""
                if (supertypesLine != null && !annotDeclLine.contains(":")) {
                    append("\n")
                    append(supertypesLine)
                }
                append("\n```")

                if (documentation != null) {
                    append("\n\n---\n\n")
                    append(enrichKDocText(documentation))
                }

                if (sourceOrigin != null) {
                    append("\n\n---\n\n")
                    append(sourceOrigin)
                }
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: buildAnnotationEntryHover failed: ${e.javaClass.name}: ${e.message}")
            return null
        }
    }

    /**
     * Builds a container info line showing the package and/or containing class.
     * Returns something like "(org.example)" or "(MyClass in org.example)".
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildContainerInfo(
        symbol: KaDeclarationSymbol,
    ): String? {
        try {
            // Get the containing symbol for context
            val containingSymbol = try {
                symbol.containingDeclaration
            } catch (_: Exception) {
                null
            }

            // Try to get the fully qualified name from the PSI
            val psi = symbol.psi
            val fqName = when (psi) {
                is KtNamedDeclaration -> psi.fqName?.asString()
                else -> null
            }

            if (fqName != null) {
                val name = when (symbol) {
                    is KaNamedSymbol -> symbol.name.asString()
                    else -> null
                }
                val packageName = if (name != null && fqName.endsWith(".$name")) {
                    fqName.removeSuffix(".$name")
                } else {
                    fqName.substringBeforeLast('.', "")
                }
                if (packageName.isNotEmpty()) {
                    // Show containing class if applicable
                    if (containingSymbol is KaNamedSymbol) {
                        return "*(${containingSymbol.name.asString()} in $packageName)*"
                    }
                    return "*($packageName)*"
                }
            }

            // Fallback: try to get package from containing class symbol chain
            if (containingSymbol is KaNamedSymbol) {
                val containerPsi = containingSymbol.psi
                val containerFqName = (containerPsi as? KtNamedDeclaration)?.fqName?.asString()
                if (containerFqName != null) {
                    return "*(${containingSymbol.name.asString()} in ${containerFqName.substringBeforeLast('.', "")})*"
                }
                // Fallback for library symbols: use classId to get package
                if (containingSymbol is KaClassLikeSymbol) {
                    val classId = (containingSymbol as KaClassLikeSymbol).classId
                    if (classId != null) {
                        val pkg = classId.packageFqName.asString()
                        if (pkg.isNotEmpty()) {
                            return "*(${containingSymbol.name.asString()} in $pkg)*"
                        }
                    }
                }
                return "*(in ${containingSymbol.name.asString()})*"
            }

            // Fallback for library symbols (no PSI): use classId or callableId
            if (symbol is KaClassLikeSymbol) {
                val classId = symbol.classId
                if (classId != null) {
                    val pkg = classId.packageFqName.asString()
                    if (pkg.isNotEmpty()) return "*($pkg)*"
                }
            }
            if (symbol is KaCallableSymbol) {
                val callableId = symbol.callableId
                if (callableId != null) {
                    val pkg = callableId.packageName.asString()
                    if (pkg.isNotEmpty()) return "*($pkg)*"
                }
            }
        } catch (_: Exception) {
            // Ignore errors in container info — it's supplementary
        }
        return null
    }

    /**
     * Builds meta-annotation lines for annotation classes and annotated declarations.
     * Shows annotations like @Target, @Retention, @Component etc. inside the code block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildAnnotationsBlock(
        symbol: KaDeclarationSymbol,
    ): String? {
        try {
            val annotations = symbol.annotations
            if (annotations.isEmpty()) return null

            // Blocklist of noisy/internal annotations to suppress
            val suppressedPackages = setOf(
                "kotlin.jvm.internal",
            )
            val suppressedNames = setOf(
                "SinceKotlin", "WasExperimental",
            )

            val relevantAnnotations = annotations.mapNotNull { annotation ->
                val classId = annotation.classId ?: return@mapNotNull null
                val shortName = classId.shortClassName.asString()
                val packageName = classId.packageFqName.asString()

                // Skip internal/noise annotations
                if (packageName in suppressedPackages) return@mapNotNull null
                if (shortName in suppressedNames) return@mapNotNull null

                val args = annotation.arguments.mapNotNull { arg ->
                    val name = arg.name?.asString()
                    val value = try {
                        renderAnnotationArgValue(arg.expression)
                    } catch (_: Exception) { null }
                    if (name != null && value != null) "$name = $value"
                    else value ?: name
                }
                if (args.isNotEmpty()) "@$shortName(${args.joinToString(", ")})"
                else "@$shortName"
            }

            if (relevantAnnotations.isEmpty()) return null
            return relevantAnnotations.joinToString("\n")
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Renders an annotation argument value to a human-readable string.
     * Handles enum entries, arrays, constants, class literals, and nested annotations.
     */
    private fun renderAnnotationArgValue(value: org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue): String {
        return when (value) {
            is org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue.EnumEntryValue -> {
                val callableId = value.callableId
                "${callableId?.classId?.shortClassName?.asString() ?: ""}.${callableId?.callableName?.asString() ?: ""}"
                    .removePrefix(".")
            }
            is org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue.ArrayValue -> {
                value.values.joinToString(", ", "[", "]") { renderAnnotationArgValue(it) }
            }
            is org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue.ClassLiteralValue -> {
                "${value.classId?.shortClassName?.asString() ?: "?"}::class"
            }
            is org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue.ConstantValue -> {
                val raw = value.value.toString()
                // Clean up KaConstantValue wrappers
                raw.removePrefix("KaConstantValue.")
                    .replace(Regex("^\\w+Value\\((.+)\\)$")) { it.groupValues[1] }
            }
            is org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue.NestedAnnotationValue -> {
                "@${value.annotation.classId?.shortClassName?.asString() ?: "?"}"
            }
            else -> {
                // Fallback: clean up the toString
                value.toString()
                    .removePrefix("KaAnnotationValue.")
                    .replace(Regex("^\\w+\\((.+)\\)$")) { it.groupValues[1] }
            }
        }
    }

    /**
     * Builds a supertypes line for classes/interfaces (e.g., ": Interface1, Interface2").
     * Skips implicit Any supertype.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildSupertypesLine(
        symbol: KaDeclarationSymbol,
    ): String? {
        try {
            if (symbol !is KaNamedClassSymbol) return null
            val supertypes = symbol.superTypes
                .mapNotNull { type ->
                    val rendered = type.render(
                        KaTypeRendererForSource.WITH_SHORT_NAMES,
                        Variance.INVARIANT
                    )
                    // Skip implicit Any and Annotation
                    if (rendered == "Any" || rendered == "Annotation") null else rendered
                }
            if (supertypes.isEmpty()) return null
            return ": ${supertypes.joinToString(", ")}"
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Builds source origin info for library symbols (JAR path or coordinates).
     */
    private fun buildSourceOrigin(symbol: KaDeclarationSymbol): String? {
        try {
            val psi = symbol.psi
            val vf = psi?.containingFile?.virtualFile
            val path = vf?.path

            // For library symbols without PSI, use classId to identify the origin
            if (path == null) {
                if (symbol.origin.name.contains("LIBRARY", ignoreCase = true)) {
                    // Try to identify the library from classId
                    val classId = (symbol as? KaClassLikeSymbol)?.classId
                    val pkg = classId?.packageFqName?.asString()
                    if (pkg != null && pkg.startsWith("kotlin")) {
                        return "*From: kotlin-stdlib*"
                    }
                    if (pkg != null) {
                        return "*From: library ($pkg)*"
                    }
                    return "*From: library*"
                }
                return null
            }

            // Only show for library symbols (inside JARs or Gradle caches)
            if (!path.contains(".jar") && !path.contains(".gradle")) return null

            // Extract JAR filename from path
            val jarName = path.substringAfterLast('/')
                .let { if (it.contains("!")) path.substringBefore("!").substringAfterLast('/') else it }

            if (jarName.endsWith(".jar")) {
                return "*From: $jarName*"
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Enhances KDoc text with richer formatting:
     * - Converts [ClassName] bracket references to inline code
     * - Converts @author, @since, @see tags to bold labels
     */
    private fun enrichKDocText(text: String): String {
        return text
            // Convert [org.foo.Bar] to `Bar`
            .replace(Regex("\\[([\\w.]+)]")) { match ->
                val ref = match.groupValues[1]
                val shortName = ref.substringAfterLast('.')
                "`$shortName`"
            }
            // Format Javadoc-style tags into readable labels
            .replace(Regex("(?m)^\\*\\*@author\\*\\*\\s*(.+)")) { "**Author:** ${it.groupValues[1]}" }
            .replace(Regex("(?m)^\\*\\*@since\\*\\*\\s*(.+)")) { "**Since:** ${it.groupValues[1]}" }
            .replace(Regex("(?m)^\\*\\*@see\\*\\*\\s*(.+)")) { "**See also:** `${it.groupValues[1].trim()}`" }
            .replace(Regex("(?m)^\\*\\*@version\\*\\*\\s*(.+)")) { "**Version:** ${it.groupValues[1]}" }
    }

    /**
     * Extracts documentation for a symbol, trying multiple sources:
     * 1. KDoc from the PSI (works for source declarations)
     * 2. KDoc from decompiled stubs (works for Kotlin library declarations)
     * 3. Javadoc-style comments from Java stubs
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.extractSymbolDocumentation(
        symbol: KaDeclarationSymbol,
    ): String? {
        val psi = symbol.psi

        // Source KDoc
        if (psi is KtDeclaration) {
            val kdoc = extractKDocText(psi)
            if (kdoc != null) return kdoc
        }

        // For library symbols, try to find documentation from the decompiled source
        // The Analysis API may expose decompiled declarations with KDoc from metadata
        if (psi is KtDeclaration) {
            // Try extracting from the raw text of the decompiled declaration
            val docComment = extractDocCommentFromPsiText(psi)
            if (docComment != null) return docComment
        }

        // Try Javadoc-style comments (for Java declarations accessed via Kotlin)
        if (psi != null) {
            val javadoc = extractJavadocFromPsi(psi)
            if (javadoc != null) return javadoc
        }

        return null
    }

    /**
     * Extracts documentation from the raw PSI text, handling cases where
     * docComment returns null but the text itself contains documentation.
     */
    private fun extractDocCommentFromPsiText(declaration: KtDeclaration): String? {
        try {
            // Walk backwards through siblings looking for doc comments
            var prev = declaration.prevSibling
            while (prev != null) {
                if (prev is PsiWhiteSpace) {
                    prev = prev.prevSibling
                    continue
                }
                if (prev is PsiComment) {
                    val text = prev.text
                    if (text.startsWith("/**")) {
                        return parseDocCommentText(text)
                    }
                }
                break
            }
        } catch (_: Exception) {
            // Ignore
        }
        return null
    }

    /**
     * Parses a doc comment string into clean documentation text.
     * Strips comment markers and leading asterisks from each line.
     */
    private fun parseDocCommentText(comment: String): String? {
        val lines = comment.removePrefix("/**").removeSuffix("*/")
            .lines()
            .map { it.trim().removePrefix("* ").removePrefix("*") }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return lines.joinToString("\n")
    }

    /**
     * Tries to extract Javadoc from a Java PSI element.
     */
    private fun extractJavadocFromPsi(element: PsiElement): String? {
        try {
            // Check for PsiDocCommentOwner (Java interface)
            val docOwnerClass = try {
                Class.forName("com.intellij.psi.javadoc.PsiDocCommentOwner")
            } catch (_: ClassNotFoundException) {
                return null
            }

            if (!docOwnerClass.isInstance(element)) return null

            val getDocComment = docOwnerClass.getMethod("getDocComment")
            val docComment = getDocComment.invoke(element) ?: return null

            val getText = docComment.javaClass.getMethod("getText")
            val text = getText.invoke(docComment) as? String ?: return null

            return parseDocCommentText(text)
        } catch (_: Exception) {
            return null
        }
    }

    // --- Private helpers: completion ---

    /**
     * Finds the receiver expression for dot-completion, if the cursor is after a dot.
     * Returns the receiver KtExpression if we are in a dot-qualified position.
     */
    private fun findDotCompletionReceiver(element: PsiElement, offset: Int): KtExpression? {
        // Walk up to find a qualified expression where the cursor is after the dot
        var current: PsiElement? = element
        while (current != null && current !is KtFile) {
            if (current is KtQualifiedExpression) {
                val dotNode = current.operationTokenNode
                if (offset > dotNode.startOffset) {
                    return current.receiverExpression
                }
            }
            current = current.parent
        }

        // Also check if the previous character is a dot
        val file = element.containingFile ?: return null
        val document = file.viewProvider.document ?: return null
        val checkOffset = maxOf(0, offset - 1)
        if (checkOffset < document.textLength) {
            val charBefore = document.charsSequence[checkOffset]
            if (charBefore == '.') {
                // Find the expression just before the dot
                val beforeDot = file.findElementAt(maxOf(0, checkOffset - 1)) ?: return null
                var expr: PsiElement? = beforeDot
                while (expr != null && expr !is KtFile) {
                    if (expr is KtExpression && expr.textRange.endOffset == checkOffset) {
                        return expr
                    }
                    expr = expr.parent
                }
            }
        }

        return null
    }

    /**
     * Collects member completions for dot-qualified expressions.
     * Gets the type of the receiver and enumerates its members.
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.collectMemberCompletions(
        receiverExpr: KtExpression,
        itemsArray: JsonArray,
    ) {
        val receiverType = receiverExpr.expressionType ?: return

        // Use KaTypeScope to enumerate members with proper type substitution
        val typeScope = receiverType.scope
        if (typeScope != null) {
            // Collect callable signatures (functions and properties with substituted types)
            val callableSignatures = typeScope.getCallableSignatures { true }
            for (sig in callableSignatures) {
                val symbol = sig.symbol
                if (symbol is KaNamedSymbol) {
                    val name = symbol.name.asString()
                    // Skip internal/synthetic names
                    if (name.startsWith("<")) continue

                    val returnType = try {
                        sig.returnType.render(
                            KaTypeRendererForSource.WITH_SHORT_NAMES,
                            Variance.INVARIANT
                        )
                    } catch (_: Exception) { "" }

                    val item = JsonObject()
                    item.addProperty("label", name)
                    item.addProperty("kind", symbolToCompletionKind(symbol))
                    item.addProperty("detail", returnType)

                    if (symbol is KaFunctionSymbol) {
                        // Add parentheses for function calls
                        val params = symbol.valueParameters
                        if (params.isEmpty()) {
                            item.addProperty("insertText", "$name()")
                        } else {
                            item.addProperty("insertText", "$name(")
                        }
                    } else {
                        item.addProperty("insertText", name)
                    }

                    itemsArray.add(item)
                }
            }

            // Collect nested classifiers
            val classifiers = typeScope.getClassifierSymbols { true }
            for (classifier in classifiers) {
                if (classifier is KaNamedSymbol) {
                    val name = classifier.name.asString()
                    if (name.startsWith("<")) continue

                    val item = JsonObject()
                    item.addProperty("label", name)
                    item.addProperty("kind", "class")
                    item.addProperty("detail", "")
                    item.addProperty("insertText", name)
                    itemsArray.add(item)
                }
            }
        }

        // Also try to get the class symbol directly for member scope access
        // This catches cases where KaTypeScope might miss certain members
        if (receiverType is KaClassType) {
            val classSymbol = receiverType.symbol
            if (classSymbol is KaDeclarationContainerSymbol) {
                val memberScope = classSymbol.memberScope
                val existingLabels = mutableSetOf<String>()
                for (i in 0 until itemsArray.size()) {
                    val label = itemsArray[i].asJsonObject.get("label")?.asString
                    if (label != null) existingLabels.add(label)
                }

                for (callable in memberScope.callables) {
                    if (callable is KaNamedSymbol) {
                        val name = callable.name.asString()
                        if (name.startsWith("<") || name in existingLabels) continue
                        existingLabels.add(name)

                        val returnType = try {
                            callable.returnType.render(
                                KaTypeRendererForSource.WITH_SHORT_NAMES,
                                Variance.INVARIANT
                            )
                        } catch (_: Exception) { "" }

                        val item = JsonObject()
                        item.addProperty("label", name)
                        item.addProperty("kind", symbolToCompletionKind(callable))
                        item.addProperty("detail", returnType)

                        if (callable is KaFunctionSymbol) {
                            val params = callable.valueParameters
                            if (params.isEmpty()) {
                                item.addProperty("insertText", "$name()")
                            } else {
                                item.addProperty("insertText", "$name(")
                            }
                        } else {
                            item.addProperty("insertText", name)
                        }

                        itemsArray.add(item)
                    }
                }
            }
        }
    }

    /**
     * Collects scope-based completions by using the Analysis API scope context.
     * Gathers declarations visible at the given position including local variables,
     * parameters, functions, classes, and top-level declarations.
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.collectScopeCompletions(
        ktFile: KtFile,
        element: PsiElement,
        itemsArray: JsonArray,
    ) {
        // Find the nearest KtElement for scope context
        val ktElement = PsiTreeUtil.getParentOfType(element, KtElement::class.java, false)
            ?: element as? KtElement

        if (ktElement != null) {
            try {
                val scopeCtx = ktFile.scopeContext(ktElement)
                val compositeScope = scopeCtx.compositeScope { true }

                val seen = mutableSetOf<String>()

                // Collect callables from scope
                for (callable in compositeScope.callables) {
                    if (callable is KaNamedSymbol) {
                        val name = callable.name.asString()
                        if (name.startsWith("<") || !seen.add(name)) continue

                        val returnType = try {
                            callable.returnType.render(
                                KaTypeRendererForSource.WITH_SHORT_NAMES,
                                Variance.INVARIANT
                            )
                        } catch (_: Exception) { "" }

                        val item = JsonObject()
                        item.addProperty("label", name)
                        item.addProperty("kind", symbolToCompletionKind(callable))
                        item.addProperty("detail", returnType)

                        if (callable is KaFunctionSymbol) {
                            val params = callable.valueParameters
                            if (params.isEmpty()) {
                                item.addProperty("insertText", "$name()")
                            } else {
                                item.addProperty("insertText", "$name(")
                            }
                        } else {
                            item.addProperty("insertText", name)
                        }

                        itemsArray.add(item)
                    }
                }

                // Collect classifiers from scope
                for (classifier in compositeScope.classifiers) {
                    if (classifier is KaNamedSymbol) {
                        val name = classifier.name.asString()
                        if (name.startsWith("<") || !seen.add(name)) continue

                        val kind = when (classifier) {
                            is KaNamedClassSymbol -> when (classifier.classKind) {
                                KaClassKind.ENUM_CLASS -> "enum"
                                KaClassKind.INTERFACE -> "interface"
                                KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT -> "module"
                                else -> "class"
                            }
                            is KaTypeAliasSymbol -> "class"
                            is KaTypeParameterSymbol -> "typeParameter"
                            else -> "class"
                        }

                        val item = JsonObject()
                        item.addProperty("label", name)
                        item.addProperty("kind", kind)
                        item.addProperty("detail", "")
                        item.addProperty("insertText", name)
                        itemsArray.add(item)
                    }
                }
            } catch (e: Exception) {
                System.err.println("CompilerBridge: scope completion failed, falling back to PSI: ${e.message}")
                // Fallback to PSI-based completion
                collectPsiScopeCompletions(element, itemsArray)
            }
        } else {
            collectPsiScopeCompletions(element, itemsArray)
        }

        // Always add Kotlin keywords
        addKeywordCompletions(itemsArray)
    }

    /**
     * Extracts the identifier prefix being typed at the cursor position.
     * Looks at the raw file text before the cursor to find the identifier being typed.
     */
    private fun extractCompletionPrefix(element: PsiElement, offset: Int): String {
        val fileText = element.containingFile?.text ?: return ""
        // Walk backwards from the cursor offset to find the start of the identifier
        var start = offset
        while (start > 0 && (fileText[start - 1].isLetterOrDigit() || fileText[start - 1] == '_')) {
            start--
        }
        if (start >= offset) return ""
        return fileText.substring(start, offset)
    }

    /**
     * Appends completion items for unimported symbols that match the prefix.
     * Each item includes additionalTextEdits to auto-insert the import statement.
     */
    private fun appendUnimportedCompletions(ktFile: KtFile, prefix: String, itemsArray: JsonArray) {
        val existingLabels = (0 until itemsArray.size())
            .map { itemsArray[it].asJsonObject.get("label")?.asString ?: "" }
            .toSet()

        val existingImports = ktFile.importDirectives
            .mapNotNull { it.importedFqName?.asString() }
            .toSet()

        val candidates = symbolIndex.searchSymbols(prefix, limit = 20)
            .filter { it.fqn != null && it.fqn !in existingImports && it.shortName !in existingLabels }

        if (candidates.isEmpty()) return

        val document = ktFile.viewProvider.document ?: return
        val importLine = findImportInsertLine(ktFile, document)

        for (decl in candidates) {
            val item = JsonObject()
            item.addProperty("label", decl.shortName)
            item.addProperty("kind", decl.kind)
            item.addProperty("detail", decl.fqn)
            item.addProperty("insertText", decl.shortName)
            item.addProperty("sortText", "z_${decl.shortName}") // rank after imported symbols

            // additionalTextEdits: insert the import statement
            val editsArray = JsonArray()
            val edit = JsonObject()
            edit.addProperty("newText", "import ${decl.fqn}\n")
            edit.addProperty("line", importLine)
            edit.addProperty("column", 0)
            edit.addProperty("endLine", importLine)
            edit.addProperty("endColumn", 0)
            editsArray.add(edit)
            item.add("additionalTextEdits", editsArray)

            itemsArray.add(item)
        }
    }

    /**
     * Fallback: walks up the PSI tree to collect named declarations in enclosing scopes.
     */
    private fun collectPsiScopeCompletions(element: PsiElement, itemsArray: JsonArray) {
        val seen = mutableSetOf<String>()
        var current: PsiElement? = element.parent

        while (current != null && current !is KtFile) {
            if (current is KtBlockExpression || current is KtClassBody || current is KtFunction) {
                for (child in current.children) {
                    if (child is KtNamedDeclaration) {
                        val name = child.name ?: continue
                        if (!seen.add(name)) continue

                        val item = JsonObject()
                        item.addProperty("label", name)
                        item.addProperty("kind", psiToCompletionKind(child))
                        item.addProperty("detail", "")
                        item.addProperty("insertText", name)
                        itemsArray.add(item)
                    }
                }
            }
            // Collect function parameters
            if (current is KtFunction) {
                for (param in current.valueParameters) {
                    val name = param.name ?: continue
                    if (!seen.add(name)) continue

                    val item = JsonObject()
                    item.addProperty("label", name)
                    item.addProperty("kind", "variable")
                    item.addProperty("detail", param.typeReference?.text ?: "")
                    item.addProperty("insertText", name)
                    itemsArray.add(item)
                }
            }
            current = current.parent
        }

        // Collect top-level declarations from the file
        if (current is KtFile) {
            for (decl in current.declarations) {
                if (decl !is KtNamedDeclaration) continue
                val name = decl.name ?: continue
                if (!seen.add(name)) continue

                val item = JsonObject()
                item.addProperty("label", name)
                item.addProperty("kind", psiToCompletionKind(decl))
                item.addProperty("detail", "")
                item.addProperty("insertText", name)
                itemsArray.add(item)
            }
        }
    }

    /**
     * Adds common Kotlin keyword completions.
     */
    private fun addKeywordCompletions(itemsArray: JsonArray) {
        val keywords = listOf(
            "val", "var", "fun", "class", "interface", "object", "enum",
            "if", "else", "when", "for", "while", "do", "return", "break",
            "continue", "throw", "try", "catch", "finally", "is", "as",
            "in", "null", "true", "false", "this", "super", "override",
            "abstract", "open", "sealed", "data", "inner", "companion",
            "private", "protected", "internal", "public", "suspend",
            "inline", "crossinline", "noinline", "reified", "import",
            "package", "typealias", "operator", "infix", "tailrec",
            "external", "annotation", "const", "lateinit", "by", "lazy",
        )

        for (keyword in keywords) {
            val item = JsonObject()
            item.addProperty("label", keyword)
            item.addProperty("kind", "keyword")
            item.addProperty("detail", "")
            item.addProperty("insertText", keyword)
            itemsArray.add(item)
        }
    }

    // --- Private helpers: signature help ---

    /**
     * Computes the active parameter index based on cursor position within
     * the argument list of a call expression.
     */
    private fun computeActiveParameter(callExpression: KtCallExpression, offset: Int): Int {
        val argList = callExpression.valueArgumentList ?: return 0
        val leftParen = argList.leftParenthesis ?: return 0

        // If cursor is before or at the left parenthesis, active param is 0
        if (offset <= leftParen.textRange.endOffset) return 0

        // Count commas before the cursor position
        var commaCount = 0
        for (child in argList.node.getChildren(null)) {
            val childElement = child.psi ?: continue
            if (childElement.textRange.startOffset >= offset) break
            if (child.text == ",") commaCount++
        }

        return commaCount
    }

    // --- Private helpers: references ---

    /**
     * Finds the target declaration element starting from a PSI element.
     * If the element is a reference, resolves it to the declaration.
     * If the element is already a declaration, returns it directly.
     */
    private fun findTargetDeclaration(element: PsiElement): PsiElement? {
        // Walk up to find a named declaration or reference expression
        var current: PsiElement? = element
        while (current != null && current !is KtFile) {
            if (current is KtNamedDeclaration) {
                return current
            }
            if (current is KtReferenceExpression) {
                // Try to resolve the reference to its declaration
                for (ref in current.references) {
                    val resolved = ref.resolve()
                    if (resolved != null) {
                        return resolved
                    }
                }
            }
            current = current.parent
        }
        return null
    }

    private fun findSpringModelProducerDeclaration(element: PsiElement): PsiElement? {
        val call = PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java, false) ?: return null
        val calleeName = call.calleeExpression?.text ?: return null
        if (calleeName != "addAttribute" && calleeName != "addObject") return null

        val args = call.valueArguments
        if (args.size < 2) return null
        if (staticStringValue(args[0].getArgumentExpression()) == null) return null

        val valueExpression = args[1].getArgumentExpression() ?: return null
        val function = PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java, true) ?: return null
        return resolveSpringProducerElement(function, valueExpression)
    }

    private fun staticStringValue(expression: KtExpression?): String? {
        val stringTemplate = expression as? KtStringTemplateExpression ?: return null
        if (stringTemplate.hasInterpolation()) return null
        return stringTemplate.entries.joinToString(separator = "") { it.text }
    }

    private fun resolveSpringProducerElement(function: KtNamedFunction, expression: KtExpression): PsiElement? {
        val nameExpression = expression as? KtNameReferenceExpression ?: return expression
        val targetName = nameExpression.getReferencedName()

        val parameter = function.valueParameters.firstOrNull { it.name == targetName }
        if (parameter != null) return parameter

        val localProperty = PsiTreeUtil.collectElementsOfType(function, KtProperty::class.java)
            .filter { it.name == targetName && it.textOffset <= nameExpression.textOffset }
            .maxByOrNull { it.textOffset }
        if (localProperty != null) return localProperty

        val classBody = function.parents.filterIsInstance<KtClassBody>().firstOrNull()
        val classProperty = classBody
            ?.declarations
            ?.filterIsInstance<KtProperty>()
            ?.firstOrNull { it.name == targetName }
        if (classProperty != null) return classProperty

        val fileProperty = function.containingKtFile.declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == targetName }
        if (fileProperty != null) return fileProperty

        return expression
    }

    // --- Private helpers: code actions ---

    /**
     * Finds the nearest enclosing declaration for a given PSI element.
     * Used to determine where to insert @Suppress annotations.
     */
    private fun findEnclosingDeclaration(element: PsiElement?): KtDeclaration? {
        var current: PsiElement? = element
        while (current != null && current !is KtFile) {
            if (current is KtDeclaration && current !is KtParameter) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /**
     * Finds the line number where a new import statement should be inserted.
     * Returns a 1-based line number after the package statement and existing imports.
     */
    private fun findImportInsertLine(
        ktFile: KtFile,
        document: com.intellij.openapi.editor.Document,
    ): Int {
        // If there are existing imports, insert after the last one
        val importList = ktFile.importList
        if (importList != null && importList.imports.isNotEmpty()) {
            val lastImport = importList.imports.last()
            return document.getLineNumber(lastImport.textRange.endOffset) + 2 // 1-based, next line
        }

        // If there's a package statement, insert after it (with a blank line)
        val packageDirective = ktFile.packageDirective
        if (packageDirective != null && packageDirective.text.isNotBlank()) {
            return document.getLineNumber(packageDirective.textRange.endOffset) + 3 // 1-based, skip blank line
        }

        // Otherwise insert at the top
        return 1
    }

    // --- Private helpers: code lens ---

    /**
     * Counts the number of references to a declaration within the same file.
     * Must be called inside an `analyze` block.
     */
    private fun countReferencesInFile(ktFile: KtFile, declaration: KtNamedDeclaration): Int {
        var count = 0
        val allNameExprs = PsiTreeUtil.collectElementsOfType(
            ktFile, KtSimpleNameExpression::class.java
        )

        for (nameExpr in allNameExprs) {
            try {
                for (ref in nameExpr.references) {
                    val resolved = ref.resolve()
                    if (resolved != null && resolved == declaration) {
                        count++
                    }
                }
            } catch (_: Exception) {
                // Skip references that fail to resolve
            }
        }

        return count
    }

    // --- Private helpers: auto-import ---

    /**
     * Searches all files in the session for top-level declarations matching the given short name.
     * Returns a list of fully-qualified names that could be imported.
     */
    private fun findImportCandidates(shortName: String): List<String> {
        return symbolIndex.findByShortName(shortName)
            .mapNotNull { it.fqn }
            .filter { it != shortName }
            .distinct()
            .sorted()
    }

    // --- Private helpers: semantic tokens ---

    /**
     * Classifies an identifier PSI element by its parent node type.
     * Returns the semantic token type index, or -1 if not classifiable.
     */
    private fun classifyIdentifierByParent(parent: PsiElement?): Int {
        return when (parent) {
            is KtNamedFunction -> 12       // function
            is KtParameter -> 7            // parameter
            is KtProperty -> {
                if (parent.isLocal) 8      // variable
                else 9                     // property
            }
            is KtClassOrObject -> 2        // class
            is KtTypeReference -> 1        // type
            is KtTypeAlias -> 1            // type
            is KtTypeParameter -> 6        // typeParameter
            is KtEnumEntry -> 10           // enumMember
            is KtSimpleNameExpression -> {
                // For reference expressions, classify by what they refer to
                classifyReferenceExpression(parent)
            }
            is KtOperationReferenceExpression -> 21 // operator
            else -> -1
        }
    }

    /**
     * Classifies a reference expression (e.g. usage of a name) by looking at
     * the parent context. This is a best-effort PSI-only classification.
     */
    private fun classifyReferenceExpression(expr: KtSimpleNameExpression): Int {
        val grandParent = expr.parent
        return when {
            grandParent is KtCallExpression && grandParent.calleeExpression == expr -> 12 // function
            grandParent is KtTypeReference -> 1         // type
            grandParent is KtDotQualifiedExpression && grandParent.selectorExpression == expr -> {
                // Could be a property or function access; default to property
                // (function calls are KtCallExpression which is caught above)
                9 // property
            }
            grandParent is KtUserType -> 1              // type
            else -> 8                                    // variable (default for references)
        }
    }

    /**
     * Determines token modifiers bitmask for an identifier.
     * Bit 0 = declaration, bit 1 = definition, bit 2 = readonly, etc.
     */
    private fun classifyIdentifierModifiers(parent: PsiElement?): Int {
        var modifiers = 0
        when (parent) {
            is KtNamedFunction,
            is KtClassOrObject,
            is KtTypeAlias,
            is KtTypeParameter,
            is KtEnumEntry -> {
                modifiers = modifiers or 0x01 // declaration
                modifiers = modifiers or 0x02 // definition
            }
            is KtParameter -> {
                modifiers = modifiers or 0x01 // declaration
            }
            is KtProperty -> {
                modifiers = modifiers or 0x01 // declaration
                modifiers = modifiers or 0x02 // definition
                if (parent.isVar.not()) {
                    modifiers = modifiers or 0x04 // readonly
                }
            }
        }
        return modifiers
    }

    /**
     * Builds the legend JSON object containing token types and modifiers arrays.
     */
    private fun buildLegend(tokenTypes: List<String>, tokenModifiers: List<String>): JsonObject {
        val legend = JsonObject()
        val typesArray = JsonArray()
        for (t in tokenTypes) typesArray.add(t)
        legend.add("tokenTypes", typesArray)
        val modsArray = JsonArray()
        for (m in tokenModifiers) modsArray.add(m)
        legend.add("tokenModifiers", modsArray)
        return legend
    }

    // --- Private helpers: call hierarchy ---

    /**
     * Builds a call hierarchy item JSON object for a function declaration.
     */
    private fun buildCallHierarchyItem(
        function: KtNamedFunction,
        ktFile: KtFile,
        document: com.intellij.openapi.editor.Document,
    ): JsonObject? {
        val name = function.name ?: return null
        val nameIdentifier = function.nameIdentifier ?: return null

        val declOffset = function.textOffset
        val declLine = document.getLineNumber(declOffset) + 1
        val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
        val declCol = declOffset - declLineStart

        val selOffset = nameIdentifier.textOffset
        val selLine = document.getLineNumber(selOffset) + 1
        val selLineStart = document.getLineStartOffset(document.getLineNumber(selOffset))
        val selCol = selOffset - selLineStart

        val item = JsonObject()
        item.addProperty("name", name)
        item.addProperty("kind", "function")
        item.addProperty("uri", "file://${ktFile.virtualFile.path}")
        item.addProperty("line", declLine)
        item.addProperty("column", declCol)
        item.addProperty("selectionLine", selLine)
        item.addProperty("selectionColumn", selCol)
        return item
    }

    // --- Private helpers: type hierarchy ---

    /**
     * Builds a type hierarchy item JSON object for a class/interface declaration.
     */
    private fun buildTypeHierarchyItem(
        classOrObject: KtClassOrObject,
        ktFile: KtFile,
        document: com.intellij.openapi.editor.Document,
    ): JsonObject? {
        val name = classOrObject.name ?: return null
        val nameIdentifier = classOrObject.nameIdentifier ?: return null

        val declOffset = nameIdentifier.textOffset
        val declLine = document.getLineNumber(declOffset) + 1
        val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
        val declCol = declOffset - declLineStart

        val kind = when {
            classOrObject is KtObjectDeclaration -> "object"
            (classOrObject as? KtClass)?.isInterface() == true -> "interface"
            (classOrObject as? KtClass)?.isEnum() == true -> "enum"
            else -> "class"
        }

        val item = JsonObject()
        item.addProperty("name", name)
        item.addProperty("kind", kind)
        item.addProperty("uri", "file://${ktFile.virtualFile.path}")
        item.addProperty("line", declLine)
        item.addProperty("column", declCol)
        return item
    }

    // --- Private helpers: utilities ---

    /**
     * Maps a KaCallableSymbol to an LSP completion item kind string.
     */
    private fun symbolToCompletionKind(symbol: KaCallableSymbol): String {
        return when (symbol) {
            is KaNamedFunctionSymbol -> "function"
            is KaConstructorSymbol -> "constructor"
            is KaPropertySymbol -> "property"
            is KaLocalVariableSymbol -> "variable"
            is KaValueParameterSymbol -> "variable"
            is KaEnumEntrySymbol -> "enumMember"
            else -> "value"
        }
    }

    /**
     * Maps a PSI declaration to an LSP completion item kind string.
     */
    private fun psiToCompletionKind(declaration: KtNamedDeclaration): String {
        return when (declaration) {
            is KtNamedFunction -> "function"
            is KtProperty -> if (declaration.isLocal) "variable" else "property"
            is KtClassOrObject -> "class"
            is KtParameter -> "variable"
            is KtTypeAlias -> "class"
            else -> "value"
        }
    }

    /**
     * Extracts the KDoc text from a declaration, stripped of comment markers.
     * Returns null if no KDoc is present.
     */
    private fun extractKDocText(declaration: KtDeclaration): String? {
        val kdoc = declaration.docComment ?: return null
        val defaultSection = kdoc.getDefaultSection()
        val content = defaultSection.getContent().trim()

        if (content.isEmpty()) return null

        val extraSections = StringBuilder()

        // Collect standard KDoc tagged sections (@param, @return, @throws, etc.)
        val allSections = kdoc.getAllSections()
        for (section in allSections) {
            if (section == defaultSection) continue
            val sectionName = section.name
            val sectionContent = section.getContent().trim()
            if (sectionContent.isNotEmpty()) {
                if (sectionName != null) {
                    extraSections.append("\n\n**@$sectionName** $sectionContent")
                } else {
                    extraSections.append("\n\n$sectionContent")
                }
            }
        }

        // Also extract Javadoc-style tags (@author, @since, @see) from raw KDoc text,
        // since the Kotlin KDoc parser doesn't recognize these as section-creating tags
        val rawText = kdoc.text ?: ""
        val javadocTagPattern = Regex("""(?m)^\s*\*?\s*@(author|since|see|version)\s+(.+)""")
        for (match in javadocTagPattern.findAll(rawText)) {
            val tag = match.groupValues[1]
            val value = match.groupValues[2].trim()
            if (value.isNotEmpty()) {
                extraSections.append("\n\n**@$tag** $value")
            }
        }

        return content + extraSections.toString()
    }

    /**
     * Registers a custom module provider that maps KtPsiFactory files (backed by LightVirtualFile)
     * to the source module. This ensures that analyze() on KtPsiFactory files enters the source
     * module's context, inheriting its LanguageVersionSettings (including -Xcontext-parameters).
     *
     * Without this, KtPsiFactory files are not in any module's contentScope and fall into the
     * default "not under content root" module which uses default language settings.
     */
    private fun registerVirtualFileModuleProvider() {
        val currentSession = session ?: return
        val module = sourceModule ?: return
        val project = currentSession.project

        try {
            val originalProvider = KotlinProjectStructureProvider.getInstance(project)

            val customProvider = object : KotlinProjectStructureProvider {
                override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule {
                    val file = element.containingFile
                    if (file != null) {
                        val vf = file.virtualFile
                        // Map KtPsiFactory-created files to the source module so they inherit
                        // its language settings and get full FIR resolution (class-body, etc.).
                        // This covers both non-physical files (virtualFile == null) and physical
                        // files backed by LightVirtualFile (from createPhysicalFile).
                        if (vf == null || vf is com.intellij.testFramework.LightVirtualFile) {
                            return module
                        }
                    }
                    return originalProvider.getModule(element, useSiteModule)
                }

                override fun getImplementingModules(module: KaModule): List<KaModule> {
                    return originalProvider.getImplementingModules(module)
                }
            }

            // Replace the service: unregister old, register new
            val mockClass = Class.forName("com.intellij.mock.MockComponentManager")
            val picoField = mockClass.getDeclaredField("picoContainer")
            picoField.isAccessible = true
            val container = picoField.get(project)
            val unregisterMethod = container.javaClass.getMethod("unregisterComponent", Any::class.java)
            unregisterMethod.invoke(container, KotlinProjectStructureProvider::class.java.name)

            val registerMethod = mockClass.getMethod(
                "registerService", Class::class.java, Any::class.java
            )
            registerMethod.invoke(project, KotlinProjectStructureProvider::class.java, customProvider)

            System.err.println("CompilerBridge: registered custom KotlinProjectStructureProvider for virtual file support")
        } catch (e: Exception) {
            System.err.println("CompilerBridge: WARNING — failed to register custom module provider: ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Finds a KtFile in the session's discovered source files only (no ad-hoc fallbacks).
     * Used by updateFileInSession to locate the in-session PSI file for document updates.
     */
    private fun findKtFileInSession(session: StandaloneAnalysisAPISession, uri: String): KtFile? {
        val filePath = uriToPath(uri)
        return session.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()
            .find {
                val path = it.virtualFile.path
                path == filePath || it.name == filePath || shadowPathMapping[path] == filePath
            }
    }

    /**
     * Notifies that a file's content has been updated.
     * Content is stored in virtualFiles and used by findKtFile() to create a
     * LightVirtualFile-backed KtFile through PsiManager for proper FIR resolution.
     */
    private fun updateFileInSession(uri: String, text: String) {
        System.err.println("CompilerBridge: updateFile($uri) — stored in virtualFiles (${text.length} chars)")
    }

    /**
     * Builds a shadow source tree in the temp directory with virtual content applied.
     *
     * When on-disk files have been edited via updateFile(), the session must be rebuilt
     * with the virtual content. This method copies all source files from the original
     * source roots into a shadow directory, replacing modified files with their virtual
     * content. The shadow directories are used as source roots instead of the originals,
     * ensuring FIR only sees one version of each file.
     */
    private fun buildShadowSourceRoots(baseSourceRoots: List<Path>): List<Path> {
        val tempDir = virtualFileTempDir ?: return baseSourceRoots
        val shadowRoot = tempDir.resolve("shadow")

        // Clean previous shadow tree
        if (shadowRoot.toFile().exists()) {
            shadowRoot.toFile().deleteRecursively()
        }
        shadowPathMapping.clear()

        val shadowRoots = mutableListOf<Path>()
        for ((index, root) in baseSourceRoots.withIndex()) {
            val rootFile = root.toFile()
            if (!rootFile.exists()) continue

            val shadowDir = shadowRoot.resolve("root$index")
            Files.createDirectories(shadowDir)

            rootFile.walk()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val relativePath = file.relativeTo(rootFile).path
                    val uri = "file://${file.absolutePath}"
                    val hasVirtualOverride = hasVirtualFile(uri)
                    val content = getVirtualFileContent(uri) ?: file.readText()
                    val target = shadowDir.resolve(relativePath)
                    target.parent.toFile().mkdirs()
                    Files.writeString(target, content)

                    if (hasVirtualOverride) {
                        System.err.println("CompilerBridge: shadow tree — $relativePath using VIRTUAL content (${content.length} chars): ${content.take(80)}")
                    }

                    // Map shadow path back to original for findKtFile matching
                    shadowPathMapping[target.toString()] = file.absolutePath
                }

            shadowRoots.add(shadowDir)
        }

        System.err.println("CompilerBridge: built shadow source tree with ${shadowPathMapping.size} files across ${shadowRoots.size} root(s)")
        return shadowRoots
    }

    /**
     * Writes a virtual file to the temp directory with package-based directory structure.
     * This makes the file discoverable by FIR when the temp dir is a source root.
     */
    private fun writeVirtualFileToDisk(uri: String, text: String) {
        val tempDir = virtualFileTempDir ?: return

        // Skip files that already exist on disk — they're already in the session's source roots
        val filePath = uriToPath(uri)
        if (File(filePath).exists()) {
            System.err.println("CompilerBridge: skipping temp dir write for $uri — file exists on disk")
            return
        }

        // Extract package name from source to build directory structure
        val packageName = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1) ?: ""
        val packageDirs = packageName.replace('.', '/')

        val fileName = filePath.substringAfterLast('/')

        // Build target path: tempDir/package/dirs/FileName.kt
        val targetDir = if (packageDirs.isNotEmpty()) {
            tempDir.resolve(packageDirs)
        } else {
            tempDir
        }
        Files.createDirectories(targetDir)
        val targetFile = targetDir.resolve(fileName)
        Files.writeString(targetFile, text)
        virtualFilesOnDisk.add(uri)
        virtualFileDiskPaths[uri] = targetFile.toString()

        System.err.println("CompilerBridge: wrote virtual file to disk: $targetFile")
    }

    /**
     * Writes decompiled library source to a temp file so the editor can open it.
     * Returns the disk path, or null if the temp dir is unavailable.
     * Results are cached so repeated go-to-definition calls don't re-write.
     */
    private fun writeDecompiledFile(vfPath: String, text: String): Path? {
        val tempDir = virtualFileTempDir ?: return null
        decompiledFileCache[vfPath]?.let { return it }

        // Extract the path after "!/" (JAR-internal path), or use the full path
        val internalPath = if (vfPath.contains("!/")) {
            vfPath.substringAfter("!/")
        } else {
            vfPath.substringAfterLast("/")
        }

        // Convert .class extension to .kt for readability
        val ktPath = if (internalPath.endsWith(".class")) {
            internalPath.removeSuffix(".class") + ".kt"
        } else {
            internalPath
        }

        val targetFile = tempDir.resolve("decompiled").resolve(ktPath)
        try {
            Files.createDirectories(targetFile.parent)
            Files.writeString(targetFile, text)
            decompiledFileCache[vfPath] = targetFile
            System.err.println("CompilerBridge: wrote decompiled file: $targetFile")
            return targetFile
        } catch (e: Exception) {
            System.err.println("CompilerBridge: failed to write decompiled file: ${e.message}")
            return null
        }
    }

    /**
     * Renders a library symbol as a Kotlin stub file and writes it to disk.
     * Returns a definition location pointing to the generated file, or null on failure.
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.renderLibrarySymbolStub(
        symbol: KaDeclarationSymbol,
        vfPath: String,
    ): JsonObject? {
        try {
            val rendered = symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
            if (rendered.isBlank()) return null

            // Shorten fully qualified names (e.g. kotlin.annotation.AnnotationTarget -> AnnotationTarget)
            // and collect them as imports
            val fqNamePattern = Regex("""([\w]+(?:\.[\w]+){2,})""")
            val imports = mutableSetOf<String>()
            var shortened = rendered
            for (match in fqNamePattern.findAll(rendered).sortedByDescending { it.range.first }) {
                val fqName = match.value
                // Only shorten if the last segment looks like a class/object name (starts uppercase)
                val segments = fqName.split('.')
                val shortName = segments.last()
                // Find the class boundary: last segment starting with uppercase
                val classIdx = segments.indexOfLast { it.first().isUpperCase() }
                if (classIdx < 0) continue
                val importName = segments.subList(0, classIdx + 1).joinToString(".")
                if (importName.count { it == '.' } < 1) continue // skip if no package
                imports.add(importName)
                val replacement = segments.subList(classIdx, segments.size).joinToString(".")
                shortened = shortened.replaceRange(match.range, replacement)
            }

            // Build package declaration from classId
            val packageName = when (symbol) {
                is KaClassLikeSymbol -> symbol.classId?.packageFqName?.asString()
                is KaCallableSymbol -> symbol.callableId?.packageName?.asString()
                else -> null
            }
            // Remove self-package imports
            if (!packageName.isNullOrEmpty()) {
                imports.removeAll { it.substringBeforeLast('.') == packageName }
            }
            // Extract documentation (KDoc or Javadoc) from the symbol's PSI
            val docText = extractSymbolDocumentation(symbol)

            val stubText = buildString {
                if (!packageName.isNullOrEmpty()) {
                    append("package $packageName\n\n")
                }
                if (imports.isNotEmpty()) {
                    imports.sorted().forEach { append("import $it\n") }
                    append("\n")
                }
                if (docText != null) {
                    append("/**\n")
                    docText.lines().forEach { line -> append(" * $line\n") }
                    append(" */\n")
                }
                append(shortened)
                append("\n")
            }

            // Find the declaration line in the generated stub
            val declLine = stubText.substring(0, stubText.indexOf(shortened)).count { it == '\n' } + 1

            val decompiledPath = writeDecompiledFile(vfPath, stubText) ?: return null
            val loc = JsonObject()
            loc.addProperty("uri", "file://${decompiledPath}")
            loc.addProperty("line", declLine)
            loc.addProperty("column", 0)
            return loc
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: renderLibrarySymbolStub failed: ${e.message?.take(100)}")
            return null
        }
    }

    /**
     * Cache of LightVirtualFile-backed KtFiles for virtual content.
     * Reused across calls to avoid creating new files on every access.
     * Invalidated when virtualFiles content changes (checked by content equality).
     */
    private val lightFileCache = mutableMapOf<String, Pair<String, KtFile>>() // uri -> (content, ktFile)

    private fun findKtFile(session: StandaloneAnalysisAPISession, uri: String): KtFile? {
        val filePath = uriToPath(uri)

        val allSessionFiles = session.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()

        System.err.println("CompilerBridge: findKtFile($uri) — resolved path=$filePath, session has ${allSessionFiles.size} file(s)")

        val virtualEntry = getVirtualFileEntry(uri)
        val virtualContent = virtualEntry?.second

        // Helper: match a session file to the requested path (including shadow tree paths)
        fun matchesPath(ktFile: KtFile): Boolean {
            val path = ktFile.virtualFile.path
            return path == filePath || ktFile.name == filePath ||
                shadowPathMapping[path] == filePath
        }

        // If no virtual content, use the session file (on-disk content)
        if (virtualContent == null) {
            val sessionFile = allSessionFiles.find { matchesPath(it) }
            if (sessionFile != null) {
                System.err.println("CompilerBridge: findKtFile($uri) — FOUND in session: ${sessionFile.virtualFile.path}")
                return sessionFile
            }
        } else {
            // Check if session file has matching content — by original path, temp dir path, or shadow path
            val tempDiskPath = virtualFileDiskPaths[uri] ?: virtualEntry?.let { virtualFileDiskPaths[it.first] }
            val sessionFile = allSessionFiles
                .find {
                    matchesPath(it) ||
                    (tempDiskPath != null && it.virtualFile.path == tempDiskPath)
                }
            if (sessionFile != null && sessionFile.text == virtualContent) {
                System.err.println("CompilerBridge: findKtFile($uri) — session file content matches virtualFiles, using session file: ${sessionFile.virtualFile.path}")
                return sessionFile
            }

            // File not in session or in-place update failed — create a LightVirtualFile-backed
            // KtFile through PsiManager. This gives a proper VFS-backed file that the
            // custom KotlinProjectStructureProvider maps to the source module, enabling
            // full FIR resolution including class-body members.
            val cacheKey = virtualEntry?.first ?: uri
            val cached = lightFileCache[cacheKey]
            if (cached != null && cached.first == virtualContent) {
                System.err.println("CompilerBridge: findKtFile($uri) — using cached LightVirtualFile (${virtualContent.length} chars)")
                return cached.second
            }

            System.err.println("CompilerBridge: findKtFile($uri) — creating LightVirtualFile (${virtualContent.length} chars)")
            try {
                val fileName = filePath.substringAfterLast('/')
                val lightVf = com.intellij.testFramework.LightVirtualFile(fileName, virtualContent)
                val psiFile = com.intellij.psi.PsiManager.getInstance(session.project).findFile(lightVf)
                if (psiFile is KtFile) {
                    lightFileCache[cacheKey] = virtualContent to psiFile
                    return psiFile
                }
                System.err.println("CompilerBridge: LightVirtualFile did not produce KtFile: ${psiFile?.javaClass?.name}")
            } catch (e: Exception) {
                System.err.println("CompilerBridge: LightVirtualFile failed: ${e.javaClass.name}: ${e.message}")
            }

            // Fallback: KtPsiFactory (loses module context but better than nothing)
            try {
                val psiFactory = KtPsiFactory(session.project)
                return psiFactory.createPhysicalFile(filePath.substringAfterLast('/'), virtualContent)
            } catch (e: Exception) {
                System.err.println("CompilerBridge: failed to create KtFile from virtualFiles: ${e.message}")
            }
        }

        System.err.println("CompilerBridge: findKtFile($uri) — NOT in session. Session file paths:")
        for (f in allSessionFiles.take(10)) {
            System.err.println("  session file: ${f.virtualFile.path}")
        }
        if (allSessionFiles.size > 10) {
            System.err.println("  ... and ${allSessionFiles.size - 10} more")
        }

        // Last resort: try to read from disk if the file exists.
        val file = File(filePath)
        if (file.exists() && file.extension == "kt") {
            System.err.println("CompilerBridge: findKtFile($uri) — creating from disk: $filePath")
            try {
                val lightVf = com.intellij.testFramework.LightVirtualFile(file.name, file.readText())
                val psiFile = com.intellij.psi.PsiManager.getInstance(session.project).findFile(lightVf)
                if (psiFile is KtFile) return psiFile
            } catch (_: Exception) {}
            try {
                val psiFactory = KtPsiFactory(session.project)
                return psiFactory.createPhysicalFile(file.name, file.readText())
            } catch (e: Exception) {
                System.err.println("CompilerBridge: failed to create KtFile from disk: ${e.message}")
            }
        }

        System.err.println("CompilerBridge: findKtFile($uri) — NOT FOUND anywhere (path=$filePath)")
        System.err.println("CompilerBridge: virtualFiles keys: ${virtualFiles.keys}")
        return null
    }

    /**
     * Collects all names declared in a KtFile — top-level declarations, class members,
     * constructor parameters. Used to filter false UNRESOLVED_REFERENCE diagnostics for
     * virtual files where the standalone API can't resolve same-file declarations.
     */
    private fun collectDeclaredNames(ktFile: KtFile): Set<String> {
        val names = mutableSetOf<String>()
        for (decl in ktFile.declarations) {
            when (decl) {
                is KtClassOrObject -> {
                    decl.name?.let { names.add(it) }
                    // Class members (properties, functions)
                    for (member in decl.declarations) {
                        (member as? KtNamedDeclaration)?.name?.let { names.add(it) }
                    }
                    // Constructor parameters
                    (decl as? KtClass)?.primaryConstructorParameters?.forEach { param ->
                        param.name?.let { names.add(it) }
                    }
                }
                is KtNamedFunction -> decl.name?.let { names.add(it) }
                is KtProperty -> decl.name?.let { names.add(it) }
                is KtTypeAlias -> decl.name?.let { names.add(it) }
                else -> {}
            }
        }
        return names
    }

    private fun uriToPath(uri: String): String {
        return if (uri.startsWith("file://")) {
            uri.removePrefix("file://")
        } else {
            uri
        }
    }

    private fun getVirtualFileEntry(uri: String): Pair<String, String>? {
        virtualFiles[uri]?.let { return uri to it }
        val path = uriToPath(uri)
        return virtualFiles.entries.firstOrNull { uriToPath(it.key) == path }?.let { it.key to it.value }
    }

    private fun getVirtualFileContent(uri: String): String? = getVirtualFileEntry(uri)?.second

    private fun hasVirtualFile(uri: String): Boolean = getVirtualFileContent(uri) != null

    private fun lineColToOffset(ktFile: KtFile, line: Int, character: Int): Int? {
        val document = ktFile.viewProvider.document
        if (document != null) {
            if (line < 1 || line > document.lineCount) {
                System.err.println("CompilerBridge: lineColToOffset — line $line out of range (1..${document.lineCount}), doc path")
                return null
            }
            val lineStartOffset = document.getLineStartOffset(line - 1)
            val lineEndOffset = document.getLineEndOffset(line - 1)
            if (character < 0) {
                System.err.println("CompilerBridge: lineColToOffset — character $character is negative, clamping to 0")
                return lineStartOffset
            }

            val lineLength = (lineEndOffset - lineStartOffset).coerceAtLeast(0)
            val safeCharacter = character.coerceAtMost(lineLength)
            if (safeCharacter != character) {
                System.err.println("CompilerBridge: lineColToOffset — character $character clamped to line length $lineLength (line=$line)")
            }
            return lineStartOffset + safeCharacter
        }
        // Fallback for LightVirtualFile-backed files where document may be null:
        // compute offset from raw text
        val text = ktFile.text
        if (line < 1) {
            System.err.println("CompilerBridge: lineColToOffset — line $line out of range (1..?) for LightVirtualFile fallback")
            return null
        }
        var currentLine = 1
        var offset = 0
        while (offset < text.length && currentLine < line) {
            if (text[offset] == '\n') currentLine++
            offset++
        }
        if (currentLine != line) {
            System.err.println("CompilerBridge: lineColToOffset — fallback: line $line not found (reached line $currentLine at offset $offset, textLen=${text.length})")
            return null
        }
        var lineEndOffset = offset
        while (lineEndOffset < text.length && text[lineEndOffset] != '\n') {
            lineEndOffset++
        }
        if (character < 0) {
            return offset
        }

        val lineLength = (lineEndOffset - offset).coerceAtLeast(0)
        val safeCharacter = character.coerceAtMost(lineLength)
        if (safeCharacter != character) {
            System.err.println("CompilerBridge: lineColToOffset — fallback character $character clamped to line length $lineLength (line=$line)")
        }
        return offset + safeCharacter
    }

    private fun findHoverElement(ktFile: KtFile, line: Int, offset: Int): PsiElement? {
        val directHit = ktFile.findElementAt(offset)
        if (directHit != null) {
            return directHit
        }

        val document = ktFile.viewProvider.document ?: return null
        if (line < 1 || line > document.lineCount) {
            return null
        }

        val lineStartOffset = document.getLineStartOffset(line - 1)
        var probe = offset - 1
        while (probe >= lineStartOffset) {
            val candidate = ktFile.findElementAt(probe)
            if (candidate != null) {
                return candidate
            }
            probe--
        }
        return null
    }
    private fun findKotlinStdlibJars(): List<Path> {
        // 1. Check java.class.path (works when running as individual JARs)
        val classpath = System.getProperty("java.class.path") ?: ""
        val fromClasspath = classpath.split(File.pathSeparator)
            .filter { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
            .map { Paths.get(it) }
            .filter { it.toFile().exists() }

        if (fromClasspath.isNotEmpty()) {
            System.err.println("CompilerBridge: found ${fromClasspath.size} stdlib JARs on classpath")
            return fromClasspath
        }

        // 2. Search Gradle cache (when running as fat JAR, stdlib isn't on classpath)
        // Prefer version matching the compiler (2.1.20), fall back to any version
        val gradleCache = Paths.get(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1", "org.jetbrains.kotlin")
        if (gradleCache.toFile().exists()) {
            val preferred = findStdlibJarsInRepository(gradleCache, preferVersion = bundledKotlinVersion())
            if (preferred.isNotEmpty()) {
                System.err.println("CompilerBridge: found ${preferred.size} stdlib JARs (v${bundledKotlinVersion()}) in Gradle cache: $preferred")
                return preferred
            }

            // Fall back to any version (prefer newest)
            val found = findStdlibJarsInRepository(gradleCache)
            if (found.isNotEmpty()) {
                System.err.println("CompilerBridge: found ${found.size} stdlib JARs (fallback) in Gradle cache: $found")
                return found
            }
        }

        // 3. Search Maven local repo
        val m2Repo = Paths.get(System.getProperty("user.home"), ".m2", "repository", "org", "jetbrains", "kotlin")
        if (m2Repo.toFile().exists()) {
            val found = findStdlibJarsInRepository(m2Repo)
            if (found.isNotEmpty()) {
                System.err.println("CompilerBridge: found ${found.size} stdlib JARs in Maven local: $found")
                return found
            }
        }

        System.err.println("CompilerBridge: WARNING - no kotlin-stdlib JARs found!")
        return emptyList()
    }

    private fun findSourceRoots(projectRoot: Path): List<Path> {
        // Check conventional Kotlin/JVM source directories
        val candidates = listOf(
            projectRoot.resolve("src/main/kotlin"),
            projectRoot.resolve("src/main/java"),
            projectRoot.resolve("src"),
        )
        val found = candidates.filter { it.toFile().exists() }
        if (found.isNotEmpty()) {
            return found
        }

        // No conventional source dirs found. If the project root itself contains
        // .kt files (directly or in subdirectories), use it as a source root.
        // This handles simple projects, scripts, and non-standard layouts.
        val rootDir = projectRoot.toFile()
        if (rootDir.exists() && rootDir.isDirectory) {
            val hasKtFiles = rootDir.walk()
                .take(500) // limit scan depth for performance
                .any { it.extension == "kt" }
            if (hasKtFiles) {
                System.err.println("CompilerBridge: no conventional source dirs, using project root as source root")
                return listOf(projectRoot)
            }
        }

        return emptyList()
    }

    companion object {
        /**
         * Set of Kotlin keyword token types for semantic token classification.
         */
        private val KEYWORD_TOKENS = setOf(
            KtTokens.FUN_KEYWORD,
            KtTokens.VAL_KEYWORD,
            KtTokens.VAR_KEYWORD,
            KtTokens.CLASS_KEYWORD,
            KtTokens.INTERFACE_KEYWORD,
            KtTokens.OBJECT_KEYWORD,
            KtTokens.ENUM_KEYWORD,
            KtTokens.IF_KEYWORD,
            KtTokens.ELSE_KEYWORD,
            KtTokens.WHEN_KEYWORD,
            KtTokens.FOR_KEYWORD,
            KtTokens.WHILE_KEYWORD,
            KtTokens.DO_KEYWORD,
            KtTokens.RETURN_KEYWORD,
            KtTokens.BREAK_KEYWORD,
            KtTokens.CONTINUE_KEYWORD,
            KtTokens.THROW_KEYWORD,
            KtTokens.TRY_KEYWORD,
            KtTokens.CATCH_KEYWORD,
            KtTokens.FINALLY_KEYWORD,
            KtTokens.IS_KEYWORD,
            KtTokens.AS_KEYWORD,
            KtTokens.IN_KEYWORD,
            KtTokens.NULL_KEYWORD,
            KtTokens.TRUE_KEYWORD,
            KtTokens.FALSE_KEYWORD,
            KtTokens.THIS_KEYWORD,
            KtTokens.SUPER_KEYWORD,
            KtTokens.OVERRIDE_KEYWORD,
            KtTokens.ABSTRACT_KEYWORD,
            KtTokens.OPEN_KEYWORD,
            KtTokens.SEALED_KEYWORD,
            KtTokens.DATA_KEYWORD,
            KtTokens.INNER_KEYWORD,
            KtTokens.COMPANION_KEYWORD,
            KtTokens.PRIVATE_KEYWORD,
            KtTokens.PROTECTED_KEYWORD,
            KtTokens.INTERNAL_KEYWORD,
            KtTokens.PUBLIC_KEYWORD,
            KtTokens.SUSPEND_KEYWORD,
            KtTokens.INLINE_KEYWORD,
            KtTokens.CROSSINLINE_KEYWORD,
            KtTokens.NOINLINE_KEYWORD,
            KtTokens.REIFIED_KEYWORD,
            KtTokens.IMPORT_KEYWORD,
            KtTokens.PACKAGE_KEYWORD,
            KtTokens.OPERATOR_KEYWORD,
            KtTokens.INFIX_KEYWORD,
            KtTokens.TAILREC_KEYWORD,
            KtTokens.EXTERNAL_KEYWORD,
            KtTokens.ANNOTATION_KEYWORD,
            KtTokens.CONST_KEYWORD,
            KtTokens.LATEINIT_KEYWORD,
            KtTokens.TYPE_ALIAS_KEYWORD,
        )

        /**
         * Maps a -X compiler flag to a LanguageFeature enum value.
         */
        fun bundledKotlinVersion(): String = SidecarRuntime.kotlinVersion

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
