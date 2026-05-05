package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

internal fun BridgeContext.references(uri: String, line: Int, character: Int): JsonObject {
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

        var targetName: String? = null
        var targetFilePath: String? = null
        var targetOffset: Int? = null
        var targetDeclaration: com.intellij.psi.PsiElement? = null

        analyze(ktFile) {
            val offset = lineColToOffset(ktFile, line, character) ?: return@analyze
            val element = ktFile.findElementAt(offset) ?: return@analyze

            val springProducer = findSpringModelProducerDeclaration(element)
            if (springProducer != null) {
                targetDeclaration = springProducer
            } else {
                targetDeclaration = findTargetDeclaration(element) ?: return@analyze
            }

            targetName = (targetDeclaration as? KtNamedDeclaration)?.name
                ?: targetDeclaration?.text?.take(50)
            val containingFile = targetDeclaration?.containingFile as? KtFile
            targetFilePath = containingFile?.virtualFile?.path
            targetOffset = targetDeclaration?.textOffset

            val resolvedDeclaration = targetDeclaration ?: return@analyze
            val declDocument = containingFile?.viewProvider?.document
            if (declDocument != null) {
                val declLine = declDocument.getLineNumber(resolvedDeclaration.textOffset) + 1
                val declLineStart = declDocument.getLineStartOffset(declDocument.getLineNumber(resolvedDeclaration.textOffset))
                val declCol = resolvedDeclaration.textOffset - declLineStart
                val declLoc = JsonObject()
                declLoc.addProperty("uri", fileUriForKtFile(containingFile))
                declLoc.addProperty("line", declLine)
                declLoc.addProperty("column", declCol)
                locationsArray.add(declLoc)
            }
        }

        if (targetName == null || targetFilePath == null) {
            result.add("locations", locationsArray)
            return result
        }

        // Search ALL session files for references to the target
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
                                if (resolvedFile.virtualFile.path == targetFilePath &&
                                    resolved.textOffset == targetOffset) {
                                    val doc = sessionFile.viewProvider.document ?: continue
                                    val refOffset = nameExpr.textOffset
                                    val refLine = doc.getLineNumber(refOffset) + 1
                                    val refLineStart = doc.getLineStartOffset(doc.getLineNumber(refOffset))
                                    val refCol = refOffset - refLineStart

                                    val loc = JsonObject()
                                    loc.addProperty("uri", fileUriForKtFile(sessionFile))
                                    loc.addProperty("line", refLine)
                                    loc.addProperty("column", refCol)

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
            val declarationUri = declarationKtFile?.let { fileUriForKtFile(it) } ?: uri
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
