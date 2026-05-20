package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance

// --- Data types ---

private data class ScopeCompletionCandidate(
    val label: String,
    val source: ScopeCompletionSource,
    val item: JsonObject,
)

private data class RankedScopeCompletionCandidate(
    val candidate: ScopeCompletionCandidate,
    val sourcePriority: Int,
    val matchPriority: Int,
    val originalIndex: Int,
)

private enum class ScopeCompletionSource {
    VISIBLE_SCOPE,
    KEYWORD,
    AUTO_IMPORT,
}

// --- Public entry point ---

internal fun BridgeContext.completion(
    uri: String,
    line: Int,
    character: Int,
    triggerCharacter: String? = null,
    correlationId: String? = null,
    includeMetrics: Boolean = false,
): JsonObject {
    val shouldCollectMetrics = includeMetrics || completionMetricsEnabledByEnv()
    val metrics = if (shouldCollectMetrics) {
        CompletionMetrics(correlationId ?: "sidecar-${System.nanoTime()}")
    } else {
        null
    }

    if (metrics?.stage("session_freshness_check") { newFilesPendingInSession } ?: newFilesPendingInSession) {
        metrics?.count("sessionRebuilt", 1)
        metrics?.stage("session_refresh") { ensureSessionCurrent() } ?: ensureSessionCurrent()
    } else {
        metrics?.count("sessionRebuilt", 0)
    }

    val result = completionOnce(uri, line, character, triggerCharacter, metrics)

    if (metrics != null) {
        val metricsJson = metrics.toJson()
        result.add("metrics", metricsJson)
        if (completionMetricsEnabledByEnv()) {
            System.err.println("completion_metrics=$metricsJson")
        }
    }
    return result
}

// --- Core implementation ---

private fun BridgeContext.completionOnce(
    uri: String,
    line: Int,
    character: Int,
    triggerCharacter: String?,
    metrics: CompletionMetrics?,
): JsonObject {
    val result = JsonObject()
    val itemsArray = JsonArray()
    val setFailureReason = { reason: String ->
        if (!result.has("reason")) {
            result.addProperty("reason", reason)
        }
    }

    val currentSession = metrics?.stage("session_lookup") { session } ?: session
    if (currentSession == null) {
        setFailureReason("no-active-session")
        result.add("items", itemsArray)
        return result
    }
    val fileSelection = metrics?.stage("file_lookup") {
        selectKtFileForCompletion(currentSession, uri, metrics)
    } ?: selectKtFileForCompletion(currentSession, uri)
    metrics?.field("completionFileMode", fileSelection.mode)
    metrics?.field("psiValidationBypass", psiValidationBypassEnabled.toString())
    metrics?.field(
        "danglingAnalysisMode",
        fileSelection.danglingResolutionMode?.name ?: "none",
    )
    if (fileSelection.fallbackReason != null) {
        metrics?.count("danglingFallback", 1)
        metrics?.field("danglingFallbackReason", fileSelection.fallbackReason)
    } else {
        metrics?.count("danglingFallback", 0)
    }

    val ktFile = fileSelection.ktFile
    if (ktFile == null) {
        setFailureReason("file-not-found")
        result.add("items", itemsArray)
        return result
    }
    val context = this

    try {
        runCompletionAnalysis(
            ktFile = ktFile,
            fileSelection = fileSelection,
            metrics = metrics,
        ) {
            completeInsideAnalysis(
                context = context,
                ktFile = ktFile,
                line = line,
                character = character,
                triggerCharacter = triggerCharacter,
                result = result,
                itemsArray = itemsArray,
                setFailureReason = setFailureReason,
                metrics = metrics,
            )
        }
    } catch (e: Throwable) {
        val fallbackReason = "dangling-analysis-failed:${e.javaClass.name}:${e.message ?: ""}"
        val fallbackFile = if (fileSelection.danglingResolutionMode != null) {
            metrics?.count("danglingFallback", 1)
            metrics?.field("danglingFallbackReason", fallbackReason)
            metrics?.stage("fallback_file_lookup") { findKtFile(currentSession, uri) } ?: findKtFile(currentSession, uri)
        } else {
            null
        }

        if (fallbackFile != null && fallbackFile !== ktFile) {
            try {
                metrics?.stage("fallback_analysis_api_analyze_block") {
                    analyze(fallbackFile) {
                        completeInsideAnalysis(
                            context = context,
                            ktFile = fallbackFile,
                            line = line,
                            character = character,
                            triggerCharacter = triggerCharacter,
                            result = result,
                            itemsArray = itemsArray,
                            setFailureReason = setFailureReason,
                            metrics = metrics,
                        )
                    }
                } ?: analyze(fallbackFile) {
                    completeInsideAnalysis(
                        context = context,
                        ktFile = fallbackFile,
                        line = line,
                        character = character,
                        triggerCharacter = triggerCharacter,
                        result = result,
                        itemsArray = itemsArray,
                        setFailureReason = setFailureReason,
                        metrics = null,
                    )
                }
            } catch (fallbackError: Throwable) {
                val reason = "completion failed: ${fallbackError.javaClass.name}: ${fallbackError.message}"
                setFailureReason(reason)
                System.err.println("CompilerBridge: $reason")
            }
        } else {
            val reason = "completion failed: ${e.javaClass.name}: ${e.message}"
            setFailureReason(reason)
            System.err.println("CompilerBridge: $reason")
        }
    }

    metrics?.count("finalItems", itemsArray.size())
    result.add("items", metrics?.stage("json_item_assembly") { itemsArray } ?: itemsArray)
    return result
}

private fun runCompletionAnalysis(
    ktFile: KtFile,
    fileSelection: CompletionFileSelection,
    metrics: CompletionMetrics?,
    action: org.jetbrains.kotlin.analysis.api.KaSession.() -> Unit,
) {
    val danglingMode = fileSelection.danglingResolutionMode
    if (danglingMode != null) {
        metrics?.stage("dangling_analysis_api_analyze_copy_block") {
            analyzeCopy(ktFile, danglingMode) { action() }
        } ?: analyzeCopy(ktFile, danglingMode) { action() }
    } else {
        metrics?.stage("analysis_api_analyze_block") {
            analyze(ktFile) { action() }
        } ?: analyze(ktFile) { action() }
    }
}

private fun org.jetbrains.kotlin.analysis.api.KaSession.completeInsideAnalysis(
    context: BridgeContext,
    ktFile: KtFile,
    line: Int,
    character: Int,
    triggerCharacter: String?,
    result: JsonObject,
    itemsArray: JsonArray,
    setFailureReason: (String) -> Unit,
    metrics: CompletionMetrics?,
) {
    val offset = (metrics?.stage("offset_lookup") { context.lineColToOffset(ktFile, line, character) }
        ?: context.lineColToOffset(ktFile, line, character)) ?: run {
        setFailureReason("line-col-offset-null (line=$line, char=$character)")
        result.add("items", itemsArray)
        return
    }

    val element = metrics?.stage("psi_file_lookup") {
        ktFile.findElementAt(offset) ?: ktFile.findElementAt(maxOf(0, offset - 1))
    } ?: (ktFile.findElementAt(offset) ?: ktFile.findElementAt(maxOf(0, offset - 1)))

    if (element == null) {
        setFailureReason("findElementAt=null (offset=$offset)")
        result.add("items", itemsArray)
        return
    }

    // Check if we are inside an import directive
    val importDirective = metrics?.stage("import_context_detection") {
        PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java)
            ?: ktFile.findElementAt(maxOf(0, offset - 1))?.let {
                PsiTreeUtil.getParentOfType(it, KtImportDirective::class.java)
            }
    } ?: (PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java)
        ?: ktFile.findElementAt(maxOf(0, offset - 1))?.let {
            PsiTreeUtil.getParentOfType(it, KtImportDirective::class.java)
        })
    if (importDirective != null) {
        val importItems = metrics?.stage("import_candidate_lookup") {
            context.collectImportCompletionItems(importDirective, offset)
        } ?: context.collectImportCompletionItems(importDirective, offset)
        metrics?.count("importCandidates", importItems.size)
        importItems
            .forEach(itemsArray::add)
    } else {
        // Check if we are in a dot-qualified expression (member completion)
        val dotCompletion = metrics?.stage("receiver_detection") {
            findDotCompletionReceiver(element, offset)
                ?: if (triggerCharacter == ".") {
                    findDotCompletionReceiverAtOffset(ktFile, offset)
                        ?: findReceiverExpressionEndingAtOffset(ktFile, offset)
                } else {
                    // Look backward past the typed prefix for a dot
                    findDotCompletionReceiverBeforePrefix(ktFile, offset)
                }
        } ?: (findDotCompletionReceiver(element, offset)
            ?: if (triggerCharacter == ".") {
                findDotCompletionReceiverAtOffset(ktFile, offset)
                    ?: findReceiverExpressionEndingAtOffset(ktFile, offset)
            } else {
                findDotCompletionReceiverBeforePrefix(ktFile, offset)
            })
        if (dotCompletion != null) {
            val memberPrefix = extractCompletionPrefix(element, offset)
            val before = itemsArray.size()
            metrics?.stage("member_candidate_collection") {
                collectMemberCompletions(dotCompletion, itemsArray, memberPrefix, metrics)
            } ?: collectMemberCompletions(dotCompletion, itemsArray, memberPrefix, null)
            metrics?.count("memberCandidates", itemsArray.size() - before)
        } else {
            val prefix = extractCompletionPrefix(element, offset)
            val visibleCandidates = metrics?.stage("scope_candidate_collection") {
                collectScopeCompletionCandidates(ktFile, element, prefix, metrics)
            } ?: collectScopeCompletionCandidates(ktFile, element, prefix, null)
            metrics?.count("scopeCandidates", visibleCandidates.size)
            val keywordCandidates = metrics?.stage("keyword_fallback_collection") {
                keywordCompletionCandidates()
            } ?: keywordCompletionCandidates()
            metrics?.count("keywordCandidates", keywordCandidates.size)
            val autoImportCandidates = metrics?.stage("auto_import_candidate_lookup") {
                context.collectUnimportedCompletionCandidates(
                    ktFile = ktFile,
                    prefix = prefix,
                    existingLabels = (visibleCandidates + keywordCandidates)
                        .map { it.label }
                        .toSet(),
                    metrics = metrics,
                )
            } ?: context.collectUnimportedCompletionCandidates(
                ktFile = ktFile,
                prefix = prefix,
                existingLabels = (visibleCandidates + keywordCandidates)
                    .map { it.label }
                    .toSet(),
                metrics = null,
            )
            metrics?.count("autoImportCandidates", autoImportCandidates.size)

            val rankedItems = metrics?.stage("ranking_scoring") {
                rankScopeCompletionCandidates(
                    prefix = prefix,
                    candidates = visibleCandidates + keywordCandidates + autoImportCandidates,
                )
            } ?: rankScopeCompletionCandidates(
                prefix = prefix,
                candidates = visibleCandidates + keywordCandidates + autoImportCandidates,
            )
            rankedItems.forEach(itemsArray::add)
        }
    }

    if (itemsArray.size() == 0) {
        setFailureReason("no-completion-items")
    } else {
        result.remove("reason")
    }
}

// --- Dot completion helpers (pure PSI, no BridgeContext state) ---

private fun findDotCompletionReceiver(element: PsiElement, offset: Int): KtExpression? {
    var current: PsiElement? = element
    while (current != null && current !is KtFile) {
        if (current is KtQualifiedExpression) {
            val dotNode = current.operationTokenNode
            if (offset > dotNode.startOffset) {
                return current.receiverExpression
            }
        }
        current = current.parent
    }

    val file = element.containingFile ?: return null
    return findDotCompletionReceiverAtOffset(file, offset)
}

private fun findDotCompletionReceiverAtOffset(file: PsiElement, offset: Int): KtExpression? {
    val containingFile = file.containingFile ?: return null
    val fileText = containingFile.text
    if (fileText.isEmpty()) return null

    val checkOffset = (offset - 1).coerceAtLeast(0)
    if (checkOffset >= fileText.length || fileText[checkOffset] != '.') {
        return null
    }

    val beforeDot = containingFile.findElementAt((checkOffset - 1).coerceAtLeast(0)) ?: return null
    var expr: PsiElement? = beforeDot
    while (expr != null && expr !is KtFile) {
        if (expr is KtExpression && expr.textRange.endOffset == checkOffset) {
            return expr
        }
        expr = expr.parent
    }

    return null
}

private fun findReceiverExpressionEndingAtOffset(file: KtFile, offset: Int): KtExpression? {
    if (offset <= 0) return null

    val probeOffset = minOf(offset - 1, file.text.length - 1)
    val leaf = file.findElementAt(probeOffset) ?: return null
    var current: PsiElement? = leaf
    while (current != null && current !is KtFile) {
        if (current is KtExpression && current.textRange.endOffset == offset) {
            return current
        }
        current = current.parent
    }

    return null
}

/**
 * Looks backward from offset past any typed identifier prefix to find a dot,
 * then resolves the receiver expression before that dot.
 * Handles `receiver.prefix` where the PSI might not parse as a KtQualifiedExpression,
 * including multiline chaining like:
 *   Criteria
 *       .where("farbId")
 *       .prefix
 */
private fun findDotCompletionReceiverBeforePrefix(file: KtFile, offset: Int): KtExpression? {
    val text = file.text ?: return null
    var pos = offset
    // Skip backward past identifier chars (the typed prefix)
    while (pos > 0 && (text[pos - 1].isLetterOrDigit() || text[pos - 1] == '_')) {
        pos--
    }
    // Check for a dot immediately before the prefix, or after whitespace (multiline chaining)
    var dotPos = pos
    while (dotPos > 0 && text[dotPos - 1].isWhitespace()) {
        dotPos--
    }
    if (dotPos <= 0 || text[dotPos - 1] != '.') return null
    val dotOffset = dotPos - 1

    // Try PSI: walk up from the dot looking for a qualified expression
    val elementAtDot = file.findElementAt(dotOffset)
    if (elementAtDot != null) {
        var current: PsiElement? = elementAtDot
        while (current != null && current !is KtFile) {
            if (current is KtDotQualifiedExpression) {
                return current.receiverExpression
            }
            current = current.parent
        }
    }

    // Try finding a receiver expression ending right before the dot,
    // skipping any whitespace between receiver and dot
    var receiverEnd = dotOffset
    while (receiverEnd > 0 && text[receiverEnd - 1].isWhitespace()) {
        receiverEnd--
    }
    return findReceiverExpressionEndingAtOffset(file, receiverEnd)
}

// --- KaSession extensions ---

/**
 * Builds a `KaScopeNameFilter` matching the case-insensitive `startsWith` semantics
 * used by client-side prefix filtering elsewhere in this file. An empty prefix
 * yields a permissive `{ true }` filter so scope queries still return all members
 * (e.g. right after a `.` with no characters typed yet).
 */
private fun scopeNamePrefixFilter(prefix: String): (Name) -> Boolean {
    if (prefix.isEmpty()) return { true }
    val lowerPrefix = prefix.lowercase()
    return { name -> name.asString().lowercase().startsWith(lowerPrefix) }
}

private fun org.jetbrains.kotlin.analysis.api.KaSession.collectMemberCompletions(
    receiverExpr: KtExpression,
    itemsArray: JsonArray,
    prefix: String = "",
    metrics: CompletionMetrics? = null,
) {
    // If the receiver is the class itself (e.g. `EnumName.`, `Foo.companionFn`,
    // `Outer.Inner`) rather than an instance value, walking instance scopes
    // returns only the inherited `Any` members. Divert to the static-qualifier
    // path before falling back to `expressionType` (sid-colf).
    val classReferenceSymbol = resolveClassReferenceReceiver(receiverExpr)
    if (classReferenceSymbol != null) {
        collectClassReferenceCompletions(
            classSymbol = classReferenceSymbol,
            itemsArray = itemsArray,
            prefix = prefix,
            metrics = metrics,
        )
        return
    }

    val receiverType = receiverExpr.expressionType ?: return
    val lowerPrefix = prefix.lowercase()
    val nameFilter = scopeNamePrefixFilter(prefix)

    val typeScope = receiverType.scope
    if (typeScope != null) {
        val callableSignatures = typeScope.getCallableSignatures(nameFilter)
        for (sig in callableSignatures) {
            val symbol = sig.symbol
            if (symbol is KaNamedSymbol) {
                val name = symbol.name.asString()
                if (name.startsWith("<")) continue
                if (lowerPrefix.isNotEmpty() && !name.lowercase().startsWith(lowerPrefix)) continue

                val returnType = metrics?.stage("detail_type_rendering") {
                    try {
                        sig.returnType.render(
                            KaTypeRendererForSource.WITH_SHORT_NAMES,
                            Variance.INVARIANT
                        )
                    } catch (_: Exception) { "" }
                } ?: try {
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

        val classifiers = typeScope.getClassifierSymbols(nameFilter)
        for (classifier in classifiers) {
            if (classifier is KaNamedSymbol) {
                val name = classifier.name.asString()
                if (name.startsWith("<")) continue
                if (lowerPrefix.isNotEmpty() && !name.lowercase().startsWith(lowerPrefix)) continue

                val item = JsonObject()
                item.addProperty("label", name)
                item.addProperty("kind", "class")
                item.addProperty("detail", "")
                item.addProperty("insertText", name)
                itemsArray.add(item)
            }
        }
    }

    if (receiverType is KaClassType) {
        val classSymbol = receiverType.symbol
        if (classSymbol is KaDeclarationContainerSymbol) {
            val memberScope = classSymbol.memberScope
            val existingLabels = mutableSetOf<String>()
            for (i in 0 until itemsArray.size()) {
                val label = itemsArray[i].asJsonObject.get("label")?.asString
                if (label != null) existingLabels.add(label)
            }

            for (callable in memberScope.callables(nameFilter)) {
                if (callable is KaNamedSymbol) {
                    val name = callable.name.asString()
                    if (name.startsWith("<") || name in existingLabels) continue
                    if (lowerPrefix.isNotEmpty() && !name.lowercase().startsWith(lowerPrefix)) continue
                    existingLabels.add(name)

                    val returnType = metrics?.stage("detail_type_rendering") {
                        try {
                            callable.returnType.render(
                                KaTypeRendererForSource.WITH_SHORT_NAMES,
                                Variance.INVARIANT
                            )
                        } catch (_: Exception) { "" }
                    } ?: try {
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
 * Instance callables inherited from `Any`. These are not legitimate completions
 * for a static qualifier such as `EnumName.` or `ClassName.`, so we strip them
 * from the class-reference completion path even when they accidentally surface
 * via inherited scopes.
 */
private val ANY_INSTANCE_CALLABLE_NAMES = setOf("toString", "equals", "hashCode")

/**
 * Sort-priority buckets for class-reference completion items. Lower numbers
 * sort first. The user-facing ordering puts the user's own declarations ahead
 * of synthetic / inherited material:
 *
 *  - Enum entries: the most specific thing to type after `EnumName.`.
 *  - Properties: includes companion `val`s and the synthetic `entries` property.
 *  - Functions: includes companion `fun`s and the synthetic `values()` /
 *    `valueOf()` methods. They are still useful but less common than entries.
 *  - Classifiers: nested classes and the bare `Companion` reference go last.
 */
private const val SORT_BUCKET_ENUM_ENTRY = "a"
private const val SORT_BUCKET_PROPERTY = "b"
private const val SORT_BUCKET_FUNCTION = "c"
private const val SORT_BUCKET_CLASSIFIER = "d"

private fun callableSortBucket(symbol: KaCallableSymbol): String = when (symbol) {
    is KaEnumEntrySymbol -> SORT_BUCKET_ENUM_ENTRY
    is KaVariableSymbol -> SORT_BUCKET_PROPERTY
    else -> SORT_BUCKET_FUNCTION
}

private fun classReferenceSortText(bucket: String, index: Int, label: String): String {
    // Zero-pad the index so the lexicographic comparison preserves insertion
    // order (which usually matches source declaration order from the scope).
    return "%s_%05d_%s".format(bucket, index, label)
}

/**
 * Resolves the receiver expression to a class-like symbol when the receiver is
 * the class itself rather than an instance value. Returns null for instance
 * receivers, objects (which are singletons and behave like instances), and
 * companion-object references, so callers can fall back to the existing
 * instance-receiver path.
 */
/**
 * Resolves the receiver expression to a class-like symbol when the receiver is
 * the class itself rather than an instance value. Returns null for plain
 * object/anonymous-object receivers (which act as singletons — the existing
 * instance-receiver path returns the correct members for them).
 *
 * Note: when the receiver is `Foo.` and `Foo` has a companion, K2 resolves the
 * reference to the companion's `KaClassSymbol` (kind = COMPANION_OBJECT). We
 * still treat that as a class-reference receiver so callers can walk the
 * companion's member scope and filter the inherited `Any` callables that are
 * cosmetically meaningless for a static qualifier.
 */
private fun org.jetbrains.kotlin.analysis.api.KaSession.resolveClassReferenceReceiver(
    receiverExpr: KtExpression,
): KaClassSymbol? {
    val refExpr = receiverExpr as? KtReferenceExpression ?: return null
    for (ref in refExpr.references) {
        if (ref !is KtReference) continue
        val symbol = try {
            ref.resolveToSymbol()
        } catch (_: Throwable) {
            continue
        }
        val classLike = when (symbol) {
            is KaClassSymbol -> symbol
            is KaTypeAliasSymbol -> (symbol.expandedType as? KaClassType)?.symbol as? KaClassSymbol
            else -> null
        } ?: continue
        return when (classLike.classKind) {
            KaClassKind.OBJECT,
            KaClassKind.ANONYMOUS_OBJECT -> null
            else -> classLike
        }
    }
    return null
}

/**
 * Collects completion candidates for a class-reference receiver (`ClassName.`):
 *
 *  - Enum entries plus the synthetic `values()`, `valueOf()`, `entries` come
 *    from the enum class's static-declared member scope.
 *  - Companion-instance callables (`ClassName.companionFn(...)`) come from
 *    walking the companion object's member scope. K2 resolves `ClassName` to
 *    the companion's `KaClassSymbol` directly when the class has a companion,
 *    so we walk that symbol's member scope; otherwise we look up
 *    `companionObject` on the surrounding `KaNamedClassSymbol`.
 *  - Nested classes/objects come from the static-declared classifier scope.
 *
 * Inherited instance `Any` callables (`toString`/`equals`/`hashCode`) are
 * filtered out because they are not callable through a static qualifier.
 */
private fun org.jetbrains.kotlin.analysis.api.KaSession.collectClassReferenceCompletions(
    classSymbol: KaClassSymbol,
    itemsArray: JsonArray,
    prefix: String,
    metrics: CompletionMetrics?,
) {
    val lowerPrefix = prefix.lowercase()
    val nameFilter = scopeNamePrefixFilter(prefix)
    val seenLabels = mutableSetOf<String>()
    val indexCounter = intArrayOf(0)

    if (classSymbol.classKind == KaClassKind.COMPANION_OBJECT) {
        // Receiver was `Foo.` where `Foo` has a companion — K2 resolved the
        // reference directly to the companion symbol. Walk its member scope to
        // expose companion callables (`Foo.create`, etc.) while still filtering
        // the inherited Any callables.
        val companionScope = classSymbol.memberScope
        addClassReferenceCallables(
            callables = companionScope.callables(nameFilter),
            itemsArray = itemsArray,
            seenLabels = seenLabels,
            lowerPrefix = lowerPrefix,
            indexCounter = indexCounter,
            metrics = metrics,
        )
        addClassReferenceClassifiers(
            classifiers = companionScope.classifiers(nameFilter),
            itemsArray = itemsArray,
            seenLabels = seenLabels,
            lowerPrefix = lowerPrefix,
            indexCounter = indexCounter,
        )
        return
    }

    val staticScope = classSymbol.staticDeclaredMemberScope
    addClassReferenceCallables(
        callables = staticScope.callables(nameFilter),
        itemsArray = itemsArray,
        seenLabels = seenLabels,
        lowerPrefix = lowerPrefix,
        indexCounter = indexCounter,
        metrics = metrics,
    )
    addClassReferenceClassifiers(
        classifiers = staticScope.classifiers(nameFilter),
        itemsArray = itemsArray,
        seenLabels = seenLabels,
        lowerPrefix = lowerPrefix,
        indexCounter = indexCounter,
    )

    if (classSymbol is KaNamedClassSymbol) {
        val companion = classSymbol.companionObject
        if (companion != null) {
            val companionScope = companion.memberScope
            addClassReferenceCallables(
                callables = companionScope.callables(nameFilter),
                itemsArray = itemsArray,
                seenLabels = seenLabels,
                lowerPrefix = lowerPrefix,
                indexCounter = indexCounter,
                metrics = metrics,
            )
            addClassReferenceClassifiers(
                classifiers = companionScope.classifiers(nameFilter),
                itemsArray = itemsArray,
                seenLabels = seenLabels,
                lowerPrefix = lowerPrefix,
                indexCounter = indexCounter,
            )
        }
    }
}

private fun org.jetbrains.kotlin.analysis.api.KaSession.addClassReferenceCallables(
    callables: Sequence<KaCallableSymbol>,
    itemsArray: JsonArray,
    seenLabels: MutableSet<String>,
    lowerPrefix: String,
    indexCounter: IntArray,
    metrics: CompletionMetrics?,
) {
    for (callable in callables) {
        if (callable !is KaNamedSymbol) continue
        val name = callable.name.asString()
        if (name.startsWith("<")) continue
        if (lowerPrefix.isNotEmpty() && !name.lowercase().startsWith(lowerPrefix)) continue
        if (name in ANY_INSTANCE_CALLABLE_NAMES) continue
        if (!seenLabels.add(name)) continue

        val returnType = metrics?.stage("detail_type_rendering") {
            try {
                callable.returnType.render(
                    KaTypeRendererForSource.WITH_SHORT_NAMES,
                    Variance.INVARIANT,
                )
            } catch (_: Exception) { "" }
        } ?: try {
            callable.returnType.render(
                KaTypeRendererForSource.WITH_SHORT_NAMES,
                Variance.INVARIANT,
            )
        } catch (_: Exception) { "" }

        val item = JsonObject()
        item.addProperty("label", name)
        item.addProperty("kind", symbolToCompletionKind(callable))
        item.addProperty("detail", returnType)

        if (callable is KaFunctionSymbol) {
            if (callable.valueParameters.isEmpty()) {
                item.addProperty("insertText", "$name()")
            } else {
                item.addProperty("insertText", "$name(")
            }
        } else {
            item.addProperty("insertText", name)
        }
        item.addProperty(
            "sortText",
            classReferenceSortText(
                bucket = callableSortBucket(callable),
                index = indexCounter[0]++,
                label = name,
            ),
        )
        itemsArray.add(item)
    }
}

private fun addClassReferenceClassifiers(
    classifiers: Sequence<KaClassifierSymbol>,
    itemsArray: JsonArray,
    seenLabels: MutableSet<String>,
    lowerPrefix: String,
    indexCounter: IntArray,
) {
    for (classifier in classifiers) {
        if (classifier !is KaNamedSymbol) continue
        val name = classifier.name.asString()
        if (name.startsWith("<")) continue
        if (lowerPrefix.isNotEmpty() && !name.lowercase().startsWith(lowerPrefix)) continue
        if (!seenLabels.add(name)) continue

        val item = JsonObject()
        item.addProperty("label", name)
        item.addProperty("kind", "class")
        item.addProperty("detail", "")
        item.addProperty("insertText", name)
        item.addProperty(
            "sortText",
            classReferenceSortText(
                bucket = SORT_BUCKET_CLASSIFIER,
                index = indexCounter[0]++,
                label = name,
            ),
        )
        itemsArray.add(item)
    }
}

private fun org.jetbrains.kotlin.analysis.api.KaSession.collectScopeCompletionCandidates(
    ktFile: KtFile,
    element: PsiElement,
    prefix: String = "",
    metrics: CompletionMetrics? = null,
): List<ScopeCompletionCandidate> {
    val candidates = mutableListOf<ScopeCompletionCandidate>()
    val nameFilter = scopeNamePrefixFilter(prefix)

    fun addCandidate(item: JsonObject) {
        val label = item.get("label")?.asString ?: return
        candidates.add(
            ScopeCompletionCandidate(
                label = label,
                source = ScopeCompletionSource.VISIBLE_SCOPE,
                item = item,
            )
        )
    }

    val ktElement = PsiTreeUtil.getParentOfType(element, KtElement::class.java, false)
        ?: element as? KtElement

    if (ktElement != null) {
        try {
            val scopeCtx = ktFile.scopeContext(ktElement)
            val compositeScope = scopeCtx.compositeScope { true }

            val seen = mutableSetOf<String>()

            for (callable in compositeScope.callables(nameFilter)) {
                if (callable !is KaNamedSymbol) continue

                val name = callable.name.asString()
                if (name.startsWith("<") || !seen.add(name)) continue

                val returnType = metrics?.stage("detail_type_rendering") {
                    try {
                        callable.returnType.render(
                            KaTypeRendererForSource.WITH_SHORT_NAMES,
                            Variance.INVARIANT
                        )
                    } catch (_: Exception) { "" }
                } ?: try {
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

                addCandidate(item)
            }

            for (classifier in compositeScope.classifiers(nameFilter)) {
                if (classifier !is KaNamedSymbol) continue

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
                addCandidate(item)
            }
        } catch (e: Exception) {
            System.err.println("CompilerBridge: scope completion failed, falling back to PSI: ${e.message}")
            candidates += collectPsiScopeCompletionCandidates(element)
        }
    } else {
        candidates += collectPsiScopeCompletionCandidates(element)
    }

    return candidates
}

// --- Import completion (needs BridgeContext for classpathFqns and symbolIndex) ---

private fun BridgeContext.collectImportCompletionItems(
    importDirective: KtImportDirective,
    offset: Int,
): List<JsonObject> {
    val importText = importDirective.text
    val importKeyword = "import "
    val importStart = importDirective.textRange.startOffset
    val relativeOffset = offset - importStart
    val textUpToCursor = if (relativeOffset in 0..importText.length) {
        importText.substring(0, relativeOffset)
    } else {
        importText
    }
    val fqnUpToCursor = textUpToCursor.removePrefix(importKeyword).trim()

    val afterDot = fqnUpToCursor.endsWith(".")
    val packagePrefix: String
    val namePrefix: String
    if (afterDot) {
        packagePrefix = fqnUpToCursor.dropLast(1)
        namePrefix = ""
    } else if (fqnUpToCursor.contains('.')) {
        packagePrefix = fqnUpToCursor.substringBeforeLast('.')
        namePrefix = fqnUpToCursor.substringAfterLast('.')
    } else {
        packagePrefix = ""
        namePrefix = fqnUpToCursor
    }

    val items = mutableListOf<JsonObject>()
    val seen = mutableSetOf<String>()

    val searchFqnPrefix = if (packagePrefix.isEmpty()) "" else "$packagePrefix."

    for (fqn in classpathFqns) {
        if (searchFqnPrefix.isNotEmpty() && !fqn.startsWith(searchFqnPrefix)) continue
        if (searchFqnPrefix.isEmpty() && fqn.indexOf('.') < 0) continue

        val remainder = if (searchFqnPrefix.isNotEmpty()) {
            fqn.removePrefix(searchFqnPrefix)
        } else {
            fqn
        }
        val nextSegment = remainder.substringBefore('.')
        val isSubPackage = remainder.contains('.')

        if (nextSegment.isEmpty()) continue
        if (namePrefix.isNotEmpty() && !nextSegment.lowercase().startsWith(namePrefix.lowercase())) continue

        if (isSubPackage) {
            if (seen.add("pkg:$nextSegment")) {
                val item = JsonObject()
                item.addProperty("label", nextSegment)
                item.addProperty("kind", "module")
                item.addProperty("detail", if (packagePrefix.isEmpty()) nextSegment else "$packagePrefix.$nextSegment")
                item.addProperty("insertText", nextSegment)
                items.add(item)
            }
        } else {
            if (seen.add("type:$nextSegment")) {
                val item = JsonObject()
                item.addProperty("label", nextSegment)
                item.addProperty("kind", "class")
                item.addProperty("detail", fqn)
                item.addProperty("insertText", nextSegment)
                items.add(item)
            }
        }
    }

    val projectDecls = symbolIndex.searchByFqnPrefix(searchFqnPrefix, limit = 200)
    for (decl in projectDecls) {
        val fqn = decl.fqn ?: continue
        val remainder = fqn.removePrefix(searchFqnPrefix)
        val nextSegment = remainder.substringBefore('.')
        val isSubPackage = remainder.contains('.')

        if (nextSegment.isEmpty()) continue
        if (namePrefix.isNotEmpty() && !nextSegment.lowercase().startsWith(namePrefix.lowercase())) continue

        if (isSubPackage) {
            if (seen.add("pkg:$nextSegment")) {
                val item = JsonObject()
                item.addProperty("label", nextSegment)
                item.addProperty("kind", "module")
                item.addProperty("detail", if (packagePrefix.isEmpty()) nextSegment else "$packagePrefix.$nextSegment")
                item.addProperty("insertText", nextSegment)
                items.add(item)
            }
        } else {
            if (seen.add("type:$nextSegment")) {
                val item = JsonObject()
                item.addProperty("label", nextSegment)
                item.addProperty("kind", decl.kind)
                item.addProperty("detail", fqn)
                item.addProperty("insertText", nextSegment)
                items.add(item)
            }
        }
    }

    items.sortWith(compareBy(
        { if (it.get("kind")?.asString == "module") 0 else 1 },
        { it.get("label")?.asString?.lowercase() ?: "" },
    ))

    return items
}

// --- Prefix extraction (pure PSI) ---

private fun extractCompletionPrefix(element: PsiElement, offset: Int): String {
    val fileText = element.containingFile?.text ?: return ""
    var start = offset
    while (start > 0 && (fileText[start - 1].isLetterOrDigit() || fileText[start - 1] == '_')) {
        start--
    }
    if (start >= offset) return ""
    return fileText.substring(start, offset)
}

// --- Unimported completion candidates (needs BridgeContext for symbolIndex) ---

private fun BridgeContext.collectUnimportedCompletionCandidates(
    ktFile: KtFile,
    prefix: String,
    existingLabels: Set<String>,
    metrics: CompletionMetrics? = null,
): List<ScopeCompletionCandidate> {
    if (prefix.isEmpty()) return emptyList()

    val existingImports = ktFile.importDirectives
        .mapNotNull { it.importedFqName?.asString() }
        .toSet()

    val candidates = symbolIndex.searchSymbols(prefix, limit = 20)
        .filter { it.fqn != null && it.fqn !in existingImports && it.shortName !in existingLabels }

    if (candidates.isEmpty()) return emptyList()

    val document = ktFile.viewProvider.document ?: return emptyList()

    return candidates.map { decl ->
        val item = JsonObject()
        item.addProperty("label", decl.shortName)
        item.addProperty("kind", decl.kind)
        item.addProperty("detail", decl.fqn)
        item.addProperty("insertText", decl.shortName)

        val editsArray = JsonArray()
        val edit = JsonObject()
        val (importLine, newText) = metrics?.stage("import_edit_construction") {
            computeImportInsertion(ktFile, document, decl.fqn!!)
        } ?: computeImportInsertion(ktFile, document, decl.fqn!!)
        edit.addProperty("newText", newText)
        edit.addProperty("line", importLine)
        edit.addProperty("column", 0)
        edit.addProperty("endLine", importLine)
        edit.addProperty("endColumn", 0)
        editsArray.add(edit)
        item.add("additionalTextEdits", editsArray)

        ScopeCompletionCandidate(
            label = decl.shortName,
            source = ScopeCompletionSource.AUTO_IMPORT,
            item = item,
        )
    }
}

// --- PSI-based scope completion (pure PSI) ---

private fun collectPsiScopeCompletionCandidates(element: PsiElement): List<ScopeCompletionCandidate> {
    val candidates = mutableListOf<ScopeCompletionCandidate>()
    val seen = mutableSetOf<String>()
    var current: PsiElement? = element.parent

    fun addCandidate(item: JsonObject) {
        val label = item.get("label")?.asString ?: return
        candidates.add(
            ScopeCompletionCandidate(
                label = label,
                source = ScopeCompletionSource.VISIBLE_SCOPE,
                item = item,
            )
        )
    }

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
                    addCandidate(item)
                }
            }
        }
        if (current is KtFunction) {
            for (param in current.valueParameters) {
                val name = param.name ?: continue
                if (!seen.add(name)) continue

                val item = JsonObject()
                item.addProperty("label", name)
                item.addProperty("kind", "variable")
                item.addProperty("detail", param.typeReference?.text ?: "")
                item.addProperty("insertText", name)
                addCandidate(item)
            }
        }
        current = current.parent
    }

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
            addCandidate(item)
        }
    }

    return candidates
}

// --- Keyword completions (pure data) ---

private fun keywordCompletionCandidates(): List<ScopeCompletionCandidate> {
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

    return keywords.map { keyword ->
        val item = JsonObject()
        item.addProperty("label", keyword)
        item.addProperty("kind", "keyword")
        item.addProperty("detail", "")
        item.addProperty("insertText", keyword)
        ScopeCompletionCandidate(
            label = keyword,
            source = ScopeCompletionSource.KEYWORD,
            item = item,
        )
    }
}

// --- Ranking logic (pure) ---

private fun rankScopeCompletionCandidates(
    prefix: String,
    candidates: List<ScopeCompletionCandidate>,
): List<JsonObject> {
    val normalizedPrefix = prefix.lowercase()

    return candidates
        .mapIndexed { index, candidate ->
            RankedScopeCompletionCandidate(
                candidate = candidate,
                sourcePriority = sourcePriority(candidate.source),
                matchPriority = matchPriority(
                    label = candidate.label,
                    prefix = prefix,
                    normalizedPrefix = normalizedPrefix,
                ),
                originalIndex = index,
            )
        }
        .sortedWith(
            compareBy<RankedScopeCompletionCandidate>(
                { it.sourcePriority },
                { it.matchPriority },
                { it.candidate.label.lowercase() },
                { it.candidate.label },
                { it.originalIndex },
            )
        )
        .map { it.candidate.item }
}

private fun sourcePriority(source: ScopeCompletionSource): Int = when (source) {
    ScopeCompletionSource.VISIBLE_SCOPE -> 0
    ScopeCompletionSource.KEYWORD -> 1
    ScopeCompletionSource.AUTO_IMPORT -> 2
}

private fun matchPriority(label: String, prefix: String, normalizedPrefix: String): Int {
    if (normalizedPrefix.isEmpty()) return 0

    val normalizedLabel = label.lowercase()
    return when {
        label == prefix -> 0
        normalizedLabel == normalizedPrefix -> 1
        label.startsWith(prefix) -> 2
        normalizedLabel.startsWith(normalizedPrefix) -> 3
        else -> 4
    }
}

// --- Classification helpers (pure) ---

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
