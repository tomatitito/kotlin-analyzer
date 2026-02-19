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
import org.jetbrains.kotlin.types.Variance
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

        System.err.println("CompilerBridge: projectRoot=$projectRoot")
        System.err.println("CompilerBridge: sourceRoots=$sourceRoots")
        System.err.println("CompilerBridge: effectiveSourceRoots=$effectiveSourceRoots")
        System.err.println("CompilerBridge: classpath=${classpath.size} entries")
        System.err.println("CompilerBridge: stdlibJars=${stdlibJars.size} jars")
        System.err.println("CompilerBridge: jdkHome=$jdkHome")

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
     * Returns type/signature information with KDoc documentation when available.
     */
    fun hover(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()

        val currentSession = session ?: return result
        val ktFile = findKtFile(currentSession, uri) ?: return result

        try {
            analyze(ktFile) {
                val offset = lineColToOffset(ktFile, line, character) ?: return@analyze
                val element = ktFile.findElementAt(offset) ?: return@analyze

                // Walk up to find the nearest meaningful element
                var current: PsiElement? = element
                while (current != null) {
                    if (current is KtNamedDeclaration) {
                        val hoverText = buildDeclarationHover(current)
                        if (hoverText != null) {
                            result.addProperty("contents", hoverText)
                            return@analyze
                        }
                    }

                    if (current is KtReferenceExpression) {
                        // Try to resolve the reference to get declaration info
                        val hoverText = buildReferenceHover(current)
                        if (hoverText != null) {
                            result.addProperty("contents", hoverText)
                            return@analyze
                        }
                    }

                    if (current is KtExpression) {
                        try {
                            val type = current.expressionType
                            if (type != null) {
                                val rendered = type.render(
                                    KaTypeRendererForSource.WITH_SHORT_NAMES,
                                    Variance.INVARIANT
                                )
                                result.addProperty("contents", "```kotlin\n$rendered\n```")
                                return@analyze
                            }
                        } catch (_: Exception) {
                            // expressionType may not be available for all expressions
                        }
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
     * Supports both scope-based completions and dot-member completions.
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
                }
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: completion failed: ${e.message}")
        }

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
                    var current: PsiElement? = element
                    while (current != null) {
                        if (current is KtReferenceExpression) {
                            val references = current.references
                            for (ref in references) {
                                try {
                                    val resolved = ref.resolve()
                                    if (resolved != null) {
                                        val file = resolved.containingFile
                                        val textOffset = resolved.textOffset
                                        val doc = file?.viewProvider?.document
                                        val vfPath = file?.virtualFile?.path

                                        if (file != null && doc != null) {
                                            // For KtPsiFactory files, virtualFile.path may be null;
                                            // fall back to the original URI (same-file reference)
                                            val targetUri = if (vfPath != null && !vfPath.contains("dummy")) {
                                                "file://$vfPath"
                                            } else {
                                                uri
                                            }
                                            val loc = JsonObject()
                                            loc.addProperty("uri", targetUri)
                                            loc.addProperty("line", doc.getLineNumber(textOffset) + 1)
                                            loc.addProperty("column", textOffset - doc.getLineStartOffset(doc.getLineNumber(textOffset)))
                                            locationsArray.add(loc)
                                        }
                                    }
                                } catch (e: Exception) {
                                    System.err.println("CompilerBridge: definition ref.resolve() failed: ${e.message?.take(100)}")
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
     * Provides find-references locations within the same file.
     * In standalone mode without full indexing, we search for references
     * to the target declaration by walking the PSI tree of the containing file.
     */
    fun references(uri: String, line: Int, character: Int): JsonObject {
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

                // Find the declaration at the cursor position
                val element = ktFile.findElementAt(offset) ?: run {
                    result.add("locations", locationsArray)
                    return@analyze
                }

                val targetDeclaration = findTargetDeclaration(element)
                if (targetDeclaration == null) {
                    result.add("locations", locationsArray)
                    return@analyze
                }

                val document = ktFile.viewProvider.document
                if (document == null) {
                    result.add("locations", locationsArray)
                    return@analyze
                }

                // Walk the entire file's PSI tree looking for references to the target
                val allReferences = PsiTreeUtil.collectElementsOfType(
                    ktFile, KtSimpleNameExpression::class.java
                )

                for (nameExpr in allReferences) {
                    try {
                        for (ref in nameExpr.references) {
                            val resolved = ref.resolve()
                            if (resolved != null && resolved == targetDeclaration) {
                                val refOffset = nameExpr.textOffset
                                val refLine = document.getLineNumber(refOffset) + 1
                                val refLineStart = document.getLineStartOffset(
                                    document.getLineNumber(refOffset)
                                )
                                val refCol = refOffset - refLineStart

                                val loc = JsonObject()
                                loc.addProperty("uri", "file://${ktFile.virtualFile.path}")
                                loc.addProperty("line", refLine)
                                loc.addProperty("column", refCol)
                                locationsArray.add(loc)
                            }
                        }
                    } catch (_: Exception) {
                        // Skip references that fail to resolve
                    }
                }

                // Also include the declaration itself
                val declOffset = targetDeclaration.textOffset
                val declLine = document.getLineNumber(declOffset) + 1
                val declLineStart = document.getLineStartOffset(
                    document.getLineNumber(declOffset)
                )
                val declCol = declOffset - declLineStart

                val declLoc = JsonObject()
                declLoc.addProperty("uri", "file://${ktFile.virtualFile.path}")
                declLoc.addProperty("line", declLine)
                declLoc.addProperty("column", declCol)

                // Add declaration location if not already present
                val alreadyIncluded = (0 until locationsArray.size()).any { i ->
                    val loc = locationsArray[i].asJsonObject
                    loc.get("line")?.asInt == declLine && loc.get("column")?.asInt == declCol
                }
                if (!alreadyIncluded) {
                    locationsArray.add(declLoc)
                }
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: references failed: ${e.message}")
        }

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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: signatureHelp failed: ${e.message}")
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

                val fileUri = "file://${ktFile.virtualFile.path}"

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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: rename failed: ${e.message}")
        }

        result.add("edits", editsArray)
        return result
    }

    /**
     * Provides code actions at the given position.
     * Suggests quick-fixes based on diagnostics at the cursor location,
     * including "Suppress warning" and "Add import" actions.
     */
    fun codeActions(uri: String, line: Int, character: Int): JsonObject {
        val result = JsonObject()
        val actionsArray = JsonArray()

        val currentSession = session ?: run {
            result.add("actions", actionsArray)
            return result
        }
        val ktFile = findKtFile(currentSession, uri) ?: run {
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

                val fileUri = "file://${ktFile.virtualFile.path}"

                // Collect diagnostics that overlap with the cursor position
                val diagnostics = ktFile.collectDiagnostics(
                    KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
                )

                for (diagnostic in diagnostics) {
                    val textRange = diagnostic.textRanges.firstOrNull() ?: continue

                    // Check if the cursor is within this diagnostic's range
                    if (offset < textRange.startOffset || offset > textRange.endOffset) continue

                    val factoryName = diagnostic.factoryName ?: continue
                    val severityName = diagnostic.severity.name

                    // Suppress warning action for warnings
                    if (severityName == "WARNING") {
                        val action = JsonObject()
                        action.addProperty("title", "Suppress warning '$factoryName'")
                        action.addProperty("kind", "quickfix")

                        val actionEdits = JsonArray()

                        // Find the enclosing declaration to add @Suppress annotation
                        val element = ktFile.findElementAt(textRange.startOffset)
                        val enclosingDeclaration = findEnclosingDeclaration(element)

                        if (enclosingDeclaration != null) {
                            val declOffset = enclosingDeclaration.textOffset
                            val declLine = document.getLineNumber(declOffset) + 1
                            val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
                            val declCol = declOffset - declLineStart

                            // Determine indentation of the declaration
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

                        val action = JsonObject()
                        action.addProperty("title", "Add import for '$unresolvedText'")
                        action.addProperty("kind", "quickfix")

                        val actionEdits = JsonArray()

                        // Find the insertion point for imports (after package statement, before first declaration)
                        val importInsertLine = findImportInsertLine(ktFile, document)

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
            System.err.println("CompilerBridge: codeActions failed: ${e.message}")
        }

        result.add("actions", actionsArray)
        return result
    }

    /**
     * Provides workspace-wide symbol search.
     * Walks all KtFiles in the session and collects declarations matching the query.
     * Results are limited to 100 symbols.
     */
    fun workspaceSymbols(query: String): JsonObject {
        val result = JsonObject()
        val symbolsArray = JsonArray()

        val currentSession = session ?: run {
            result.add("symbols", symbolsArray)
            return result
        }

        try {
            val allKtFiles = currentSession.modulesWithFiles.entries
                .flatMap { (_, files) -> files }
                .filterIsInstance<KtFile>()

            val lowerQuery = query.lowercase()
            var count = 0

            for (ktFile in allKtFiles) {
                if (count >= 100) break

                val declarations = PsiTreeUtil.collectElementsOfType(
                    ktFile, KtNamedDeclaration::class.java
                )

                for (declaration in declarations) {
                    if (count >= 100) break

                    val name = declaration.name ?: continue

                    // Case-insensitive substring match
                    if (lowerQuery.isNotEmpty() && !name.lowercase().contains(lowerQuery)) continue

                    val kind = when (declaration) {
                        is KtClassOrObject -> when {
                            declaration is KtObjectDeclaration -> "object"
                            declaration is KtEnumEntry -> "enumMember"
                            (declaration as? KtClass)?.isInterface() == true -> "interface"
                            (declaration as? KtClass)?.isEnum() == true -> "enum"
                            else -> "class"
                        }
                        is KtNamedFunction -> "function"
                        is KtProperty -> "property"
                        is KtTypeAlias -> "typeAlias"
                        else -> continue // Skip other declaration types (parameters, etc.)
                    }

                    val document = ktFile.viewProvider.document ?: continue
                    val declOffset = declaration.textOffset
                    val declLine = document.getLineNumber(declOffset) + 1
                    val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
                    val declCol = declOffset - declLineStart

                    val symbolObj = JsonObject()
                    symbolObj.addProperty("name", name)
                    symbolObj.addProperty("kind", kind)
                    symbolObj.addProperty("uri", "file://${ktFile.virtualFile.path}")
                    symbolObj.addProperty("line", declLine)
                    symbolObj.addProperty("column", declCol)
                    symbolsArray.add(symbolObj)
                    count++
                }
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: workspaceSymbols failed: ${e.message}")
        }

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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: inlayHints failed: ${e.message}")
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

                val fileUri = "file://${ktFile.virtualFile.path}"

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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: codeLens failed: ${e.message}")
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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: semanticTokens failed: ${e.message}")
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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: callHierarchyPrepare failed: ${e.message}")
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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: callHierarchyIncoming failed: ${e.message}")
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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: typeHierarchyPrepare failed: ${e.message}")
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
        } catch (e: Exception) {
            System.err.println("CompilerBridge: typeHierarchySupertypes failed: ${e.message}")
        }

        result.add("supertypes", supertypesArray)
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

    // --- Private helpers: hover ---

    /**
     * Builds hover text for a named declaration, including its rendered signature and KDoc.
     * Must be called inside an `analyze` block.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildDeclarationHover(
        declaration: KtNamedDeclaration,
    ): String? {
        val symbol = declaration.symbol
        val rendered = try {
            symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
        } catch (_: Exception) {
            // Fallback to PSI text if rendering fails
            val text = declaration.text?.take(300) ?: return null
            text.lines().first()
        }

        val kdocText = extractKDocText(declaration)

        return buildString {
            append("```kotlin\n")
            append(rendered)
            append("\n```")
            if (kdocText != null) {
                append("\n\n---\n\n")
                append(kdocText)
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
                val symbol = ref.resolveToSymbol()
                if (symbol is KaDeclarationSymbol) {
                    val rendered = try {
                        symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
                    } catch (_: Exception) {
                        symbol.toString()
                    }

                    // Try to get KDoc from the PSI
                    val psi = symbol.psi
                    val kdocText = if (psi is KtDeclaration) extractKDocText(psi) else null

                    return buildString {
                        append("```kotlin\n")
                        append(rendered)
                        append("\n```")
                        if (kdocText != null) {
                            append("\n\n---\n\n")
                            append(kdocText)
                        }
                    }
                }
            }
        }

        // Fallback: try expression type
        if (refExpr is KtExpression) {
            val type = refExpr.expressionType
            if (type != null) {
                val rendered = type.render(
                    KaTypeRendererForSource.WITH_SHORT_NAMES,
                    Variance.INVARIANT
                )
                return "```kotlin\n$rendered\n```"
            }
        }

        return null
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
                if (dotNode != null && offset > dotNode.startOffset) {
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

        // Also collect tagged sections
        val allSections = kdoc.getAllSections()
        val extraSections = StringBuilder()
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

        return content + extraSections.toString()
    }

    private fun findKtFile(session: StandaloneAnalysisAPISession, uri: String): KtFile? {
        val filePath = uriToPath(uri)

        // First, look for the file in the session's discovered source files
        val sessionFile = session.modulesWithFiles.entries
            .flatMap { (_, files) -> files }
            .filterIsInstance<KtFile>()
            .find { it.virtualFile.path == filePath || it.name == filePath }

        if (sessionFile != null) {
            return sessionFile
        }

        // If not found in session, try to create a KtFile from virtualFiles content
        val content = virtualFiles[uri]
        if (content != null) {
            System.err.println("CompilerBridge: file not in session, creating from virtualFiles: $uri")
            try {
                val fileName = filePath.substringAfterLast('/')
                val psiFactory = KtPsiFactory(session.project)
                return psiFactory.createFile(fileName, content)
            } catch (e: Exception) {
                System.err.println("CompilerBridge: failed to create KtFile from virtualFiles: ${e.message}")
            }
        }

        // Last resort: try to read from disk if the file exists
        val file = File(filePath)
        if (file.exists() && file.extension == "kt") {
            System.err.println("CompilerBridge: file not in session, creating from disk: $uri")
            try {
                val psiFactory = KtPsiFactory(session.project)
                return psiFactory.createFile(file.name, file.readText())
            } catch (e: Exception) {
                System.err.println("CompilerBridge: failed to create KtFile from disk: ${e.message}")
            }
        }

        System.err.println("CompilerBridge: file not found anywhere: $uri (path=$filePath)")
        return null
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
            val stdlibNames = listOf("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")
            val preferredVersion = "2.1.20"

            // Try preferred version first
            val preferred = mutableListOf<Path>()
            for (name in stdlibNames) {
                val versionDir = gradleCache.resolve(name).resolve(preferredVersion).toFile()
                if (!versionDir.exists()) continue
                val jars = versionDir.walkTopDown()
                    .filter { it.extension == "jar" && !it.name.contains("-sources") && !it.name.contains("-javadoc") }
                    .toList()
                if (jars.isNotEmpty()) {
                    preferred.add(jars.first().toPath())
                }
            }
            if (preferred.isNotEmpty()) {
                System.err.println("CompilerBridge: found ${preferred.size} stdlib JARs (v$preferredVersion) in Gradle cache: $preferred")
                return preferred
            }

            // Fall back to any version (prefer newest)
            val found = mutableListOf<Path>()
            for (name in stdlibNames) {
                val moduleDir = gradleCache.resolve(name).toFile()
                if (!moduleDir.exists()) continue
                val versionDirs = moduleDir.listFiles()?.filter { it.isDirectory }?.sortedDescending() ?: continue
                for (versionDir in versionDirs) {
                    val jars = versionDir.walkTopDown()
                        .filter { it.extension == "jar" && !it.name.contains("-sources") && !it.name.contains("-javadoc") }
                        .toList()
                    if (jars.isNotEmpty()) {
                        found.add(jars.first().toPath())
                        break
                    }
                }
            }
            if (found.isNotEmpty()) {
                System.err.println("CompilerBridge: found ${found.size} stdlib JARs (fallback) in Gradle cache: $found")
                return found
            }
        }

        // 3. Search Maven local repo
        val m2Repo = Paths.get(System.getProperty("user.home"), ".m2", "repository", "org", "jetbrains", "kotlin")
        if (m2Repo.toFile().exists()) {
            val stdlibNames = listOf("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")
            val found = mutableListOf<Path>()

            for (name in stdlibNames) {
                val moduleDir = m2Repo.resolve(name).toFile()
                if (!moduleDir.exists()) continue

                val versionDirs = moduleDir.listFiles()?.filter { it.isDirectory }?.sortedDescending() ?: continue
                for (versionDir in versionDirs) {
                    val jar = versionDir.resolve("$name-${versionDir.name}.jar")
                    if (jar.exists()) {
                        found.add(jar.toPath())
                        break
                    }
                }
            }

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
