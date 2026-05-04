package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance

internal fun BridgeContext.inlayHints(uri: String, startLine: Int, endLine: Int): JsonObject {
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

            val properties = PsiTreeUtil.collectElementsOfType(
                ktFile, KtProperty::class.java
            )
            for (property in properties) {
                val propOffset = property.textOffset
                if (propOffset < startOffset || propOffset > endOffset) continue
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

            val callExpressions = PsiTreeUtil.collectElementsOfType(
                ktFile, KtCallExpression::class.java
            )
            for (callExpr in callExpressions) {
                val callOffset = callExpr.textOffset
                if (callOffset < startOffset || callOffset > endOffset) continue

                val argList = callExpr.valueArgumentList ?: continue
                val args = argList.arguments
                if (args.isEmpty()) continue

                if (args.all { it.isNamed() }) continue

                try {
                    val callInfo = callExpr.resolveToCall()
                    if (callInfo is KaSuccessCallInfo) {
                        val call = callInfo.call
                        if (call is KaFunctionCall<*>) {
                            val signature = call.partiallyAppliedSymbol.signature
                            val valueParams = signature.valueParameters

                            for ((index, arg) in args.withIndex()) {
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
