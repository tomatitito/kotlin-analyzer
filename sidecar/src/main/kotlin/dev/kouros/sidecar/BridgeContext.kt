package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal const val COMPLETION_DANGLING_FILE_RESOLUTION_MODE_NAME = "PREFER_SELF"
internal val COMPLETION_DANGLING_FILE_RESOLUTION_MODE: KaDanglingFileResolutionMode =
    KaDanglingFileResolutionMode.PREFER_SELF

internal const val PSI_VALIDATION_BYPASS_PROPERTY = "kotlin.analyzer.psi.validation.bypass"
internal const val PSI_VALIDATION_BYPASS_ENV = "KOTLIN_ANALYZER_PSI_VALIDATION_BYPASS"

internal data class CompletionFileSelection(
    val ktFile: KtFile?,
    val mode: String,
    val danglingResolutionMode: KaDanglingFileResolutionMode? = null,
    val fallbackReason: String? = null,
)

class BridgeContext {
    // --- State fields ---
    internal var session: StandaloneAnalysisAPISession? = null
    internal var sourceModule: KaModule? = null
    internal var disposable = Disposer.newDisposable("compiler-bridge")
    internal val virtualFiles = mutableMapOf<String, String>()
    internal val pebbleDocuments = PebbleDocumentStore()
    internal val pebbleIndex = PebbleTemplateIndex()
    internal val pebbleSpringIndex = PebbleSpringIndex(pebbleIndex)
    internal val symbolIndex = SymbolIndex()
    internal var classpathFqns = setOf<String>()
    internal var pebbleSpringIndexDirty = true
    internal var virtualFileTempDir: Path? = null
    internal val virtualFilesOnDisk = mutableSetOf<String>()
    internal val virtualFileDiskPaths = mutableMapOf<String, String>()
    internal val decompiledFileCache = mutableMapOf<String, Path>()
    internal var sessionDirty = false
    internal var newFilesPendingInSession = false
    internal val dirtyOnDiskFiles = mutableSetOf<String>()
    internal val shadowPathMapping = mutableMapOf<String, String>()
    internal var initProjectRoot = ""
    internal var initClasspath = emptyList<String>()
    internal var initCompilerFlags = emptyList<String>()
    internal var initJdkHome = ""
    internal var initSourceRoots = emptyList<String>()
    internal val lightFileCache = mutableMapOf<String, Pair<String, KtFile>>()
    internal var psiValidationBypassEnabled = true

    // --- Session lifecycle methods ---

    fun initialize(
        projectRoot: String,
        classpath: List<String>,
        compilerFlags: List<String>,
        jdkHome: String,
        sourceRoots: List<String>,
    ) {
        System.err.println("CompilerBridge: initializing session")
        val startTime = System.currentTimeMillis()

        initProjectRoot = projectRoot
        initClasspath = classpath
        initCompilerFlags = compilerFlags
        initJdkHome = jdkHome
        initSourceRoots = sourceRoots

        if (virtualFileTempDir == null) {
            virtualFileTempDir = Files.createTempDirectory("kotlin-analyzer-virtual")
            System.err.println("CompilerBridge: created virtual file temp dir: $virtualFileTempDir")
        }

        for ((uri, text) in virtualFiles) {
            writeVirtualFileToDisk(uri, text)
        }

        lightFileCache.clear()

        if (session != null) {
            Disposer.dispose(disposable)
            disposable = Disposer.newDisposable("compiler-bridge")
        }

        val stdlibJars = findKotlinStdlibJars()

        val languageFeatures = mutableMapOf<LanguageFeature, LanguageFeature.State>()
        for (flag in compilerFlags) {
            CompilerBridgeRuntime.mapCompilerFlag(flag)?.let { feature ->
                languageFeatures[feature] = LanguageFeature.State.ENABLED
            }
        }
        val resolvedLanguageVersion = CompilerBridgeRuntime.resolveLanguageVersion(compilerFlags)
        val resolvedApiVersion = CompilerBridgeRuntime.resolveApiVersion(compilerFlags, resolvedLanguageVersion)
        System.err.println("CompilerBridge: languageVersion=$resolvedLanguageVersion, apiVersion=$resolvedApiVersion")
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

        if (classpath.isEmpty()) {
            System.err.println("CompilerBridge: WARNING — classpath is empty, external dependency resolution will fail")
        }

        classpath.filter { "spring" in it.lowercase() }.forEach {
            System.err.println("CompilerBridge: spring JAR on classpath: $it")
        }
        classpath.filter { "arrow" in it.lowercase() }.forEach {
            System.err.println("CompilerBridge: arrow JAR on classpath: $it")
        }
        System.err.println("CompilerBridge: stdlibJars=${stdlibJars.size} jars")
        System.err.println("CompilerBridge: jdkHome=$jdkHome")

        try {
            session = buildStandaloneAnalysisAPISession(disposable) {
                buildKtModuleProvider {
                    platform = JvmPlatforms.defaultJvmPlatform

                    val jdkModule = if (jdkHome.isNotEmpty()) {
                        buildKtSdkModule {
                            addBinaryRootsFromJdkHome(Paths.get(jdkHome), isJre = false)
                            libraryName = "JDK"
                            platform = JvmPlatforms.defaultJvmPlatform
                        }
                    } else {
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

                    val stdlibModule = if (stdlibJars.isNotEmpty()) {
                        buildKtLibraryModule {
                            libraryName = "kotlin-stdlib"
                            for (jar in stdlibJars) {
                                addBinaryRoot(jar)
                            }
                            platform = JvmPlatforms.defaultJvmPlatform
                        }
                    } else null

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

                    val mainModule = buildKtSourceModule {
                        moduleName = "main"
                        platform = JvmPlatforms.defaultJvmPlatform

                        for (root in effectiveSourceRoots) {
                            addSourceRoot(root)
                        }

                        languageVersionSettings = LanguageVersionSettingsImpl(
                            languageVersion = resolvedLanguageVersion,
                            apiVersion = resolvedApiVersion,
                            specificFeatures = languageFeatures,
                        )

                        if (jdkModule != null) addRegularDependency(jdkModule)
                        if (stdlibModule != null) addRegularDependency(stdlibModule)
                        if (classpathModule != null) addRegularDependency(classpathModule)
                    }
                    sourceModule = mainModule
                    addModule(mainModule)
                }
            }
        } catch (e: Exception) {
            sessionDirty = true
            System.err.println("CompilerBridge: session build failed, will retry on next request: ${e.message}")
            throw e
        }

        sessionDirty = false
        newFilesPendingInSession = false

        registerVirtualFileModuleProvider()
        applyPsiValidationBypassPolicy()

        val elapsed = System.currentTimeMillis() - startTime
        System.err.println("CompilerBridge: session created in ${elapsed}ms")

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

        symbolIndex.rebuildFromSession(session!!)
        System.err.println("CompilerBridge: symbol index built with ${symbolIndex.size()} declarations")

        classpathFqns = buildClasspathFqnIndex(classpath + stdlibJars.map(Path::toString))
        System.err.println("CompilerBridge: classpath FQN index built with ${classpathFqns.size} entries")

        pebbleSpringIndexDirty = true
    }

    fun shutdown() {
        try {
            if (session != null) {
                Disposer.dispose(disposable)
                session = null
                sourceModule = null
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: shutdown error: ${e.message}")
        }
    }

    fun updateFile(uri: String, text: String) {
        virtualFiles[uri] = text
        updateFileInSession(uri, text)
        pebbleSpringIndexDirty = true

        val filePath = uriToPath(uri)
        val onDiskFile = File(filePath)
        if (onDiskFile.exists()) {
            if (uri in dirtyOnDiskFiles) {
                sessionDirty = true
            } else {
                try {
                    val onDiskContent = onDiskFile.readText()
                    if (text != onDiskContent) {
                        dirtyOnDiskFiles.add(uri)
                        sessionDirty = true
                        System.err.println("CompilerBridge: updateFile($uri) — on-disk file content changed, session marked dirty")
                    }
                } catch (e: Exception) {
                    dirtyOnDiskFiles.add(uri)
                    sessionDirty = true
                }
            }
        }

        val wasOnDisk = uri in virtualFilesOnDisk
        writeVirtualFileToDisk(uri, text)
        val nowOnDisk = uri in virtualFilesOnDisk
        if (nowOnDisk && !wasOnDisk) {
            sessionDirty = true
            newFilesPendingInSession = true
            System.err.println("CompilerBridge: updateFile($uri) — new virtual file in temp dir, session marked dirty")
        } else if (nowOnDisk && wasOnDisk) {
            sessionDirty = true
        }

        val currentSession = session
        if (currentSession != null) {
            try {
                val fp = uriToPath(uri)
                val fileName = fp.substringAfterLast('/')
                val psiFactory = KtPsiFactory(currentSession.project)
                val ktFile = psiFactory.createFile(fileName, text)
                symbolIndex.indexFile(uri, ktFile)
            } catch (e: Exception) {
                System.err.println("CompilerBridge: failed to re-index $uri: ${e.message}")
            }
        }
    }

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

    internal fun ensureSessionCurrent() {
        if (!sessionDirty || session == null) return
        System.err.println("CompilerBridge: session dirty, rebuilding to pick up virtual file changes")
        initialize(initProjectRoot, initClasspath, initCompilerFlags, initJdkHome, initSourceRoots)
    }

    internal fun ensurePebbleSpringIndexCurrent() {
        if (!pebbleSpringIndexDirty) return
        ensureSessionCurrent()
        val currentSession = session ?: return
        val kotlinFiles = linkedMapOf<String, KtFile>()
        for ((_, files) in currentSession.modulesWithFiles) {
            for (ktFile in files.filterIsInstance<KtFile>()) {
                kotlinFiles[buildFileUri(ktFile.virtualFile.path)] = ktFile
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

    // --- URI / path conversion ---

    internal fun uriToPath(uri: String): String {
        return if (uri.startsWith("file://")) {
            uri.removePrefix("file://")
        } else {
            uri
        }
    }

    internal fun buildFileUri(vfPath: String): String {
        val originalPath = shadowPathMapping[vfPath] ?: vfPath
        return virtualFiles.keys.firstOrNull { uriToPath(it) == originalPath }
            ?: "file://$originalPath"
    }

    // --- Virtual file accessors ---

    internal fun getVirtualFileEntry(uri: String): Pair<String, String>? {
        virtualFiles[uri]?.let { return uri to it }
        val path = uriToPath(uri)
        return virtualFiles.entries.firstOrNull { uriToPath(it.key) == path }?.let { it.key to it.value }
    }

    internal fun getVirtualFileContent(uri: String): String? = getVirtualFileEntry(uri)?.second

    internal fun hasVirtualFile(uri: String): Boolean = getVirtualFileContent(uri) != null

    // --- File lookup methods ---

    internal fun findKtFileInSession(session: StandaloneAnalysisAPISession, uri: String): KtFile? {
        val filePath = uriToPath(uri)
        return session.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()
            .find {
                val path = it.virtualFile.path
                path == filePath || it.name == filePath || shadowPathMapping[path] == filePath
            }
    }

    internal fun selectKtFileForCompletion(
        session: StandaloneAnalysisAPISession,
        uri: String,
        metrics: CompletionMetrics? = null,
    ): CompletionFileSelection {
        val sessionFile = findKtFileInSession(session, uri)
        val virtualEntry = getVirtualFileEntry(uri)
        val virtualContent = virtualEntry?.second

        if (sessionFile == null) {
            return CompletionFileSelection(
                ktFile = findKtFile(session, uri),
                mode = if (virtualContent == null) {
                    "missing_or_no_session"
                } else {
                    "new_virtual_source_not_in_session"
                },
            )
        }

        if (virtualContent == null || sessionFile.text == virtualContent) {
            return CompletionFileSelection(
                ktFile = sessionFile,
                mode = "existing_session_source_without_editor_overlay",
            )
        }

        return try {
            val danglingFile = metrics?.stage("dangling_file_creation") {
                createCompletionDanglingFile(session, uri, virtualContent, sessionFile)
            } ?: createCompletionDanglingFile(session, uri, virtualContent, sessionFile)

            CompletionFileSelection(
                ktFile = danglingFile,
                mode = "existing_session_source_with_editor_overlay",
                danglingResolutionMode = COMPLETION_DANGLING_FILE_RESOLUTION_MODE,
            )
        } catch (e: Throwable) {
            CompletionFileSelection(
                ktFile = findKtFile(session, uri),
                mode = "fallback_light_virtual_file",
                fallbackReason = "dangling-create-failed:${e.javaClass.name}:${e.message ?: ""}",
            )
        }
    }

    private fun createCompletionDanglingFile(
        session: StandaloneAnalysisAPISession,
        uri: String,
        virtualContent: String,
        sessionFile: KtFile,
    ): KtFile {
        val fileName = uriToPath(uri).substringAfterLast('/')
        val danglingFile = KtPsiFactory(session.project).createFile(fileName, virtualContent)
        (danglingFile as? PsiFileImpl)?.setOriginalFile(sessionFile)
        moduleForSessionFile(session, sessionFile)?.let { danglingFile.contextModule = it }
        return danglingFile
    }

    private fun moduleForSessionFile(session: StandaloneAnalysisAPISession, ktFile: KtFile): KaModule? {
        return session.modulesWithFiles.entries
            .firstOrNull { (_, files) -> files.any { it === ktFile } }
            ?.key
            ?: sourceModule
    }

    internal fun findKtFile(session: StandaloneAnalysisAPISession, uri: String): KtFile? {
        val filePath = uriToPath(uri)

        val allSessionFiles = session.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()

        System.err.println("CompilerBridge: findKtFile($uri) — resolved path=$filePath, session has ${allSessionFiles.size} file(s)")

        val virtualEntry = getVirtualFileEntry(uri)
        val virtualContent = virtualEntry?.second

        fun matchesPath(ktFile: KtFile): Boolean {
            val path = ktFile.virtualFile.path
            return path == filePath || ktFile.name == filePath ||
                shadowPathMapping[path] == filePath
        }

        if (virtualContent == null) {
            val sessionFile = allSessionFiles.find { matchesPath(it) }
            if (sessionFile != null) {
                System.err.println("CompilerBridge: findKtFile($uri) — FOUND in session: ${sessionFile.virtualFile.path}")
                return sessionFile
            }
        } else {
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

    internal fun allSessionKtFiles(): List<KtFile> {
        val currentSession = session ?: return emptyList()
        return currentSession.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()
    }

    internal fun allVirtualKtFiles(): List<KtFile> {
        val currentSession = session ?: return emptyList()
        return virtualFiles.mapNotNull { (uri, text) ->
            try {
                val fileName = uriToPath(uri).substringAfterLast('/')
                val lightVf = com.intellij.testFramework.LightVirtualFile(fileName, text)
                com.intellij.psi.PsiManager.getInstance(currentSession.project).findFile(lightVf) as? KtFile
            } catch (_: Exception) {
                null
            }
        }
    }

    internal fun fileUriForKtFile(ktFile: KtFile): String? {
        val path = ktFile.virtualFile?.path ?: return null
        return buildFileUri(path)
    }

    // --- Offset / position conversion ---

    internal fun lineColToOffset(ktFile: KtFile, line: Int, character: Int): Int? {
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

    internal fun offsetToLineCol(
        document: com.intellij.openapi.editor.Document,
        offset: Int,
    ): Pair<Int, Int> {
        val lineIndex = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineIndex)
        return Pair(lineIndex + 1, offset - lineStart)
    }

    internal fun selectionForOffset(
        document: com.intellij.openapi.editor.Document,
        offset: Int,
        length: Int = 0,
    ): JsonObject {
        val startLineIndex = document.getLineNumber(offset)
        val startLineStart = document.getLineStartOffset(startLineIndex)
        val endOffset = offset + length
        val endLineIndex = document.getLineNumber(endOffset.coerceAtMost(document.textLength))
        val endLineStart = document.getLineStartOffset(endLineIndex)
        return JsonObject().apply {
            addProperty(AnalyzerCommands.ARG_START_LINE, startLineIndex)
            addProperty(AnalyzerCommands.ARG_START_CHARACTER, offset - startLineStart)
            addProperty(AnalyzerCommands.ARG_END_LINE, endLineIndex)
            addProperty(AnalyzerCommands.ARG_END_CHARACTER, endOffset - endLineStart)
        }
    }

    // --- PSI helpers ---

    internal fun findHoverElement(ktFile: KtFile, line: Int, offset: Int): PsiElement? {
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

    internal fun findTargetDeclaration(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null && current !is KtFile) {
            if (current is KtNamedDeclaration) {
                return current
            }
            if (current is KtReferenceExpression) {
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

    internal fun findEnclosingDeclaration(element: PsiElement?): KtDeclaration? {
        var current: PsiElement? = element
        while (current != null && current !is KtFile) {
            if (current is KtDeclaration && current !is KtParameter) {
                return current
            }
            current = current.parent
        }
        return null
    }

    internal fun extractKDocText(declaration: KtDeclaration): String? {
        val kdoc = declaration.docComment ?: return null
        val defaultSection = kdoc.getDefaultSection()
        val content = defaultSection.getContent().trim()

        if (content.isEmpty()) return null

        val extraSections = StringBuilder()

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

    // --- Import helpers (delegating to ImportEditHelper top-level functions) ---

    internal fun findImportCandidates(ktFile: KtFile, shortName: String): List<ImportCandidate> =
        findImportCandidates(ktFile, shortName, symbolIndex, classpathFqns)

    internal fun planTypeImportAssist(
        ktFile: KtFile,
        factoryName: String?,
        startOffset: Int,
        endOffset: Int,
    ): TypeImportAssistPlan? =
        planTypeImportAssist(ktFile, factoryName, startOffset, endOffset, symbolIndex, classpathFqns)

    // --- Virtual file management ---

    internal fun writeVirtualFileToDisk(uri: String, text: String) {
        val tempDir = virtualFileTempDir ?: return

        val filePath = uriToPath(uri)
        if (File(filePath).exists()) {
            System.err.println("CompilerBridge: skipping temp dir write for $uri — file exists on disk")
            return
        }

        val packageName = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1) ?: ""
        val packageDirs = packageName.replace('.', '/')

        val fileName = filePath.substringAfterLast('/')

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

    internal fun writeDecompiledFile(vfPath: String, text: String): Path? {
        val tempDir = virtualFileTempDir ?: return null
        decompiledFileCache[vfPath]?.let { return it }

        val internalPath = if (vfPath.contains("!/")) {
            vfPath.substringAfter("!/")
        } else {
            vfPath.substringAfterLast("/")
        }

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

    internal fun buildShadowSourceRoots(baseSourceRoots: List<Path>): List<Path> {
        val tempDir = virtualFileTempDir ?: return baseSourceRoots
        val shadowRoot = tempDir.resolve("shadow")

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

                    shadowPathMapping[target.toString()] = file.absolutePath
                }

            shadowRoots.add(shadowDir)
        }

        System.err.println("CompilerBridge: built shadow source tree with ${shadowPathMapping.size} files across ${shadowRoots.size} root(s)")
        return shadowRoots
    }

    internal fun updateFileInSession(uri: String, text: String) {
        System.err.println("CompilerBridge: updateFile($uri) — stored in virtualFiles (${text.length} chars)")
    }

    // The standalone Analysis API rejects legacy LightVirtualFile-backed PSI
    // through KaBaseIllegalPsiExceptionKt.checkValidity(). Keep the legacy bypass
    // on by default for non-completion paths, but allow tests and sidecar runs to
    // force validation back on while dangling completion is exercised.
    private fun applyPsiValidationBypassPolicy() {
        psiValidationBypassEnabled = psiValidationBypassRequested()
        try {
            com.intellij.openapi.util.registry.Registry
                .get("kotlin.analysis.validate.psi.input").setValue(!psiValidationBypassEnabled)
        } catch (_: Exception) {}
        try {
            val field = Class.forName(
                "org.jetbrains.kotlin.analysis.api.impl.base.components.KaBaseIllegalPsiExceptionKt"
            ).getDeclaredField("allowIllegalPsiAccess")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as ThreadLocal<Boolean>).set(psiValidationBypassEnabled)
        } catch (e: Exception) {
            System.err.println("CompilerBridge: could not apply PSI validation policy: ${e.message}")
        }
        System.err.println(
            "CompilerBridge: PSI validation bypass ${if (psiValidationBypassEnabled) "enabled" else "disabled"}"
        )
    }

    private fun psiValidationBypassRequested(): Boolean {
        val configured = System.getProperty(PSI_VALIDATION_BYPASS_PROPERTY)
            ?: System.getenv(PSI_VALIDATION_BYPASS_ENV)
            ?: return true
        return configured.lowercase() in setOf("1", "true", "yes", "on")
    }

    // --- Session infrastructure ---

    internal fun registerVirtualFileModuleProvider() {
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

    internal fun findKotlinStdlibJars(): List<Path> {
        val classpath = System.getProperty("java.class.path") ?: ""
        val fromClasspath = classpath.split(File.pathSeparator)
            .filter { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
            .map { Paths.get(it) }
            .filter { it.toFile().exists() }

        if (fromClasspath.isNotEmpty()) {
            System.err.println("CompilerBridge: found ${fromClasspath.size} stdlib JARs on classpath")
            return fromClasspath
        }

        val gradleCache = Paths.get(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1", "org.jetbrains.kotlin")
        if (gradleCache.toFile().exists()) {
            val preferred = CompilerBridgeRuntime.findStdlibJarsInRepository(gradleCache, preferVersion = CompilerBridgeRuntime.bundledKotlinVersion())
            if (preferred.isNotEmpty()) {
                System.err.println("CompilerBridge: found ${preferred.size} stdlib JARs (v${CompilerBridgeRuntime.bundledKotlinVersion()}) in Gradle cache: $preferred")
                return preferred
            }

            val found = CompilerBridgeRuntime.findStdlibJarsInRepository(gradleCache)
            if (found.isNotEmpty()) {
                System.err.println("CompilerBridge: found ${found.size} stdlib JARs (fallback) in Gradle cache: $found")
                return found
            }
        }

        val m2Repo = Paths.get(System.getProperty("user.home"), ".m2", "repository", "org", "jetbrains", "kotlin")
        if (m2Repo.toFile().exists()) {
            val found = CompilerBridgeRuntime.findStdlibJarsInRepository(m2Repo)
            if (found.isNotEmpty()) {
                System.err.println("CompilerBridge: found ${found.size} stdlib JARs in Maven local: $found")
                return found
            }
        }

        System.err.println("CompilerBridge: WARNING - no kotlin-stdlib JARs found!")
        return emptyList()
    }

    internal fun findSourceRoots(projectRoot: Path): List<Path> {
        val candidates = listOf(
            projectRoot.resolve("src/main/kotlin"),
            projectRoot.resolve("src/main/java"),
            projectRoot.resolve("src"),
        )
        val found = candidates.filter { it.toFile().exists() }
        if (found.isNotEmpty()) {
            return found
        }

        val rootDir = projectRoot.toFile()
        if (rootDir.exists() && rootDir.isDirectory) {
            val hasKtFiles = rootDir.walk()
                .take(500)
                .any { it.extension == "kt" }
            if (hasKtFiles) {
                System.err.println("CompilerBridge: no conventional source dirs, using project root as source root")
                return listOf(projectRoot)
            }
        }

        return emptyList()
    }

    internal fun buildClasspathFqnIndex(jars: List<String>): Set<String> {
        val fqns = mutableSetOf<String>()
        for (entry in jars) {
            val file = File(entry)
            if (!file.isFile || file.extension.lowercase() != "jar") continue
            try {
                java.util.jar.JarFile(file).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val jarEntry = entries.nextElement()
                        val name = jarEntry.name
                        if (!name.endsWith(".class")) continue
                        if (name.startsWith("META-INF/")) continue
                        val className = name.removeSuffix(".class")
                        if (className.endsWith("/package-info") || className == "module-info") continue
                        val relativeName = className.substringAfterLast('/')
                        if ('$' in relativeName) continue
                        val fqn = className.replace('/', '.')
                        if (fqn.isBlank()) continue
                        val shortName = fqn.substringAfterLast('.')
                        if (shortName.isEmpty() || !shortName.first().isJavaIdentifierStart()) continue
                        fqns.add(fqn)
                    }
                }
            } catch (_: Exception) {
                // Ignore unreadable jars
            }
        }
        return fqns
    }

    // --- Spring model helpers ---

    internal fun findSpringModelProducerDeclaration(element: PsiElement): PsiElement? {
        val call = PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java, false) ?: return null
        val calleeName = call.calleeExpression?.text ?: return null
        if (!pebbleSpringIndex.isSupportedModelAttributeCall(calleeName)) return null

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
}
