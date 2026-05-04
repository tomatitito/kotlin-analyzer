package dev.kouros.sidecar

import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

data class ImportCandidate(val shortName: String, val fqn: String)

data class TypeImportAssistPlan(
    val shortName: String,
    val candidates: List<ImportCandidate>,
)

fun findImportCandidates(
    ktFile: KtFile,
    shortName: String,
    symbolIndex: SymbolIndex,
    classpathFqns: Set<String>,
): List<ImportCandidate> {
    val currentPackage = ktFile.packageFqName.asString()

    val existingImports = ktFile.importDirectives
        .mapNotNull { it.importedFqName?.asString() }
        .toSet()

    val candidates = mutableListOf<ImportCandidate>()

    symbolIndex.findByShortName(shortName)
        .mapNotNull { declaration ->
            val fqn = declaration.fqn ?: return@mapNotNull null
            if (fqn == shortName) return@mapNotNull null
            val packageName = fqn.substringBeforeLast('.', "")
            if (packageName == currentPackage) return@mapNotNull null
            if (fqn in existingImports) return@mapNotNull null
            ImportCandidate(shortName = shortName, fqn = fqn)
        }
        .let { candidates.addAll(it) }

    val suffix = ".$shortName"
    for (fqn in classpathFqns) {
        if (!fqn.endsWith(suffix)) continue
        val packageName = fqn.substringBeforeLast('.', "")
        if (packageName == currentPackage) continue
        if (fqn in existingImports) continue
        candidates.add(ImportCandidate(shortName = shortName, fqn = fqn))
    }

    return candidates
        .distinctBy { it.fqn }
        .sortedBy { it.fqn }
}

fun planTypeImportAssist(
    ktFile: KtFile,
    factoryName: String?,
    startOffset: Int,
    endOffset: Int,
    symbolIndex: SymbolIndex,
    classpathFqns: Set<String>,
): TypeImportAssistPlan? {
    if (factoryName != "UNRESOLVED_REFERENCE") return null
    if (startOffset >= endOffset) return null

    val unresolvedElement = ktFile.findElementAt(startOffset) ?: return null
    val unresolvedText = ktFile.text.substring(startOffset, minOf(endOffset, ktFile.text.length))

    val userType = PsiTreeUtil.getParentOfType(
        unresolvedElement,
        KtUserType::class.java,
        false,
    )
    if (userType != null && userType.qualifier == null) {
        val simpleName = userType.referenceExpression
        if (simpleName != null && simpleName.getReferencedName() == unresolvedText) {
            val typeReference = PsiTreeUtil.getParentOfType(userType, KtTypeReference::class.java, false)
            if (typeReference != null) {
                val shortName = simpleName.getReferencedName()
                if (shortName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    val candidates = findImportCandidates(ktFile, shortName, symbolIndex, classpathFqns)
                    if (candidates.isNotEmpty()) {
                        return TypeImportAssistPlan(shortName = shortName, candidates = candidates)
                    }
                }
            }
        }
    }

    val nameRef = PsiTreeUtil.getParentOfType(
        unresolvedElement,
        KtNameReferenceExpression::class.java,
        false,
    )
    if (nameRef != null && nameRef.getReferencedName() == unresolvedText) {
        val insideTypeRef = PsiTreeUtil.getParentOfType(nameRef, KtTypeReference::class.java, false)
        if (insideTypeRef == null) {
            val shortName = nameRef.getReferencedName()
            if (shortName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                val candidates = findImportCandidates(ktFile, shortName, symbolIndex, classpathFqns)
                if (candidates.isNotEmpty()) {
                    return TypeImportAssistPlan(shortName = shortName, candidates = candidates)
                }
            }
        }
    }

    return null
}

fun buildImportEdit(
    uri: String,
    ktFile: KtFile,
    document: com.intellij.openapi.editor.Document,
    fqn: String,
): JsonObject? {
    if ('.' !in fqn) return null

    val currentPackage = ktFile.packageFqName.asString()
    val packageName = fqn.substringBeforeLast('.', "")
    if (packageName == currentPackage) return null

    val existingImports = ktFile.importDirectives
        .mapNotNull { it.importedFqName?.asString() }
        .toSet()
    if (fqn in existingImports) return null

    val (insertLine, newText) = computeImportInsertion(ktFile, document, fqn)
    return makeEdit(uri, insertLine, 0, insertLine, 0, newText)
}

fun computeImportInsertion(
    ktFile: KtFile,
    document: com.intellij.openapi.editor.Document,
    fqn: String,
): Pair<Int, String> {
    val importList = ktFile.importList
    if (importList != null && importList.imports.isNotEmpty()) {
        val lastImport = importList.imports.last()
        val insertLine = document.getLineNumber(lastImport.textRange.endOffset) + 2
        val needsBlankLine = lineNeedsBlankLineBeforeDeclarations(document, insertLine)
        val newText = if (needsBlankLine) "import $fqn\n\n" else "import $fqn\n"
        return Pair(insertLine, newText)
    }

    val packageDirective = ktFile.packageDirective
    if (packageDirective != null && packageDirective.text.isNotBlank()) {
        return Pair(
            document.getLineNumber(packageDirective.textRange.endOffset) + 2,
            "import $fqn\n\n",
        )
    }

    return Pair(1, "import $fqn\n\n")
}

fun makeEdit(
    uri: String,
    startLine: Int,
    startCol: Int,
    endLine: Int,
    endCol: Int,
    newText: String,
): JsonObject {
    val edit = JsonObject()
    edit.addProperty("uri", uri)
    val range = JsonObject()
    range.addProperty("startLine", startLine)
    range.addProperty("startColumn", startCol)
    range.addProperty("endLine", endLine)
    range.addProperty("endColumn", endCol)
    edit.add("range", range)
    edit.addProperty("newText", newText)
    return edit
}

private fun lineNeedsBlankLineBeforeDeclarations(
    document: com.intellij.openapi.editor.Document,
    line: Int,
): Boolean {
    val zeroBasedLine = line - 1
    if (zeroBasedLine < 0 || zeroBasedLine >= document.lineCount) return true

    val startOffset = document.getLineStartOffset(zeroBasedLine)
    val endOffset = document.getLineEndOffset(zeroBasedLine)
    return document.charsSequence.subSequence(startOffset, endOffset).toString().isNotBlank()
}
