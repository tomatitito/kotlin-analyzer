package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path

private data class BodyAnalysis(val referencesThis: Boolean, val accessesPrivateMembers: Boolean)

internal fun BridgeContext.codeActions(uri: String, line: Int, character: Int): JsonObject {
    val result = JsonObject()
    val actionsArray = JsonArray()
    val perfStart = System.currentTimeMillis()

    System.err.println("CompilerBridge: codeActions($uri, line=$line, char=$character)")

    ensureSessionCurrent()

    val currentSession = session ?: run {
        System.err.println("CompilerBridge: codeActions — session is NULL")
        result.add("actions", actionsArray)
        return result
    }
    val ktFile = findKtFile(currentSession, uri) ?: run {
        System.err.println("CompilerBridge: codeActions — file not found")
        result.add("actions", actionsArray)
        return result
    }

    try {
        analyze(ktFile) {
            val offset = this@codeActions.lineColToOffset(ktFile, line, character) ?: run {
                result.add("actions", actionsArray)
                return@analyze
            }

            val document = ktFile.viewProvider.document
            if (document == null) {
                result.add("actions", actionsArray)
                return@analyze
            }

            val fileUri = uri

            val ctx = this@codeActions

            // 1. Diagnostic-based code actions
            try {
                val diagnostics = ktFile.collectDiagnostics(
                    KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
                )

                for (diagnostic in diagnostics) {
                    val textRange = diagnostic.textRanges.firstOrNull() ?: continue

                    val diagStartLine = document.getLineNumber(textRange.startOffset)
                    val diagEndLine = document.getLineNumber(textRange.endOffset)
                    val cursorLine = line - 1

                    if (cursorLine < diagStartLine || cursorLine > diagEndLine) continue

                    val factoryName = diagnostic.factoryName ?: continue
                    val severityName = diagnostic.severity.name

                    if (severityName == "WARNING") {
                        val action = JsonObject()
                        action.addProperty("title", "Suppress warning '$factoryName'")
                        action.addProperty("kind", "quickfix")

                        val actionEdits = JsonArray()

                        val element = ktFile.findElementAt(textRange.startOffset)
                        val enclosingDeclaration = ctx.findEnclosingDeclaration(element)

                        if (enclosingDeclaration != null) {
                            val declOffset = enclosingDeclaration.textOffset
                            val declLine = document.getLineNumber(declOffset) + 1
                            val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
                            val declCol = declOffset - declLineStart

                            val lineEndOffset = document.getLineEndOffset(document.getLineNumber(declOffset))
                            val lineText = document.charsSequence.subSequence(declLineStart, lineEndOffset).toString()
                            val indent = lineText.takeWhile { it == ' ' || it == '\t' }

                            val suppressEdit = JsonObject()
                            suppressEdit.addProperty("uri", fileUri)
                            val suppressRange = JsonObject()
                            suppressRange.addProperty("startLine", declLine)
                            suppressRange.addProperty("startColumn", declCol)
                            suppressRange.addProperty("endLine", declLine)
                            suppressRange.addProperty("endColumn", declCol)
                            suppressEdit.add("range", suppressRange)
                            suppressEdit.addProperty("newText", "@Suppress(\"$factoryName\")\n$indent")
                            actionEdits.add(suppressEdit)
                        }

                        action.add("edits", actionEdits)
                        actionsArray.add(action)
                    }

                    if (factoryName == "UNRESOLVED_REFERENCE") {
                        addTypeImportCodeActions(
                            ctx = ctx,
                            uri = fileUri,
                            ktFile = ktFile,
                            document = document,
                            factoryName = factoryName,
                            startOffset = textRange.startOffset,
                            endOffset = textRange.endOffset,
                            actionsArray = actionsArray,
                        )
                    }
                }
            } catch (e: Exception) {
                System.err.println("CompilerBridge: diagnostic code actions failed: ${e.message}")
            }

            // 2. Context-aware refactoring actions
            val element = ktFile.findElementAt(offset)
            if (element != null) {
                addInterfaceNavigationActions(ctx, element, actionsArray)
                addInterfaceSignaturePropagationAction(ctx, element, document, actionsArray)
                addOpenOrCreateUnitTestAction(ctx, element, fileUri, actionsArray)

                addExplicitTypeAction(ctx, element, document, fileUri, actionsArray)
                addConvertBodyAction(ctx, element, document, fileUri, actionsArray)
                addMoveToCompanionAction(ctx, element, document, fileUri, actionsArray)
                addMoveFromCompanionAction(ctx, element, document, fileUri, actionsArray)
                addMoveToTopLevelAction(ctx, element, document, fileUri, actionsArray, ktFile)
                addConvertToExtensionFunctionAction(ctx, element, document, fileUri, actionsArray)
            }

            // 3. Source actions: Organize imports
            addOrganizeImportsAction(ctx, ktFile, document, fileUri, actionsArray)
        }
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: codeActions failed: ${e.javaClass.name}: ${e.message}")
        e.printStackTrace(System.err)
    }

    System.err.println("CompilerBridge: codeActions($uri) — returning ${actionsArray.size()} action(s)")
    for (i in 0 until actionsArray.size()) {
        val action = actionsArray[i].asJsonObject
        System.err.println("  action[$i]: ${action.get("title")?.asString} (kind=${action.get("kind")?.asString})")
    }

    System.err.println("[PERF] method=codeActions uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
    result.add("actions", actionsArray)
    return result
}

private fun KaSession.addInterfaceNavigationActions(
    ctx: BridgeContext,
    element: PsiElement,
    actionsArray: JsonArray,
) {
    val target = when (element) {
        is PsiWhiteSpace, is PsiComment -> element.prevSibling ?: element.parent
        else -> element
    } ?: return

    val classOrObject = PsiTreeUtil.getParentOfType(target, KtClassOrObject::class.java, false) ?: return
    val nameIdentifier = classOrObject.nameIdentifier ?: return
    val interestingElement = sequenceOf(target, target.parent)
        .filterNotNull()
        .firstOrNull { candidate ->
            candidate == nameIdentifier ||
                candidate.node?.elementType == KtTokens.CLASS_KEYWORD ||
                candidate.node?.elementType == KtTokens.OBJECT_KEYWORD ||
                candidate.node?.elementType == KtTokens.INTERFACE_KEYWORD
        } ?: return

    val targets = when {
        classOrObject is KtClass && classOrObject.isInterface() ->
            findImplementationNavigationTargets(ctx, classOrObject)
        interestingElement == nameIdentifier ||
            interestingElement.node?.elementType == KtTokens.CLASS_KEYWORD ||
            interestingElement.node?.elementType == KtTokens.OBJECT_KEYWORD ->
            findInterfaceNavigationTargets(ctx, classOrObject)
        else -> emptyList()
    }

    targets
        .sortedBy { it.title }
        .forEach { targetInfo ->
            actionsArray.add(
                JsonObject().apply {
                    addProperty("title", targetInfo.title)
                    addProperty("kind", "refactor")
                    add("command", buildOpenTargetCommand(targetInfo))
                }
            )
        }
}

private fun addOpenOrCreateUnitTestAction(
    ctx: BridgeContext,
    element: PsiElement,
    fileUri: String,
    actionsArray: JsonArray,
) {
    val targetClass = findUnitTestActionClass(element) ?: return
    val className = targetClass.name ?: return
    val packageName = targetClass.containingKtFile.packageFqName.asString()
    val sourcePath = Path.of(ctx.uriToPath(fileUri))
    val plan = KotlinUnitTestPlanner.plan(sourcePath, packageName, className)
    if (plan is KotlinUnitTestPlan.Unavailable) {
        return
    }

    val action = JsonObject().apply {
        addProperty("title", "Open or Create Unit Test")
        addProperty("kind", "refactor")
        add("command", buildUnitTestCommand(plan))
    }
    actionsArray.add(action)
}

private fun findUnitTestActionClass(element: PsiElement): KtClass? {
    val target = when (element) {
        is PsiWhiteSpace, is PsiComment -> element.prevSibling ?: element.parent
        else -> element
    } ?: return null

    val ktClass = PsiTreeUtil.getParentOfType(target, KtClass::class.java, false) ?: return null
    val nameIdentifier = ktClass.nameIdentifier
    val interestingElement = sequenceOf(target, target.parent)
        .filterNotNull()
        .firstOrNull { candidate ->
            candidate == nameIdentifier || candidate.node?.elementType == KtTokens.CLASS_KEYWORD
        }

    return if (interestingElement != null) ktClass else null
}

private fun buildUnitTestCommand(plan: KotlinUnitTestPlan): JsonObject {
    return when (plan) {
        is KotlinUnitTestPlan.Existing -> JsonObject().apply {
            addProperty("title", "Open or Create Unit Test")
            addProperty("command", AnalyzerCommands.OPEN_TEST_TARGET)
            add(
                "arguments",
                JsonArray().also { arguments ->
                    arguments.add(
                        JsonObject().apply {
                            addProperty(AnalyzerCommands.ARG_TARGET_URI, plan.targetUri)
                            add(
                                AnalyzerCommands.ARG_SELECTION,
                                plan.selection.toJson(),
                            )
                        }
                    )
                },
            )
        }
        is KotlinUnitTestPlan.Create -> JsonObject().apply {
            addProperty("title", "Open or Create Unit Test")
            addProperty("command", AnalyzerCommands.CREATE_AND_OPEN_TEST_TARGET)
            add(
                "arguments",
                JsonArray().also { arguments ->
                    arguments.add(
                        JsonObject().apply {
                            addProperty(AnalyzerCommands.ARG_TARGET_URI, plan.targetUri)
                            addProperty(AnalyzerCommands.ARG_TARGET_PATH, plan.targetPath.toString())
                            addProperty(AnalyzerCommands.ARG_INITIAL_CONTENTS, plan.initialContents)
                            add(
                                AnalyzerCommands.ARG_SELECTION,
                                plan.selection.toJson(),
                            )
                        }
                    )
                },
            )
        }
        is KotlinUnitTestPlan.Unavailable -> error("unavailable plan should not be converted to a command")
    }
}

private fun buildOpenTargetCommand(target: NavigationTarget): JsonObject {
    return JsonObject().apply {
        addProperty("title", target.title)
        addProperty("command", AnalyzerCommands.OPEN_TARGET)
        add(
            "arguments",
            JsonArray().also { arguments ->
                arguments.add(
                    JsonObject().apply {
                        addProperty(AnalyzerCommands.ARG_TARGET_URI, target.targetUri)
                        add(AnalyzerCommands.ARG_SELECTION, target.selection)
                    }
                )
            },
        )
    }
}

private fun TestTargetSelection.toJson(): JsonObject {
    return JsonObject().apply {
        addProperty(AnalyzerCommands.ARG_START_LINE, startLine)
        addProperty(AnalyzerCommands.ARG_START_CHARACTER, startCharacter)
        addProperty(AnalyzerCommands.ARG_END_LINE, endLine)
        addProperty(AnalyzerCommands.ARG_END_CHARACTER, endCharacter)
    }
}

/**
 * Adds "Add explicit type" code action for properties/variables that lack a type annotation.
 */
private fun KaSession.addExplicitTypeAction(
    ctx: BridgeContext,
    element: PsiElement,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    actionsArray: JsonArray,
) {
    try {
        val property = PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
        if (property != null && property.typeReference == null && property.initializer != null) {
            val nameIdentifier = property.nameIdentifier ?: return
            val symbol = property.symbol
            val returnType = symbol.returnType
            val rendered = returnType.render(
                org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource.WITH_SHORT_NAMES,
                org.jetbrains.kotlin.types.Variance.INVARIANT
            )

            if (rendered.isNotEmpty() && rendered != "Unit" && rendered != "Nothing") {
                val action = JsonObject()
                action.addProperty("title", "Add explicit type '$rendered'")
                action.addProperty("kind", "refactor")

                val actionEdits = JsonArray()
                val insertOffset = nameIdentifier.textRange.endOffset
                val insertLine = document.getLineNumber(insertOffset) + 1
                val insertLineStart = document.getLineStartOffset(document.getLineNumber(insertOffset))
                val insertCol = insertOffset - insertLineStart

                val edit = JsonObject()
                edit.addProperty("uri", fileUri)
                val range = JsonObject()
                range.addProperty("startLine", insertLine)
                range.addProperty("startColumn", insertCol)
                range.addProperty("endLine", insertLine)
                range.addProperty("endColumn", insertCol)
                edit.add("range", range)
                edit.addProperty("newText", ": $rendered")
                actionEdits.add(edit)

                action.add("edits", actionEdits)
                actionsArray.add(action)
            }
        }
    } catch (_: Exception) {
        // Ignore failures in type resolution
    }
}

/**
 * Adds "Convert to expression body" / "Convert to block body" code action for functions.
 */
private fun addConvertBodyAction(
    ctx: BridgeContext,
    element: PsiElement,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    actionsArray: JsonArray,
) {
    try {
        val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
            ?: return

        val body = function.bodyBlockExpression
        val expressionBody = function.bodyExpression

        if (body != null && body.statements.size == 1) {
            val singleStatement = body.statements.first()
            val expr = if (singleStatement is KtReturnExpression) {
                singleStatement.returnedExpression
            } else {
                singleStatement
            }

            if (expr != null) {
                val action = JsonObject()
                action.addProperty("title", "Convert to expression body")
                action.addProperty("kind", "refactor")

                val actionEdits = JsonArray()

                val bodyStart = body.textOffset
                val bodyEnd = body.textRange.endOffset

                val startLine = document.getLineNumber(bodyStart) + 1
                val startLineStart = document.getLineStartOffset(document.getLineNumber(bodyStart))
                val startCol = bodyStart - startLineStart

                val endLine = document.getLineNumber(bodyEnd) + 1
                val endLineStart = document.getLineStartOffset(document.getLineNumber(bodyEnd))
                val endCol = bodyEnd - endLineStart

                val edit = JsonObject()
                edit.addProperty("uri", fileUri)
                val range = JsonObject()
                range.addProperty("startLine", startLine)
                range.addProperty("startColumn", startCol)
                range.addProperty("endLine", endLine)
                range.addProperty("endColumn", endCol)
                edit.add("range", range)
                edit.addProperty("newText", "= ${expr.text}")
                actionEdits.add(edit)

                action.add("edits", actionEdits)
                actionsArray.add(action)
            }
        } else if (expressionBody != null && body == null) {
            val equalsToken = function.equalsToken ?: return

            val action = JsonObject()
            action.addProperty("title", "Convert to block body")
            action.addProperty("kind", "refactor")

            val actionEdits = JsonArray()

            val exprStart = equalsToken.textOffset
            val exprEnd = expressionBody.textRange.endOffset

            val startLine = document.getLineNumber(exprStart) + 1
            val startLineStart = document.getLineStartOffset(document.getLineNumber(exprStart))
            val startCol = exprStart - startLineStart

            val endLine = document.getLineNumber(exprEnd) + 1
            val endLineStart = document.getLineStartOffset(document.getLineNumber(exprEnd))
            val endCol = exprEnd - endLineStart

            val funcOffset = function.textOffset
            val funcLineStart = document.getLineStartOffset(document.getLineNumber(funcOffset))
            val funcLineEnd = document.getLineEndOffset(document.getLineNumber(funcOffset))
            val funcLineText = document.charsSequence.subSequence(funcLineStart, funcLineEnd).toString()
            val indent = funcLineText.takeWhile { it == ' ' || it == '\t' }

            val hasReturnType = function.typeReference != null

            val edit = JsonObject()
            edit.addProperty("uri", fileUri)
            val range = JsonObject()
            range.addProperty("startLine", startLine)
            range.addProperty("startColumn", startCol)
            range.addProperty("endLine", endLine)
            range.addProperty("endColumn", endCol)
            edit.add("range", range)

            val returnKeyword = if (hasReturnType) "return " else ""
            edit.addProperty("newText", "{\n$indent    ${returnKeyword}${expressionBody.text}\n$indent}")
            actionEdits.add(edit)

            action.add("edits", actionEdits)
            actionsArray.add(action)
        }
    } catch (_: Exception) {
        // Ignore failures in function body analysis
    }
}

/**
 * Adds "Organize imports" source action.
 */
private fun addOrganizeImportsAction(
    ctx: BridgeContext,
    ktFile: KtFile,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    actionsArray: JsonArray,
) {
    val importList = ktFile.importList ?: return
    val imports = importList.imports
    if (imports.isEmpty()) return

    val importPaths = imports.mapNotNull { it.importedFqName?.asString() }.distinct().sorted()

    val currentPaths = imports.mapNotNull { it.importedFqName?.asString() }
    if (currentPaths == importPaths) return

    val newImportText = importPaths.joinToString("\n") { "import $it" }

    val firstImport = imports.first()
    val lastImport = imports.last()
    val startLine = document.getLineNumber(firstImport.textRange.startOffset) + 1
    val startCol = 0
    val endLine = document.getLineNumber(lastImport.textRange.endOffset) + 1
    val endLineStart = document.getLineStartOffset(document.getLineNumber(lastImport.textRange.endOffset))
    val endCol = lastImport.textRange.endOffset - endLineStart

    val action = JsonObject()
    action.addProperty("title", "Organize imports")
    action.addProperty("kind", "source.organizeImports")

    val editsArray = JsonArray()
    val edit = JsonObject()
    edit.addProperty("uri", fileUri)
    val range = JsonObject()
    range.addProperty("startLine", startLine)
    range.addProperty("startColumn", startCol)
    range.addProperty("endLine", endLine)
    range.addProperty("endColumn", endCol)
    edit.add("range", range)
    edit.addProperty("newText", newImportText)
    editsArray.add(edit)

    action.add("edits", editsArray)
    actionsArray.add(action)
}

private fun KaSession.analyzeBodyReferences(
    ctx: BridgeContext,
    declaration: KtDeclaration,
    containingClass: KtClass,
): BodyAnalysis {
    val body = when (declaration) {
        is KtNamedFunction -> declaration.bodyExpression
        is KtProperty -> declaration.initializer
        else -> return BodyAnalysis(false, false)
    } ?: return BodyAnalysis(false, false)

    val hasExplicitThis = PsiTreeUtil.findChildrenOfType(body, KtThisExpression::class.java).isNotEmpty()

    var refsThis = hasExplicitThis
    var accessesPrivate = false

    try {
        val classSymbol = containingClass.symbol
        for (nameExpr in PsiTreeUtil.findChildrenOfType(body, KtSimpleNameExpression::class.java)) {
            if (nameExpr is KtOperationReferenceExpression) continue
            try {
                for (ref in nameExpr.references.filterIsInstance<KtReference>()) {
                    for (target in ref.resolveToSymbols()) {
                        if (target is KaCallableSymbol && target.containingSymbol == classSymbol) {
                            refsThis = true
                            if (target.visibility == KaSymbolVisibility.PRIVATE) {
                                accessesPrivate = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip unresolvable references
            }
            if (refsThis && accessesPrivate) break
        }
    } catch (_: Exception) {
        val memberNames = mutableSetOf<String>()
        val privateNames = mutableSetOf<String>()
        containingClass.body?.declarations?.forEach { decl ->
            if (decl != declaration && decl is KtNamedDeclaration) {
                decl.name?.let { memberNames.add(it) }
                if (decl.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
                    decl.name?.let { privateNames.add(it) }
                }
            }
        }
        for (nameExpr in PsiTreeUtil.findChildrenOfType(body, KtSimpleNameExpression::class.java)) {
            if (nameExpr is KtOperationReferenceExpression) continue
            val name = nameExpr.getReferencedName()
            if (name in memberNames) refsThis = true
            if (name in privateNames) accessesPrivate = true
            if (refsThis && accessesPrivate) break
        }
    }

    return BodyAnalysis(refsThis, accessesPrivate)
}

private fun addTypeImportCodeActions(
    ctx: BridgeContext,
    uri: String,
    ktFile: KtFile,
    document: com.intellij.openapi.editor.Document,
    factoryName: String?,
    startOffset: Int,
    endOffset: Int,
    actionsArray: JsonArray,
) {
    val plan = ctx.planTypeImportAssist(ktFile, factoryName, startOffset, endOffset) ?: return
    if (plan.candidates.isEmpty()) return

    for (candidate in plan.candidates) {
        val importEdit = ctx.buildImportEdit(uri, ktFile, document, candidate.fqn) ?: continue
        val action = JsonObject()
        val title = if (plan.candidates.size == 1) {
            "Import '${plan.shortName}'"
        } else {
            "Import '${plan.shortName}' from ${candidate.fqn}"
        }
        action.addProperty("title", title)
        action.addProperty("kind", "quickfix")

        val actionEdits = JsonArray()
        actionEdits.add(importEdit)
        action.add("edits", actionEdits)
        actionsArray.add(action)
    }
}

/**
 * Adds "Update interface signature" or "Update implementation signatures" code action
 * when an override function's parameter list diverges from its interface declaration
 * (or vice versa) by pure parameter additions.
 *
 * Uses PSI-based supertype name matching (not symbol resolution) so that it works
 * correctly with LightVirtualFiles from virtual editor changes.
 */
private fun KaSession.addInterfaceSignaturePropagationAction(
    ctx: BridgeContext,
    element: PsiElement,
    document: com.intellij.openapi.editor.Document,
    actionsArray: JsonArray,
) {
    try {
        val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false) ?: return
        val funcName = function.name ?: return

        val containingClassOrObject = PsiTreeUtil.getParentOfType(function, KtClassOrObject::class.java) ?: return

        if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
            // On an override: offer to update the interface signature
            val supertypeNames = containingClassOrObject.superTypeListEntries
                .mapNotNull { it.typeReference?.text?.substringBefore("<")?.trim() }

            // Search session files for interfaces that match supertypes and have the function
            val interfaceMatches = ctx.allSessionKtFiles().flatMap { file ->
                PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java)
                    .filter { cls ->
                        cls is KtClass && cls.isInterface() &&
                            cls.name in supertypeNames &&
                            PsiTreeUtil.collectElementsOfType(cls, KtNamedFunction::class.java)
                                .any { it.name == funcName }
                    }
            }

            if (interfaceMatches.size != 1) return  // ambiguous or no match

            val interfaceClass = interfaceMatches.single()
            val interfaceFunc = PsiTreeUtil.collectElementsOfType(interfaceClass, KtNamedFunction::class.java)
                .firstOrNull { it.name == funcName } ?: return

            val overrideParams = function.valueParameters
            val interfaceParams = interfaceFunc.valueParameters

            if (overrideParams.size <= interfaceParams.size) return
            if (!isParameterAddition(interfaceParams, overrideParams)) return

            val interfaceFile = interfaceClass.containingFile as? KtFile ?: return
            val interfaceDoc = interfaceFile.viewProvider.document ?: return
            val interfaceUri = "file://${interfaceFile.virtualFile.path}"

            val newParamText = overrideParams.joinToString(", ") { it.text }
            val paramListRange = interfaceFunc.valueParameterList ?: return
            val (startLine, startCol) = ctx.offsetToLineCol(interfaceDoc, paramListRange.textOffset)
            val (endLine, endCol) = ctx.offsetToLineCol(interfaceDoc, paramListRange.textRange.endOffset)

            val action = JsonObject()
            action.addProperty("title", "Update interface signature")
            action.addProperty("kind", "refactor")
            val edits = JsonArray()
            edits.add(ctx.makeEdit(interfaceUri, startLine, startCol, endLine, endCol, "($newParamText)"))
            action.add("edits", edits)
            actionsArray.add(action)
        } else {
            // Check if we're in an interface declaration
            val isInterface = containingClassOrObject is KtClass && containingClassOrObject.isInterface()
            if (!isInterface) return

            val interfaceName = containingClassOrObject.name ?: return
            val interfaceParams = function.valueParameters

            // Search session files + virtual files for implementations
            val edits = JsonArray()
            val allFiles = ctx.allSessionKtFiles() + ctx.allVirtualKtFiles()
            val seenUris = mutableSetOf<String>()

            for (file in allFiles) {
                val candidates = PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java)
                for (candidate in candidates) {
                    if (candidate === containingClassOrObject) continue
                    val supertypeNames = candidate.superTypeListEntries
                        .mapNotNull { it.typeReference?.text?.substringBefore("<")?.trim() }
                    if (interfaceName !in supertypeNames) continue

                    val implFunc = PsiTreeUtil.collectElementsOfType(candidate, KtNamedFunction::class.java)
                        .firstOrNull { it.name == funcName && it.hasModifier(KtTokens.OVERRIDE_KEYWORD) }
                        ?: continue

                    val implParams = implFunc.valueParameters
                    if (implParams.size >= interfaceParams.size) continue
                    if (!isParameterAddition(implParams, interfaceParams)) continue

                    val implFile = candidate.containingFile as? KtFile ?: continue
                    val implDoc = implFile.viewProvider.document ?: continue
                    val implUri = "file://${implFile.virtualFile.path}"
                    if (implUri in seenUris) continue
                    seenUris.add(implUri)

                    val implParamList = implFunc.valueParameterList ?: continue
                    val sourceParamList = function.valueParameterList ?: continue
                    val (startLine, startCol) = ctx.offsetToLineCol(implDoc, implParamList.textOffset)
                    val (endLine, endCol) = ctx.offsetToLineCol(implDoc, implParamList.textRange.endOffset)

                    // Preserve multiline formatting: if impl is multiline, reformat with impl's indentation
                    val newParamListText = if (implParamList.text.contains('\n')) {
                        val implIndent = indentAt(implDoc, implParamList.textOffset)
                        val paramIndent = "$implIndent    "
                        val paramTexts = interfaceParams.map { it.text }
                        "(\n${paramTexts.joinToString(",\n") { "$paramIndent$it" }}\n$implIndent)"
                    } else {
                        sourceParamList.text
                    }
                    edits.add(ctx.makeEdit(implUri, startLine, startCol, endLine, endCol, newParamListText))
                }
            }

            if (edits.size() > 0) {
                val action = JsonObject()
                action.addProperty("title", "Update implementation signatures")
                action.addProperty("kind", "refactor")
                action.add("edits", edits)
                actionsArray.add(action)
            }
        }
    } catch (_: Exception) {
        // Ignore failures
    }
}

/**
 * Checks whether targetParams is a pure addition to sourceParams:
 * all source params must appear as a prefix of target params with matching name and type text.
 */
private fun isParameterAddition(sourceParams: List<KtParameter>, targetParams: List<KtParameter>): Boolean {
    for (i in sourceParams.indices) {
        val src = sourceParams[i]
        val tgt = targetParams[i]
        if (src.name != tgt.name) return false
        if (src.typeReference?.text != tgt.typeReference?.text) return false
    }
    return true
}

/**
 * Creates an edit that deletes a PSI element, consuming a trailing newline if present.
 */
private fun makeDeleteEdit(
    ctx: BridgeContext,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    element: PsiElement,
): JsonObject {
    val (startLine, startCol) = ctx.offsetToLineCol(document, element.textOffset)
    var endOffset = element.textRange.endOffset
    val docText = document.charsSequence
    if (endOffset < docText.length && docText[endOffset] == '\n') {
        endOffset++
    }
    val (endLine, endCol) = ctx.offsetToLineCol(document, endOffset)
    return ctx.makeEdit(fileUri, startLine, startCol, endLine, endCol, "")
}

/**
 * Creates an edit that appends text at the end of the file.
 */
private fun makeAppendToFileEdit(
    ctx: BridgeContext,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    text: String,
): JsonObject {
    val lastLineEnd = document.getLineEndOffset(document.lineCount - 1)
    val (endLine, endCol) = ctx.offsetToLineCol(document, lastLineEnd)
    return ctx.makeEdit(fileUri, endLine, endCol, endLine, endCol, "\n\n$text")
}

/**
 * Returns the leading whitespace of the line containing the given offset.
 */
private fun indentAt(document: com.intellij.openapi.editor.Document, offset: Int): String {
    val lineNum = document.getLineNumber(offset)
    val lineStart = document.getLineStartOffset(lineNum)
    val lineEnd = document.getLineEndOffset(lineNum)
    return document.charsSequence.subSequence(lineStart, lineEnd).toString()
        .takeWhile { it == ' ' || it == '\t' }
}

private fun cleanModifiersForTopLevel(function: KtNamedFunction): String {
    val modifierList = function.modifierList ?: return ""
    val kept = modifierList.node.getChildren(null).filter { child ->
        val type = child.elementType
        type != KtTokens.PRIVATE_KEYWORD && type != KtTokens.PROTECTED_KEYWORD &&
            type != KtTokens.WHITE_SPACE
    }
    if (kept.isEmpty()) return ""
    return kept.joinToString(" ") { it.text } + " "
}

/**
 * Adds "Move to companion object" code action for class members.
 */
private fun KaSession.addMoveToCompanionAction(
    ctx: BridgeContext,
    element: PsiElement,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    actionsArray: JsonArray,
) {
    try {
        val declaration = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
            ?: return

        val parentClassBody = declaration.parent as? KtClassBody ?: return
        val parentClassOrObject = parentClassBody.parent
        if (parentClassOrObject is KtObjectDeclaration && parentClassOrObject.isCompanion()) return
        val containingClass = parentClassOrObject as? KtClass ?: return

        if (analyzeBodyReferences(ctx, declaration, containingClass).referencesThis) return

        val action = JsonObject()
        action.addProperty("title", "Move to companion object")
        action.addProperty("kind", "refactor")
        val actionEdits = JsonArray()

        val memberText = declaration.text
        val memberIndent = indentAt(document, declaration.textOffset)

        actionEdits.add(makeDeleteEdit(ctx, document, fileUri, declaration))

        val companion = containingClass.companionObjects.firstOrNull()
        if (companion != null) {
            val companionBody = companion.body
            if (companionBody != null) {
                val closingBrace = companionBody.rBrace
                if (closingBrace != null) {
                    val (insertLine, insertCol) = ctx.offsetToLineCol(document, closingBrace.textOffset)
                    val companionIndent = memberIndent + "    "
                    val reindentedText = memberText.lines().joinToString("\n") { line ->
                        if (line.isBlank()) line else "$companionIndent$line".trimEnd()
                    }
                    actionEdits.add(ctx.makeEdit(fileUri, insertLine, insertCol, insertLine, insertCol,
                        "$reindentedText\n$memberIndent"))
                }
            }
        } else {
            val classBody = containingClass.body
            if (classBody != null) {
                val closingBrace = classBody.rBrace
                if (closingBrace != null) {
                    val (insertLine, insertCol) = ctx.offsetToLineCol(document, closingBrace.textOffset)
                    val companionIndent = memberIndent + "    "
                    val reindentedMember = memberText.lines().joinToString("\n") { line ->
                        if (line.isBlank()) line else "$companionIndent    $line".trimEnd()
                    }
                    val companionText = "${memberIndent}companion object {\n$reindentedMember\n$memberIndent}\n"
                    actionEdits.add(ctx.makeEdit(fileUri, insertLine, insertCol, insertLine, insertCol,
                        "\n$companionText$memberIndent"))
                }
            }
        }

        action.add("edits", actionEdits)
        actionsArray.add(action)
    } catch (_: Exception) {
        // Ignore failures
    }
}

/**
 * Adds "Move from companion object" code action for companion object members.
 */
private fun addMoveFromCompanionAction(
    ctx: BridgeContext,
    element: PsiElement,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    actionsArray: JsonArray,
) {
    try {
        val declaration = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
            ?: return

        val companionBody = declaration.parent as? KtClassBody ?: return
        val companion = companionBody.parent as? KtObjectDeclaration ?: return
        if (!companion.isCompanion()) return

        val containingClass = companion.parent?.parent as? KtClass ?: return

        val action = JsonObject()
        action.addProperty("title", "Move from companion object")
        action.addProperty("kind", "refactor")
        val actionEdits = JsonArray()

        val memberText = declaration.text

        val classBody = containingClass.body ?: return
        val classIndent = indentAt(document, containingClass.textOffset)
        val memberIndent = classIndent + "    "

        val companionDeclarations = companionBody.declarations
        val willBeEmpty = companionDeclarations.size == 1 && companionDeclarations.first() == declaration

        if (willBeEmpty) {
            val docText = document.charsSequence
            var companionDeleteStart = companion.textOffset
            var companionDeleteEnd = companion.textRange.endOffset
            val companionLine = document.getLineNumber(companionDeleteStart)
            if (companionLine > 0) {
                companionDeleteStart = document.getLineStartOffset(companionLine)
            }
            if (companionDeleteEnd < docText.length && docText[companionDeleteEnd] == '\n') {
                companionDeleteEnd++
            }
            val (compStartLine, compStartCol) = ctx.offsetToLineCol(document, companionDeleteStart)
            val (compEndLine, compEndCol) = ctx.offsetToLineCol(document, companionDeleteEnd)
            actionEdits.add(ctx.makeEdit(fileUri, compStartLine, compStartCol, compEndLine, compEndCol, ""))
        } else {
            actionEdits.add(makeDeleteEdit(ctx, document, fileUri, declaration))
        }

        val companionOffset = companion.textOffset
        val companionLine = document.getLineNumber(companionOffset)
        val insertOffset = document.getLineStartOffset(companionLine)
        val (insertLine, insertCol) = ctx.offsetToLineCol(document, insertOffset)

        val reindentedText = memberText.lines().joinToString("\n") { line ->
            if (line.isBlank()) line else "$memberIndent${line.trimStart()}".trimEnd()
        }
        actionEdits.add(ctx.makeEdit(fileUri, insertLine, insertCol, insertLine, insertCol,
            "$reindentedText\n\n"))

        action.add("edits", actionEdits)
        actionsArray.add(action)
    } catch (_: Exception) {
        // Ignore failures
    }
}

/**
 * Adds "Move to top level" code action for class member functions.
 */
private fun KaSession.addMoveToTopLevelAction(
    ctx: BridgeContext,
    element: PsiElement,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    actionsArray: JsonArray,
    ktFile: KtFile,
) {
    try {
        val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
            ?: return

        val containingClassBody = function.parent as? KtClassBody ?: return
        val containingClass = containingClassBody.parent as? KtClass ?: return

        if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return

        val funcName = function.name ?: return
        val className = containingClass.name ?: return

        if (containingClass.typeParameters.isNotEmpty()) return

        val action = JsonObject()
        action.addProperty("title", "Move to top level")
        action.addProperty("kind", "refactor")
        val actionEdits = JsonArray()

        val usesThis = analyzeBodyReferences(ctx, function, containingClass).referencesThis

        val cleanModifiers = cleanModifiersForTopLevel(function)
        val typeParams = function.typeParameterList?.text?.let { "$it " } ?: ""
        val returnType = function.typeReference?.text?.let { ": $it" } ?: ""
        val paramList = function.valueParameterList?.text ?: "()"
        val receiverType = function.receiverTypeReference?.text

        val newParamList = if (usesThis) {
            val existingParams = paramList.removeSurrounding("(", ")")
            if (existingParams.isBlank()) "(self: $className)" else "(self: $className, $existingParams)"
        } else {
            paramList
        }

        val bodyExpr = function.bodyExpression ?: return
        var bodyText = bodyExpr.text
        if (usesThis) {
            val thisExprs = PsiTreeUtil.findChildrenOfType(bodyExpr, KtThisExpression::class.java)
                .sortedByDescending { it.textOffset }
            val bodyStart = bodyExpr.textOffset
            val sb = StringBuilder(bodyText)
            for (thisExpr in thisExprs) {
                val relStart = thisExpr.textOffset - bodyStart
                val relEnd = thisExpr.textRange.endOffset - bodyStart
                sb.replace(relStart, relEnd, "self")
            }
            bodyText = sb.toString()
        }

        val receiverPrefix = if (receiverType != null) "$receiverType." else ""
        val newFuncText = "${cleanModifiers}fun $typeParams$receiverPrefix$funcName$newParamList$returnType $bodyText"

        actionEdits.add(makeDeleteEdit(ctx, document, fileUri, function))
        actionEdits.add(makeAppendToFileEdit(ctx, document, fileUri, newFuncText))

        if (usesThis) {
            val callSites = mutableListOf<KtCallExpression>()
            containingClassBody.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    super.visitCallExpression(expression)
                    val callee = expression.calleeExpression
                    if (callee is KtSimpleNameExpression && callee.getReferencedName() == funcName) {
                        if (!PsiTreeUtil.isAncestor(function, expression, true)) {
                            callSites.add(expression)
                        }
                    }
                }
            })

            for (call in callSites.sortedByDescending { it.textOffset }) {
                val parent = call.parent
                val argList = call.valueArgumentList
                val existingArgs = argList?.arguments?.joinToString(", ") { it.text } ?: ""

                if (parent is KtDotQualifiedExpression) {
                    val receiver = parent.receiverExpression.text
                    val newArgs = if (existingArgs.isBlank()) receiver else "$receiver, $existingArgs"
                    val newCallText = "$funcName($newArgs)"
                    val (csStartLine, csStartCol) = ctx.offsetToLineCol(document, parent.textOffset)
                    val (csEndLine, csEndCol) = ctx.offsetToLineCol(document, parent.textRange.endOffset)
                    actionEdits.add(ctx.makeEdit(fileUri, csStartLine, csStartCol, csEndLine, csEndCol, newCallText))
                } else {
                    val newArgs = if (existingArgs.isBlank()) "this" else "this, $existingArgs"
                    val newCallText = "$funcName($newArgs)"
                    val (csStartLine, csStartCol) = ctx.offsetToLineCol(document, call.textOffset)
                    val (csEndLine, csEndCol) = ctx.offsetToLineCol(document, call.textRange.endOffset)
                    actionEdits.add(ctx.makeEdit(fileUri, csStartLine, csStartCol, csEndLine, csEndCol, newCallText))
                }
            }
        }

        action.add("edits", actionEdits)
        actionsArray.add(action)
    } catch (_: Exception) {
        // Ignore failures
    }
}

/**
 * Adds "Convert to extension function" code action for class member functions.
 */
private fun KaSession.addConvertToExtensionFunctionAction(
    ctx: BridgeContext,
    element: PsiElement,
    document: com.intellij.openapi.editor.Document,
    fileUri: String,
    actionsArray: JsonArray,
) {
    try {
        val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
            ?: return

        val containingClassBody = function.parent as? KtClassBody ?: return
        val parentClassOrObject = containingClassBody.parent
        if (parentClassOrObject is KtObjectDeclaration && parentClassOrObject.isCompanion()) return
        val containingClass = parentClassOrObject as? KtClass ?: return

        if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
        if (containingClass.typeParameters.isNotEmpty()) return
        if (analyzeBodyReferences(ctx, function, containingClass).accessesPrivateMembers) return

        val funcName = function.name ?: return
        val className = containingClass.name ?: return

        val action = JsonObject()
        action.addProperty("title", "Convert to extension function")
        action.addProperty("kind", "refactor")
        val actionEdits = JsonArray()

        val cleanModifiers = cleanModifiersForTopLevel(function)
        val typeParams = function.typeParameterList?.text?.let { "$it " } ?: ""
        val paramList = function.valueParameterList?.text ?: "()"
        val returnType = function.typeReference?.text?.let { ": $it" } ?: ""
        val bodyText = function.bodyExpression?.text ?: return

        val extFuncText = "${cleanModifiers}fun $typeParams$className.$funcName$paramList$returnType $bodyText"

        actionEdits.add(makeDeleteEdit(ctx, document, fileUri, function))
        actionEdits.add(makeAppendToFileEdit(ctx, document, fileUri, extFuncText))

        action.add("edits", actionEdits)
        actionsArray.add(action)
    } catch (_: Exception) {
        // Ignore failures
    }
}
