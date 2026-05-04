package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

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

internal fun BridgeContext.semanticTokens(uri: String): JsonObject {
    val result = JsonObject()
    val dataArray = JsonArray()

    val tokenTypes = listOf(
        "namespace", "type", "class", "enum", "interface", "struct",
        "typeParameter", "parameter", "variable", "property", "enumMember",
        "event", "function", "method", "macro", "keyword", "modifier",
        "comment", "string", "number", "regexp", "operator", "decorator",
    )
    val tokenModifiers = listOf(
        "declaration", "definition", "readonly", "static", "deprecated",
        "abstract", "async", "modification", "documentation", "defaultLibrary",
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

        data class RawToken(
            val line: Int,
            val col: Int,
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

        fun walkPsi(element: PsiElement) {
            when (element) {
                is PsiComment -> {
                    addToken(element.textOffset, element.textLength, 17)
                }
                is KtStringTemplateExpression -> {
                    addToken(element.textOffset, element.textLength, 18)
                }
                is KtConstantExpression -> {
                    val elementType = element.node.elementType
                    if (elementType == KtTokens.INTEGER_LITERAL ||
                        elementType == KtTokens.FLOAT_LITERAL
                    ) {
                        addToken(element.textOffset, element.textLength, 19)
                    }
                }
                is KtAnnotationEntry -> {
                    addToken(element.textOffset, element.textLength, 22)
                }
                else -> {
                    if (element.children.isEmpty() && element !is PsiWhiteSpace) {
                        val elementType = element.node.elementType
                        if (KEYWORD_TOKENS.contains(elementType)) {
                            addToken(element.textOffset, element.textLength, 15)
                        } else if (elementType == KtTokens.IDENTIFIER) {
                            val parent = element.parent
                            val tokenType = classifyIdentifierByParent(parent)
                            val modifiers = classifyIdentifierModifiers(parent)
                            if (tokenType >= 0) {
                                addToken(element.textOffset, element.textLength, tokenType, modifiers)
                            }
                        }
                    }

                    if (element !is KtStringTemplateExpression && element !is KtAnnotationEntry) {
                        for (child in element.children) {
                            walkPsi(child)
                        }
                    }
                }
            }
        }

        walkPsi(ktFile)

        tokens.sortWith(compareBy({ it.line }, { it.col }))

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

private fun classifyIdentifierByParent(parent: PsiElement?): Int {
    return when (parent) {
        is KtNamedFunction -> 12
        is KtParameter -> 7
        is KtProperty -> {
            if (parent.isLocal) 8
            else 9
        }
        is KtClassOrObject -> 2
        is KtTypeReference -> 1
        is KtTypeAlias -> 1
        is KtTypeParameter -> 6
        is KtEnumEntry -> 10
        is KtSimpleNameExpression -> {
            classifyReferenceExpression(parent)
        }
        is KtOperationReferenceExpression -> 21
        else -> -1
    }
}

private fun classifyReferenceExpression(expr: KtSimpleNameExpression): Int {
    val grandParent = expr.parent
    return when {
        grandParent is KtCallExpression && grandParent.calleeExpression == expr -> 12
        grandParent is KtTypeReference -> 1
        grandParent is KtDotQualifiedExpression && grandParent.selectorExpression == expr -> {
            9
        }
        grandParent is KtUserType -> 1
        else -> 8
    }
}

private fun classifyIdentifierModifiers(parent: PsiElement?): Int {
    var modifiers = 0
    when (parent) {
        is KtNamedFunction,
        is KtClassOrObject,
        is KtTypeAlias,
        is KtTypeParameter,
        is KtEnumEntry -> {
            modifiers = modifiers or 0x01
            modifiers = modifiers or 0x02
        }
        is KtParameter -> {
            modifiers = modifiers or 0x01
        }
        is KtProperty -> {
            modifiers = modifiers or 0x01
            modifiers = modifiers or 0x02
            if (parent.isVar.not()) {
                modifiers = modifiers or 0x04
            }
        }
    }
    return modifiers
}

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
