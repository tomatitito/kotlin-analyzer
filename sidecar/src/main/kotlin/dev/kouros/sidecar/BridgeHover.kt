package dev.kouros.sidecar

import com.google.gson.JsonObject
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance

internal fun BridgeContext.hover(uri: String, line: Int, character: Int): JsonObject {
    val perfStart = System.currentTimeMillis()
    var result = hoverOnce(uri, line, character)

    if (!result.has("contents") && sessionDirty) {
        val currentReason = result.get("reason")?.asString ?: "no explicit reason"
        System.err.println("CompilerBridge: hover($uri) — retrying after rebuild (reason=$currentReason)")
        try {
            ensureSessionCurrent()
            result = hoverOnce(uri, line, character)
        } catch (e: Throwable) {
            val retryFailure = JsonObject()
            retryFailure.addProperty("reason", "hover retry failed: ${e.javaClass.name}: ${e.message}")
            result = retryFailure
        }
    }

    System.err.println("[PERF] method=hover uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
    return result
}

private fun BridgeContext.hoverOnce(uri: String, line: Int, character: Int): JsonObject {
    val result = JsonObject()
    var resolvedOffset: Int? = null
    val setFailureReason = { reason: String ->
        if (!result.has("reason")) {
            result.addProperty("reason", reason)
        }
    }

    val currentSession = session ?: run {
        setFailureReason("no-active-session")
        return result
    }
    val ktFile = findKtFile(currentSession, uri) ?: run {
        setFailureReason("file-not-found")
        return result
    }

    try {
        analyze(ktFile) {
            val offset = lineColToOffset(ktFile, line, character)
            if (offset == null) {
                val reason = "line-col-offset-null (line=$line, char=$character, fileLines=${ktFile.text.lines().size})"
                setFailureReason(reason)
                System.err.println("CompilerBridge: hover — $reason")
                return@analyze
            }
            resolvedOffset = offset

            val element = findHoverElement(ktFile, line, offset)
            if (element == null) {
                val reason = "findElementAt=null (offset=$offset, textLen=${ktFile.text.length})"
                setFailureReason(reason)
                System.err.println("CompilerBridge: hover — $reason")
                return@analyze
            }

            var current: PsiElement? = element
            while (current != null) {
                if (current is PsiWhiteSpace || current is PsiComment) {
                    current = current.parent
                    continue
                }

                if (current is KtAnnotationEntry) {
                    val hoverText = buildAnnotationEntryHover(this@hoverOnce, current)
                    if (hoverText != null) {
                        result.addProperty("contents", hoverText)
                        result.remove("reason")
                        return@analyze
                    }
                }

                if (current is KtNamedDeclaration) {
                    val hoverText = buildDeclarationHover(this@hoverOnce, current)
                    if (hoverText != null) {
                        result.addProperty("contents", hoverText)
                        result.remove("reason")
                        return@analyze
                    }
                }

                if (current is KtReferenceExpression) {
                    val insideAnnotation = PsiTreeUtil.getParentOfType(current, KtAnnotationEntry::class.java) != null
                    if (!insideAnnotation) {
                        val hoverText = buildReferenceHover(this@hoverOnce, current)
                        if (hoverText != null) {
                            result.addProperty("contents", hoverText)
                            result.remove("reason")
                            return@analyze
                        }
                    }
                }

                if (current is KtExpression) {
                    val insideAnnotationExpr = PsiTreeUtil.getParentOfType(current, KtAnnotationEntry::class.java) != null
                    if (!insideAnnotationExpr) {
                        try {
                            val type = current.expressionType
                            if (type != null) {
                                val rendered = type.render(
                                    KaTypeRendererForSource.WITH_SHORT_NAMES,
                                    Variance.INVARIANT
                                )
                                result.addProperty("contents", "```kotlin\n$rendered\n```")
                                result.remove("reason")
                                return@analyze
                            }
                        } catch (_: Exception) {}
                    }
                }

                current = current.parent
            }

            if (!result.has("reason")) {
                setFailureReason("no-hoverable-element (line=$line, char=$character, offset=${resolvedOffset ?: -1})")
            }
        }
    } catch (e: Throwable) {
        val reason = "hover failed: ${e.javaClass.name}: ${e.message}"
        setFailureReason(reason)
        System.err.println("CompilerBridge: $reason")
    }

    return result
}

// --- KaSession extension methods for hover rendering ---

private fun KaSession.buildDeclarationHover(
    ctx: BridgeContext,
    declaration: KtNamedDeclaration,
): String? {
    val symbol = try {
        declaration.symbol
    } catch (_: Exception) {
        null
    }

    val rendered = if (symbol != null) {
        try {
            symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
        } catch (_: Exception) {
            val text = declaration.text?.take(300) ?: return null
            text.lines().first()
        }
    } else {
        val text = declaration.text?.take(300) ?: return null
        text.lines().first()
    }

    val kdocText = ctx.extractKDocText(declaration)

    return buildString {
        if (symbol != null) {
            val containerInfo = buildContainerInfo(symbol)
            if (containerInfo != null) {
                append(containerInfo)
                append("\n\n")
            }

            val annotationsBlock = buildAnnotationsBlock(symbol)
            val supertypesLine = buildSupertypesLine(symbol)

            val cleanRendered = if (annotationsBlock != null) {
                rendered.lines()
                    .dropWhile { it.trimStart().startsWith("@") }
                    .joinToString("\n")
            } else {
                rendered
            }

            append("```kotlin\n")
            if (annotationsBlock != null) {
                append(annotationsBlock)
                append("\n")
            }
            append(cleanRendered)
            val declarationLine = cleanRendered.lines().lastOrNull { it.isNotBlank() } ?: ""
            if (supertypesLine != null && !declarationLine.contains(":")) {
                append("\n")
                append(supertypesLine)
            }
            append("\n```")

            if (kdocText != null) {
                append("\n\n---\n\n")
                append(enrichKDocText(kdocText))
            }

            val sourceOrigin = buildSourceOrigin(symbol)
            if (sourceOrigin != null) {
                append("\n\n---\n\n")
                append(sourceOrigin)
            }
        } else {
            append("```kotlin\n")
            append(rendered)
            append("\n```")

            if (kdocText != null) {
                append("\n\n---\n\n")
                append(enrichKDocText(kdocText))
            }
        }
    }
}

private fun KaSession.buildReferenceHover(
    ctx: BridgeContext,
    refExpr: KtReferenceExpression,
): String? {
    for (ref in refExpr.references) {
        if (ref is KtReference) {
            var symbol = ref.resolveToSymbol()
            if (symbol is KaConstructorSymbol) {
                val containingClass = symbol.containingDeclaration
                if (containingClass is KaDeclarationSymbol) {
                    symbol = containingClass
                }
            }
            if (symbol is KaDeclarationSymbol) {
                val rendered = try {
                    symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
                } catch (_: Exception) {
                    symbol.toString()
                }

                val kdocText = extractSymbolDocumentation(ctx, symbol)

                return buildString {
                    val containerInfo = buildContainerInfo(symbol)
                    if (containerInfo != null) {
                        append(containerInfo)
                        append("\n\n")
                    }

                    val annotationsBlock = buildAnnotationsBlock(symbol)
                    val supertypesLine = buildSupertypesLine(symbol)

                    val cleanRendered = if (annotationsBlock != null) {
                        rendered.lines()
                            .dropWhile { it.trimStart().startsWith("@") }
                            .joinToString("\n")
                    } else {
                        rendered
                    }

                    append("```kotlin\n")
                    if (annotationsBlock != null) {
                        append(annotationsBlock)
                        append("\n")
                    }
                    append(cleanRendered)
                    val refDeclLine = cleanRendered.lines().lastOrNull { it.isNotBlank() } ?: ""
                    if (supertypesLine != null && !refDeclLine.contains(":")) {
                        append("\n")
                        append(supertypesLine)
                    }
                    append("\n```")
                    if (kdocText != null) {
                        append("\n\n---\n\n")
                        append(enrichKDocText(kdocText))
                    }

                    val sourceOrigin = buildSourceOrigin(symbol)
                    if (sourceOrigin != null) {
                        append("\n\n---\n\n")
                        append(sourceOrigin)
                    }
                }
            }
        }
    }

    val type = refExpr.expressionType
    if (type != null) {
        val rendered = type.render(
            KaTypeRendererForSource.WITH_SHORT_NAMES,
            Variance.INVARIANT
        )
        return "```kotlin\n$rendered\n```"
    }

    return null
}

private fun KaSession.buildAnnotationEntryHover(
    ctx: BridgeContext,
    annotationEntry: KtAnnotationEntry,
): String? {
    try {
        val typeRef = annotationEntry.typeReference ?: return null
        val type = typeRef.type
        val classSymbol = (type as? org.jetbrains.kotlin.analysis.api.types.KaClassType)
            ?.symbol as? KaNamedClassSymbol ?: return null

        val psi = classSymbol.psi as? KtDeclaration

        val rendered = classSymbol.render(
            KaDeclarationRendererForSource.WITH_SHORT_NAMES
        )

        val containerInfo = buildContainerInfo(classSymbol)
        val annotationsBlock = buildAnnotationsBlock(classSymbol)
        val supertypesLine = buildSupertypesLine(classSymbol)

        val cleanRendered = if (annotationsBlock != null) {
            rendered.lines()
                .dropWhile { it.trimStart().startsWith("@") }
                .joinToString("\n")
        } else {
            rendered
        }

        val kdocText = if (psi != null) ctx.extractKDocText(psi) else null
        val symbolDoc = extractSymbolDocumentation(ctx, classSymbol)
        val documentation = kdocText ?: symbolDoc

        val sourceOrigin = buildSourceOrigin(classSymbol)

        return buildString {
            if (containerInfo != null) {
                append(containerInfo)
                append("\n\n")
            }

            append("```kotlin\n")
            if (annotationsBlock != null) {
                append(annotationsBlock)
                append("\n")
            }
            append(cleanRendered)
            val annotDeclLine = cleanRendered.lines().lastOrNull { it.isNotBlank() } ?: ""
            if (supertypesLine != null && !annotDeclLine.contains(":")) {
                append("\n")
                append(supertypesLine)
            }
            append("\n```")

            if (documentation != null) {
                append("\n\n---\n\n")
                append(enrichKDocText(documentation))
            }

            if (sourceOrigin != null) {
                append("\n\n---\n\n")
                append(sourceOrigin)
            }
        }
    } catch (e: Exception) {
        System.err.println("CompilerBridge: buildAnnotationEntryHover failed: ${e.javaClass.name}: ${e.message}")
        return null
    }
}

private fun KaSession.buildContainerInfo(
    symbol: KaDeclarationSymbol,
): String? {
    try {
        val containingSymbol = try {
            symbol.containingDeclaration
        } catch (_: Exception) {
            null
        }

        val psi = symbol.psi
        val fqName = when (psi) {
            is KtNamedDeclaration -> psi.fqName?.asString()
            else -> null
        }

        if (fqName != null) {
            val name = when (symbol) {
                is KaNamedSymbol -> symbol.name.asString()
                else -> null
            }
            val packageName = if (name != null && fqName.endsWith(".$name")) {
                fqName.removeSuffix(".$name")
            } else {
                fqName.substringBeforeLast('.', "")
            }
            if (packageName.isNotEmpty()) {
                if (containingSymbol is KaNamedSymbol) {
                    return "*(${containingSymbol.name.asString()} in $packageName)*"
                }
                return "*($packageName)*"
            }
        }

        if (containingSymbol is KaNamedSymbol) {
            val containerPsi = containingSymbol.psi
            val containerFqName = (containerPsi as? KtNamedDeclaration)?.fqName?.asString()
            if (containerFqName != null) {
                return "*(${containingSymbol.name.asString()} in ${containerFqName.substringBeforeLast('.', "")})*"
            }
            if (containingSymbol is KaClassLikeSymbol) {
                val classId = (containingSymbol as KaClassLikeSymbol).classId
                if (classId != null) {
                    val pkg = classId.packageFqName.asString()
                    if (pkg.isNotEmpty()) {
                        return "*(${containingSymbol.name.asString()} in $pkg)*"
                    }
                }
            }
            return "*(in ${containingSymbol.name.asString()})*"
        }

        if (symbol is KaClassLikeSymbol) {
            val classId = symbol.classId
            if (classId != null) {
                val pkg = classId.packageFqName.asString()
                if (pkg.isNotEmpty()) return "*($pkg)*"
            }
        }
        if (symbol is KaCallableSymbol) {
            val callableId = symbol.callableId
            if (callableId != null) {
                val pkg = callableId.packageName.asString()
                if (pkg.isNotEmpty()) return "*($pkg)*"
            }
        }
    } catch (_: Exception) {}
    return null
}

private fun KaSession.buildAnnotationsBlock(
    symbol: KaDeclarationSymbol,
): String? {
    try {
        val annotations = symbol.annotations
        if (annotations.isEmpty()) return null

        val suppressedPackages = setOf("kotlin.jvm.internal")
        val suppressedNames = setOf("SinceKotlin", "WasExperimental")

        val relevantAnnotations = annotations.mapNotNull { annotation ->
            val classId = annotation.classId ?: return@mapNotNull null
            val shortName = classId.shortClassName.asString()
            val packageName = classId.packageFqName.asString()

            if (packageName in suppressedPackages) return@mapNotNull null
            if (shortName in suppressedNames) return@mapNotNull null

            val args = annotation.arguments.mapNotNull { arg ->
                val name = arg.name?.asString()
                val value = try {
                    renderAnnotationArgValue(arg.expression)
                } catch (_: Exception) { null }
                if (name != null && value != null) "$name = $value"
                else value ?: name
            }
            if (args.isNotEmpty()) "@$shortName(${args.joinToString(", ")})"
            else "@$shortName"
        }

        if (relevantAnnotations.isEmpty()) return null
        return relevantAnnotations.joinToString("\n")
    } catch (_: Exception) {
        return null
    }
}

private fun KaSession.buildSupertypesLine(
    symbol: KaDeclarationSymbol,
): String? {
    try {
        if (symbol !is KaNamedClassSymbol) return null
        val supertypes = symbol.superTypes
            .mapNotNull { type ->
                val rendered = type.render(
                    KaTypeRendererForSource.WITH_SHORT_NAMES,
                    Variance.INVARIANT
                )
                if (rendered == "Any" || rendered == "Annotation") null else rendered
            }
        if (supertypes.isEmpty()) return null
        return ": ${supertypes.joinToString(", ")}"
    } catch (_: Exception) {
        return null
    }
}

internal fun KaSession.extractSymbolDocumentation(
    ctx: BridgeContext,
    symbol: KaDeclarationSymbol,
): String? {
    val psi = symbol.psi

    if (psi is KtDeclaration) {
        val kdoc = ctx.extractKDocText(psi)
        if (kdoc != null) return kdoc
    }

    if (psi is KtDeclaration) {
        val docComment = extractDocCommentFromPsiText(psi)
        if (docComment != null) return docComment
    }

    if (psi != null) {
        val javadoc = extractJavadocFromPsi(psi)
        if (javadoc != null) return javadoc
    }

    return null
}

// --- Non-KaSession helper methods ---

private fun renderAnnotationArgValue(value: KaAnnotationValue): String {
    return when (value) {
        is KaAnnotationValue.EnumEntryValue -> {
            val callableId = value.callableId
            "${callableId?.classId?.shortClassName?.asString() ?: ""}.${callableId?.callableName?.asString() ?: ""}"
                .removePrefix(".")
        }
        is KaAnnotationValue.ArrayValue -> {
            value.values.joinToString(", ", "[", "]") { renderAnnotationArgValue(it) }
        }
        is KaAnnotationValue.ClassLiteralValue -> {
            "${value.classId?.shortClassName?.asString() ?: "?"}::class"
        }
        is KaAnnotationValue.ConstantValue -> {
            val raw = value.value.toString()
            raw.removePrefix("KaConstantValue.")
                .replace(Regex("^\\w+Value\\((.+)\\)$")) { it.groupValues[1] }
        }
        is KaAnnotationValue.NestedAnnotationValue -> {
            "@${value.annotation.classId?.shortClassName?.asString() ?: "?"}"
        }
        else -> {
            value.toString()
                .removePrefix("KaAnnotationValue.")
                .replace(Regex("^\\w+\\((.+)\\)$")) { it.groupValues[1] }
        }
    }
}

private fun buildSourceOrigin(symbol: KaDeclarationSymbol): String? {
    try {
        val psi = symbol.psi
        val vf = psi?.containingFile?.virtualFile
        val path = vf?.path

        if (path == null) {
            if (symbol.origin.name.contains("LIBRARY", ignoreCase = true)) {
                val classId = (symbol as? KaClassLikeSymbol)?.classId
                val pkg = classId?.packageFqName?.asString()
                if (pkg != null && pkg.startsWith("kotlin")) {
                    return "*From: kotlin-stdlib*"
                }
                if (pkg != null) {
                    return "*From: library ($pkg)*"
                }
                return "*From: library*"
            }
            return null
        }

        if (!path.contains(".jar") && !path.contains(".gradle")) return null

        val jarName = path.substringAfterLast('/')
            .let { if (it.contains("!")) path.substringBefore("!").substringAfterLast('/') else it }

        if (jarName.endsWith(".jar")) {
            return "*From: $jarName*"
        }
    } catch (_: Exception) {}
    return null
}

internal fun enrichKDocText(text: String): String {
    return text
        .replace(Regex("\\[([\\w.]+)]")) { match ->
            val ref = match.groupValues[1]
            val shortName = ref.substringAfterLast('.')
            "`$shortName`"
        }
        .replace(Regex("(?m)^\\*\\*@author\\*\\*\\s*(.+)")) { "**Author:** ${it.groupValues[1]}" }
        .replace(Regex("(?m)^\\*\\*@since\\*\\*\\s*(.+)")) { "**Since:** ${it.groupValues[1]}" }
        .replace(Regex("(?m)^\\*\\*@see\\*\\*\\s*(.+)")) { "**See also:** `${it.groupValues[1].trim()}`" }
        .replace(Regex("(?m)^\\*\\*@version\\*\\*\\s*(.+)")) { "**Version:** ${it.groupValues[1]}" }
}

private fun extractDocCommentFromPsiText(declaration: KtDeclaration): String? {
    try {
        var prev = declaration.prevSibling
        while (prev != null) {
            if (prev is PsiWhiteSpace) {
                prev = prev.prevSibling
                continue
            }
            if (prev is PsiComment) {
                val text = prev.text
                if (text.startsWith("/**")) {
                    return parseDocCommentText(text)
                }
            }
            break
        }
    } catch (_: Exception) {}
    return null
}

internal fun parseDocCommentText(comment: String): String? {
    val lines = comment.removePrefix("/**").removeSuffix("*/")
        .lines()
        .map { it.trim().removePrefix("* ").removePrefix("*") }
        .filter { it.isNotBlank() }
    if (lines.isEmpty()) return null
    return lines.joinToString("\n")
}

private fun extractJavadocFromPsi(element: PsiElement): String? {
    try {
        val docOwnerClass = try {
            Class.forName("com.intellij.psi.javadoc.PsiDocCommentOwner")
        } catch (_: ClassNotFoundException) {
            return null
        }

        if (!docOwnerClass.isInstance(element)) return null

        val getDocComment = docOwnerClass.getMethod("getDocComment")
        val docComment = getDocComment.invoke(element) ?: return null

        val getText = docComment.javaClass.getMethod("getText")
        val text = getText.invoke(docComment) as? String ?: return null

        return parseDocCommentText(text)
    } catch (_: Exception) {
        return null
    }
}
