package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.*

internal fun BridgeContext.analyze(uri: String, version: Int? = null): JsonObject {
    ensureSessionCurrent()

    val result = JsonObject()
    val diagnosticsArray = JsonArray()
    val editsArray = JsonArray()

    // Skip diagnostics for decompiled library stub files
    val tempDir = virtualFileTempDir
    if (tempDir != null) {
        val filePath = uriToPath(uri)
        if (filePath.startsWith(tempDir.resolve("decompiled").toString())) {
            result.add("diagnostics", diagnosticsArray)
            result.add("edits", editsArray)
            return result
        }
    }

    val currentSession = session
    if (currentSession == null) {
        System.err.println("CompilerBridge: analyze($uri) — session is NULL, returning empty diagnostics")
        result.add("diagnostics", diagnosticsArray)
        result.add("edits", editsArray)
        return result
    }

    val ktFile = findKtFile(currentSession, uri)
    if (ktFile == null) {
        System.err.println("CompilerBridge: analyze($uri) — file not found anywhere")
        result.add("diagnostics", diagnosticsArray)
        result.add("edits", editsArray)
        return result
    }

    System.err.println("CompilerBridge: analyze($uri) — found KtFile: ${ktFile.virtualFile?.path ?: "(ad-hoc)"}, name=${ktFile.name}")

    // Log content source for debugging
    val virtualContent = getVirtualFileContent(uri)
    val isVirtualFile = virtualContent != null &&
        findKtFileInSession(currentSession, uri)?.text != virtualContent
    System.err.println("CompilerBridge: analyze($uri) — using ${if (isVirtualFile) "virtual" else "session"} content (${ktFile.text.length} chars)")

    // For virtual files (content differs from session), collect names declared in the file.
    val declaredNames = if (isVirtualFile) collectDeclaredNames(ktFile) else emptySet()
    if (isVirtualFile && declaredNames.isNotEmpty()) {
        System.err.println("CompilerBridge: analyze($uri) — virtual file declares: $declaredNames (will filter false UNRESOLVED_REFERENCE)")
    }

    try {
        analyze(ktFile) {
            val analysisStart = System.currentTimeMillis()
            val document = ktFile.viewProvider.document
            val diagnostics = ktFile.collectDiagnostics(
                KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
            )
            val analysisElapsed = System.currentTimeMillis() - analysisStart

            System.err.println("CompilerBridge: analyze($uri) — collectDiagnostics took ${analysisElapsed}ms, returned ${diagnostics.size} diagnostic(s)")
            for (d in diagnostics) {
                System.err.println("  [${d.severity}] ${d.factoryName}: ${d.defaultMessage} at ${d.textRanges.firstOrNull()}")
            }
            for (diagnostic in diagnostics) {
                // Suppress compiler diagnostics that are currently too noisy or diverge
                // from the UX we want to present in the editor.
                if (diagnostic.factoryName == "RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY" ||
                    diagnostic.factoryName == "CONTEXT_RECEIVERS_DEPRECATED") {
                    continue
                }

                if (isVirtualFile && diagnostic.factoryName == "UNRESOLVED_REFERENCE" && declaredNames.isNotEmpty()) {
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
    result.add("edits", editsArray)
    if (version != null) {
        result.addProperty("version", version)
    }
    return result
}

internal fun BridgeContext.analyzeAll(): JsonObject {
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

        // Skip Gradle build scripts
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
                    if (diagnostic.factoryName == "RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY" ||
                        diagnostic.factoryName == "CONTEXT_RECEIVERS_DEPRECATED") {
                        continue
                    }

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

private fun collectDeclaredNames(ktFile: KtFile): Set<String> {
    val names = mutableSetOf<String>()
    for (decl in ktFile.declarations) {
        when (decl) {
            is KtClassOrObject -> {
                decl.name?.let { names.add(it) }
                for (member in decl.declarations) {
                    (member as? KtNamedDeclaration)?.name?.let { names.add(it) }
                }
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
