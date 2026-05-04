package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

internal fun BridgeContext.rename(uri: String, line: Int, character: Int, newName: String): JsonObject {
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

            val renameOffsets = mutableSetOf<Int>()

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

            for (renameOffset in renameOffsets) {
                val startLine = document.getLineNumber(renameOffset) + 1
                val startLineStart = document.getLineStartOffset(document.getLineNumber(renameOffset))
                val startCol = renameOffset - startLineStart

                val nameLen = if (renameOffset == declNameOffset) {
                    declNameLength
                } else {
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
