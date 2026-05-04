package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.types.Variance
import com.intellij.psi.util.PsiTreeUtil

internal fun BridgeContext.signatureHelp(uri: String, line: Int, character: Int): JsonObject {
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

            val callExpression = PsiTreeUtil.getParentOfType(
                element, KtCallExpression::class.java, false
            ) ?: run {
                result.add("signatures", signaturesArray)
                return@analyze
            }

            val activeParam = computeActiveParameter(callExpression, offset)

            val callInfo = callExpression.resolveToCall()
            if (callInfo is KaSuccessCallInfo) {
                val call = callInfo.call
                if (call is KaFunctionCall<*>) {
                    val signature = call.partiallyAppliedSymbol.signature
                    val functionSymbol = signature.symbol

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

private fun computeActiveParameter(callExpression: KtCallExpression, offset: Int): Int {
    val argList = callExpression.valueArgumentList ?: return 0
    val leftParen = argList.leftParenthesis ?: return 0

    if (offset <= leftParen.textRange.endOffset) return 0

    var commaCount = 0
    for (child in argList.node.getChildren(null)) {
        val childElement = child.psi ?: continue
        if (childElement.textRange.startOffset >= offset) break
        if (child.text == ",") commaCount++
    }

    return commaCount
}
