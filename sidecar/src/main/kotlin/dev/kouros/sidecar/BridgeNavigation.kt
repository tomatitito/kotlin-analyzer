package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.*

/**
 * Provides go-to-definition locations.
 */
internal fun BridgeContext.definition(uri: String, line: Int, character: Int): JsonObject {
    ensureSessionCurrent()
    ensurePebbleSpringIndexCurrent()
    val result = JsonObject()
    val locationsArray = JsonArray()
    val perfStart = System.currentTimeMillis()

    val currentSession = session ?: run {
        val springTemplateUri = pebbleSpringIndex.definitionForKotlinView(uri, line, character)
        if (springTemplateUri != null) {
            val location = JsonObject()
            location.addProperty("uri", springTemplateUri)
            location.addProperty("line", 1)
            location.addProperty("column", 0)
            locationsArray.add(location)
        }
        result.add("locations", locationsArray)
        return result
    }
    val ktFile = findKtFile(currentSession, uri) ?: run {
        result.add("locations", locationsArray)
        return result
    }

    try {
        analyze(ktFile) {
            val offset = lineColToOffset(ktFile, line, character) ?: run {
                result.add("locations", locationsArray)
                return@analyze
            }

            val element = ktFile.findElementAt(offset)
            if (element != null) {
                val springProducer = findSpringModelProducerDeclaration(element)
                if (springProducer != null) {
                    val declarationUri = (springProducer.containingFile as? KtFile)?.let { fileUriForKtFile(it) } ?: uri
                    for (usage in pebbleSpringIndex.pebbleUsagesForDeclaration(springProducer, declarationUri)) {
                        val location = JsonObject()
                        location.addProperty("uri", usage.uri)
                        location.addProperty("line", usage.line)
                        location.addProperty("column", usage.column)
                        locationsArray.add(location)
                    }
                    return@analyze
                }

                val springTemplateUri = pebbleSpringIndex.definitionForKotlinView(uri, line, character)
                if (springTemplateUri != null) {
                    val location = JsonObject()
                    location.addProperty("uri", springTemplateUri)
                    location.addProperty("line", 1)
                    location.addProperty("column", 0)
                    locationsArray.add(location)
                    return@analyze
                }

                // Check if we're on an override member — resolve to the interface declaration
                val overrideDecl = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
                if (overrideDecl != null && overrideDecl.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
                    val memberName = overrideDecl.name
                    val containingClassOrObj = PsiTreeUtil.getParentOfType(overrideDecl, KtClassOrObject::class.java)
                    if (memberName != null && containingClassOrObj != null) {
                        val containerSymbol = containingClassOrObj.symbol as? KaNamedClassSymbol
                        if (containerSymbol != null) {
                            for (superType in containerSymbol.superTypes.filterIsInstance<KaClassType>()) {
                                val superSymbol = superType.symbol as? KaNamedClassSymbol ?: continue
                                if (superSymbol.classKind != KaClassKind.INTERFACE) continue
                                val psi = try { superSymbol.psi } catch (_: Throwable) { null }
                                val superClass = psi as? KtClassOrObject ?: continue
                                val interfaceMember = PsiTreeUtil.collectElementsOfType(superClass, KtNamedDeclaration::class.java)
                                    .firstOrNull { it.name == memberName && it != overrideDecl }
                                if (interfaceMember != null) {
                                    val loc = buildPsiDefinitionLocation(this@definition, interfaceMember, uri)
                                    if (loc != null) {
                                        locationsArray.add(loc)
                                        result.add("locations", locationsArray)
                                        return@analyze
                                    }
                                }
                            }
                        }
                    }
                }

                // Find the reference element
                var current: PsiElement? = element
                while (current != null) {
                    // Handle annotation entries: resolve to the annotation class
                    if (current is org.jetbrains.kotlin.psi.KtAnnotationEntry) {
                        try {
                            val typeRef = current.typeReference
                            val type = typeRef?.type
                            val classSymbol = (type as? org.jetbrains.kotlin.analysis.api.types.KaClassType)
                                ?.symbol as? KaDeclarationSymbol
                            if (classSymbol != null) {
                                val loc = buildDefinitionLocation(this@definition, classSymbol, uri)
                                if (loc != null) {
                                    locationsArray.add(loc)
                                }
                            }
                        } catch (e: Throwable) {
                            System.err.println("CompilerBridge: definition annotation resolve failed: ${e.message?.take(100)}")
                        }
                        break
                    }

                    if (current is KtReferenceExpression) {
                        // First try Analysis API resolution
                        var resolved = false
                        for (ref in current.references) {
                            if (ref is KtReference) {
                                try {
                                    var symbol = ref.resolveToSymbol()
                                    // For constructor calls, navigate to the class
                                    if (symbol is KaConstructorSymbol) {
                                        val container = symbol.containingDeclaration
                                        if (container is KaDeclarationSymbol) {
                                            symbol = container
                                        }
                                    }
                                    if (symbol is KaDeclarationSymbol) {
                                        val loc = buildDefinitionLocation(this@definition, symbol, uri)
                                        if (loc != null) {
                                            locationsArray.add(loc)
                                            resolved = true
                                        }
                                    }
                                } catch (e: Throwable) {
                                    System.err.println("CompilerBridge: definition resolveToSymbol failed: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                                }
                            }
                        }

                        // Fall back to PSI-based resolve
                        if (!resolved) {
                            for (ref in current.references) {
                                try {
                                    val psiTarget = ref.resolve()
                                    if (psiTarget != null) {
                                        val loc = buildPsiDefinitionLocation(this@definition, psiTarget, uri)
                                        if (loc != null) {
                                            locationsArray.add(loc)
                                        }
                                    }
                                } catch (e: Throwable) {
                                    System.err.println("CompilerBridge: definition ref.resolve() failed: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                                }
                            }
                        }
                        break
                    }
                    current = current.parent
                }
            }
        }
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: definition failed: ${e.javaClass.name}: ${e.message}")
    }

    System.err.println("[PERF] method=definition uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
    result.add("locations", locationsArray)
    return result
}

/**
 * Provides find-implementations locations.
 */
internal fun BridgeContext.implementation(uri: String, line: Int, character: Int): JsonObject {
    ensureSessionCurrent()
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
        analyze(ktFile) {
            val offset = lineColToOffset(ktFile, line, character) ?: run {
                result.add("locations", locationsArray)
                return@analyze
            }

            val element = ktFile.findElementAt(offset) ?: run {
                result.add("locations", locationsArray)
                return@analyze
            }

            // Check if cursor is on a specific member declaration within the interface
            val memberDecl = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
            val isOnMember = memberDecl != null && memberDecl !is KtClassOrObject

            val classOrObject = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java, false)
            if (classOrObject != null && !isOnMember) {
                val classSymbol = classOrObject.symbol as? KaNamedClassSymbol
                if (classSymbol != null && classSymbol.classKind == KaClassKind.INTERFACE) {
                    val interfaceClassId = classSymbol.classId
                    if (interfaceClassId != null) {
                        for (sessionFile in allSessionKtFiles()) {
                            val candidates = PsiTreeUtil.collectElementsOfType(sessionFile, KtClassOrObject::class.java)
                            for (candidate in candidates) {
                                if (candidate === classOrObject) continue
                                if (!isConcreteImplementationCandidate(candidate)) continue
                                val candidateSymbol = candidate.symbol as? KaNamedClassSymbol ?: continue
                                val implementsIt = candidateSymbol.superTypes
                                    .filterIsInstance<KaClassType>()
                                    .any { superType ->
                                        val superSymbol = superType.symbol as? KaNamedClassSymbol
                                        superSymbol?.classKind == KaClassKind.INTERFACE &&
                                            superSymbol.classId == interfaceClassId
                                    }
                                if (implementsIt) {
                                    val candidateFile = candidate.containingFile as? KtFile ?: continue
                                    val candidateDoc = candidateFile.viewProvider.document ?: continue
                                    val nameId = candidate.nameIdentifier ?: continue
                                    val implOffset = nameId.textOffset
                                    val implLine = candidateDoc.getLineNumber(implOffset) + 1
                                    val implLineStart = candidateDoc.getLineStartOffset(candidateDoc.getLineNumber(implOffset))
                                    val implCol = implOffset - implLineStart
                                    val loc = JsonObject()
                                    loc.addProperty("uri", fileUriForKtFile(candidateFile))
                                    loc.addProperty("line", implLine)
                                    loc.addProperty("column", implCol)
                                    locationsArray.add(loc)
                                }
                            }
                        }
                    }
                }
            }

            // Also find implementing members
            val declaration = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
            if (declaration != null) {
                findImplementingMemberLocations(this@implementation, declaration, locationsArray)
            }
        }
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: implementation failed: ${e.javaClass.name}: ${e.message}")
    }

    System.err.println("[PERF] method=implementation uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
    result.add("locations", locationsArray)
    return result
}

/**
 * Builds a definition location from a KaDeclarationSymbol using the Analysis API.
 */
private fun KaSession.buildDefinitionLocation(ctx: BridgeContext, symbol: KaDeclarationSymbol, fallbackUri: String): JsonObject? {
    val isLibrary = try {
        symbol.origin.name.contains("LIBRARY", ignoreCase = true)
    } catch (_: Throwable) { false }

    if (isLibrary) {
        val psi = try { symbol.psi } catch (_: Throwable) { null }
        if (psi != null) {
            try {
                val file = psi.containingFile
                val vfPath = file?.virtualFile?.path
                if (file != null && vfPath != null) {
                    val textOffset = try { psi.textOffset } catch (_: Throwable) { 0 }
                    val text = try { file.text } catch (_: Throwable) { null }
                    if (text != null && textOffset <= text.length) {
                        val beforeOffset = text.substring(0, textOffset)
                        val lineNumber = beforeOffset.count { it == '\n' } + 1
                        val lastNewline = beforeOffset.lastIndexOf('\n')
                        val column = if (lastNewline >= 0) textOffset - lastNewline - 1 else textOffset

                        val decompiledPath = ctx.writeDecompiledFile(vfPath, text)
                        val uri = if (decompiledPath != null) {
                            "file://${decompiledPath}"
                        } else {
                            ctx.buildFileUri(vfPath)
                        }

                        val loc = JsonObject()
                        loc.addProperty("uri", uri)
                        loc.addProperty("line", lineNumber)
                        loc.addProperty("column", column)
                        return loc
                    }

                    val rendered = renderLibrarySymbolStub(ctx, symbol, vfPath)
                    if (rendered != null) return rendered
                }
            } catch (e: Throwable) {
                System.err.println("CompilerBridge: buildDefinitionLocation PSI access failed: ${e.javaClass.simpleName}")
            }
        }

        // Fallback: render symbol stub using classId
        try {
            val classId = when (symbol) {
                is KaClassLikeSymbol -> symbol.classId
                is KaCallableSymbol -> symbol.callableId?.classId
                else -> null
            }
            if (classId != null) {
                val pkg = classId.packageFqName.asString().replace('.', '/')
                val className = classId.shortClassName.asString()
                val syntheticPath = "$pkg/$className.class"
                val rendered = renderLibrarySymbolStub(ctx, symbol, syntheticPath)
                if (rendered != null) return rendered

                val loc = JsonObject()
                loc.addProperty("uri", "kotlin-analyzer:///library/$pkg/$className.class")
                loc.addProperty("line", 1)
                loc.addProperty("column", 0)
                return loc
            }
        } catch (e: Throwable) {
            System.err.println("CompilerBridge: buildDefinitionLocation synthetic failed: ${e.message?.take(100)}")
        }

        return null
    }

    // For source symbols, use standard PSI location
    val psi = try { symbol.psi } catch (_: Throwable) { null }
    if (psi != null) {
        return buildPsiDefinitionLocation(ctx, psi, fallbackUri)
    }
    return null
}

/**
 * Builds a definition location from a resolved PsiElement (PSI-based fallback).
 */
private fun buildPsiDefinitionLocation(ctx: BridgeContext, psiTarget: PsiElement, fallbackUri: String): JsonObject? {
    try {
        val file = psiTarget.containingFile ?: return null
        val textOffset = psiTarget.textOffset
        val doc = file.viewProvider?.document
        val vfPath = file.virtualFile?.path

        if (doc != null) {
            val targetUri = if (vfPath != null && !vfPath.contains("dummy")) {
                (file as? KtFile)?.let { ctx.fileUriForKtFile(it) } ?: ctx.buildFileUri(vfPath)
            } else {
                fallbackUri
            }
            val loc = JsonObject()
            loc.addProperty("uri", targetUri)
            loc.addProperty("line", doc.getLineNumber(textOffset) + 1)
            loc.addProperty("column", textOffset - doc.getLineStartOffset(doc.getLineNumber(textOffset)))
            return loc
        }

        // Fallback for JAR/library files
        if (vfPath != null && file.text != null) {
            val text = file.text
            val beforeOffset = text.substring(0, minOf(textOffset, text.length))
            val lineNumber = beforeOffset.count { it == '\n' } + 1
            val lastNewline = beforeOffset.lastIndexOf('\n')
            val column = if (lastNewline >= 0) textOffset - lastNewline - 1 else textOffset

            val loc = JsonObject()
            loc.addProperty("uri", ctx.buildFileUri(vfPath))
            loc.addProperty("line", lineNumber)
            loc.addProperty("column", column)
            return loc
        }
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: buildPsiDefinitionLocation failed: ${e.message?.take(100)}")
    }
    return null
}

private fun KaSession.findImplementingMemberLocations(
    ctx: BridgeContext,
    declaration: KtNamedDeclaration,
    locationsArray: JsonArray,
) {
    val containingClass = PsiTreeUtil.getParentOfType(declaration, KtClassOrObject::class.java) ?: return
    val classSymbol = containingClass.symbol as? KaNamedClassSymbol ?: return
    if (classSymbol.classKind != KaClassKind.INTERFACE) return

    val memberName = declaration.name ?: return
    val interfaceClassId = classSymbol.classId ?: return

    for (sessionFile in ctx.allSessionKtFiles()) {
        val candidates = PsiTreeUtil.collectElementsOfType(sessionFile, KtClassOrObject::class.java)
        for (candidate in candidates) {
            if (candidate === containingClass) continue
            val candidateSymbol = candidate.symbol as? KaNamedClassSymbol ?: continue
            val implementsInterface = candidateSymbol.superTypes
                .filterIsInstance<KaClassType>()
                .any { superType ->
                    val superSymbol = superType.symbol as? KaNamedClassSymbol
                    superSymbol?.classKind == KaClassKind.INTERFACE &&
                        superSymbol.classId == interfaceClassId
                }
            if (!implementsInterface) continue

            // Find the overriding member with matching name
            val overridingMember = PsiTreeUtil.collectElementsOfType(candidate, KtNamedDeclaration::class.java)
                .firstOrNull { decl ->
                    decl.name == memberName &&
                        decl.hasModifier(KtTokens.OVERRIDE_KEYWORD)
                }
            if (overridingMember != null) {
                val candidateFile = candidate.containingFile as? KtFile ?: continue
                val candidateDoc = candidateFile.viewProvider.document ?: continue
                val nameId = overridingMember.nameIdentifier ?: continue
                val implOffset = nameId.textOffset
                val implLine = candidateDoc.getLineNumber(implOffset) + 1
                val implLineStart = candidateDoc.getLineStartOffset(candidateDoc.getLineNumber(implOffset))
                val implCol = implOffset - implLineStart
                val loc = JsonObject()
                loc.addProperty("uri", ctx.fileUriForKtFile(candidateFile))
                loc.addProperty("line", implLine)
                loc.addProperty("column", implCol)
                locationsArray.add(loc)
            }
        }
    }
}

/**
 * Renders a library symbol as a Kotlin stub file and writes it to disk.
 */
private fun KaSession.renderLibrarySymbolStub(
    ctx: BridgeContext,
    symbol: KaDeclarationSymbol,
    vfPath: String,
): JsonObject? {
    try {
        val rendered = symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
        if (rendered.isBlank()) return null

        val fqNamePattern = Regex("""([\w]+(?:\.[\w]+){2,})""")
        val imports = mutableSetOf<String>()
        var shortened = rendered
        for (match in fqNamePattern.findAll(rendered).sortedByDescending { it.range.first }) {
            val fqName = match.value
            val segments = fqName.split('.')
            val classIdx = segments.indexOfLast { it.first().isUpperCase() }
            if (classIdx < 0) continue
            val importName = segments.subList(0, classIdx + 1).joinToString(".")
            if (importName.count { it == '.' } < 1) continue
            imports.add(importName)
            val replacement = segments.subList(classIdx, segments.size).joinToString(".")
            shortened = shortened.replaceRange(match.range, replacement)
        }

        val packageName = when (symbol) {
            is KaClassLikeSymbol -> symbol.classId?.packageFqName?.asString()
            is KaCallableSymbol -> symbol.callableId?.packageName?.asString()
            else -> null
        }
        if (!packageName.isNullOrEmpty()) {
            imports.removeAll { it.substringBeforeLast('.') == packageName }
        }
        val docText = extractSymbolDocumentation(ctx, symbol)

        val stubText = buildString {
            if (!packageName.isNullOrEmpty()) {
                append("package $packageName\n\n")
            }
            if (imports.isNotEmpty()) {
                imports.sorted().forEach { append("import $it\n") }
                append("\n")
            }
            if (docText != null) {
                append("/**\n")
                docText.lines().forEach { line -> append(" * $line\n") }
                append(" */\n")
            }
            append(shortened)
            append("\n")
        }

        val declLine = stubText.substring(0, stubText.indexOf(shortened)).count { it == '\n' } + 1

        val decompiledPath = ctx.writeDecompiledFile(vfPath, stubText) ?: return null
        val loc = JsonObject()
        loc.addProperty("uri", "file://${decompiledPath}")
        loc.addProperty("line", declLine)
        loc.addProperty("column", 0)
        return loc
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: renderLibrarySymbolStub failed: ${e.message?.take(100)}")
        return null
    }
}
