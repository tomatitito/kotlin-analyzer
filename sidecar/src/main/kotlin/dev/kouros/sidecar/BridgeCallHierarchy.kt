package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.*

internal fun BridgeContext.callHierarchyPrepare(uri: String, line: Int, character: Int): JsonObject {
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

internal fun BridgeContext.callHierarchyIncoming(uri: String, line: Int, character: Int, name: String): JsonObject {
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

            val targetFunction = PsiTreeUtil.getParentOfType(
                element, KtNamedFunction::class.java, false
            ) ?: run {
                result.add("calls", callsArray)
                return@analyze
            }

            val allKtFiles = currentSession.modulesWithFiles.entries
                .flatMap { (_, files) -> files }
                .filterIsInstance<KtFile>()

            for (searchFile in allKtFiles) {
                val searchDoc = searchFile.viewProvider.document ?: continue
                val fileUri = "file://${searchFile.virtualFile.path}"

                val callExprs = PsiTreeUtil.collectElementsOfType(
                    searchFile, KtCallExpression::class.java
                )

                for (callExpr in callExprs) {
                    try {
                        val calleeExpr = callExpr.calleeExpression
                        if (calleeExpr == null) continue
                        val calleeName = calleeExpr.text
                        if (calleeName != name) continue

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

                        val enclosingFunction = PsiTreeUtil.getParentOfType(
                            callExpr, KtNamedFunction::class.java, false
                        )

                        val callObj = JsonObject()

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
                            fromObj.addProperty("name", searchFile.name)
                            fromObj.addProperty("kind", "file")
                            fromObj.addProperty("uri", fileUri)
                            fromObj.addProperty("line", 1)
                            fromObj.addProperty("column", 0)
                        }
                        callObj.add("from", fromObj)

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
