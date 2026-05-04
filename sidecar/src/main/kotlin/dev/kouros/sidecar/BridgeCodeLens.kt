package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.*

internal fun BridgeContext.codeLens(uri: String): JsonObject {
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

            val classOrObjects = PsiTreeUtil.collectElementsOfType(
                ktFile, KtClassOrObject::class.java
            )
            for (classOrObj in classOrObjects) {
                val name = classOrObj.name ?: continue
                val nameIdentifier = classOrObj.nameIdentifier ?: continue

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
        } catch (_: Exception) {}
    }

    return count
}
