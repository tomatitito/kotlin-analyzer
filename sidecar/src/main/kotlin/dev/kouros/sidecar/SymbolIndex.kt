package dev.kouros.sidecar

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.psi.*
import java.util.concurrent.ConcurrentHashMap

data class IndexedDeclaration(
    val shortName: String,
    val fqn: String?,
    val kind: String,
    val uri: String,
    val line: Int,
    val column: Int,
)

class SymbolIndex {
    private val index = ConcurrentHashMap<String, List<IndexedDeclaration>>()

    fun indexFile(uri: String, ktFile: KtFile) {
        val document = ktFile.viewProvider.document ?: return
        val declarations = PsiTreeUtil.findChildrenOfType(ktFile, KtNamedDeclaration::class.java)
        val indexed = declarations.mapNotNull { declaration ->
            val name = declaration.name ?: return@mapNotNull null
            val kind = when (declaration) {
                is KtObjectDeclaration -> "object"
                is KtEnumEntry -> "enumMember"
                is KtClass -> when {
                    declaration.isInterface() -> "interface"
                    declaration.isEnum() -> "enum"
                    else -> "class"
                }
                is KtNamedFunction -> "function"
                is KtProperty -> "property"
                is KtTypeAlias -> "typeAlias"
                else -> null
            } ?: return@mapNotNull null

            val declOffset = declaration.textOffset
            val declLine = document.getLineNumber(declOffset) + 1
            val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
            val declCol = declOffset - declLineStart

            IndexedDeclaration(
                shortName = name,
                fqn = declaration.fqName?.asString(),
                kind = kind,
                uri = uri,
                line = declLine,
                column = declCol,
            )
        }
        index[uri] = indexed
    }

    fun removeFile(uri: String) {
        index.remove(uri)
    }

    fun findByShortName(shortName: String): List<IndexedDeclaration> =
        index.values.flatten().filter { it.shortName == shortName }

    fun searchSymbols(query: String, limit: Int = 100): List<IndexedDeclaration> {
        val all = index.values.flatten()
        val filtered = if (query.isEmpty()) {
            all
        } else {
            val lowerQuery = query.lowercase()
            all.filter { it.shortName.lowercase().contains(lowerQuery) }
        }
        return filtered.sortedBy { it.shortName }.take(limit)
    }

    fun allDeclarationsInFile(uri: String): List<IndexedDeclaration> =
        index[uri] ?: emptyList()

    fun rebuildFromSession(session: StandaloneAnalysisAPISession) {
        index.clear()
        for ((_, ktFiles) in session.modulesWithFiles) {
            for (ktFile in ktFiles.filterIsInstance<KtFile>()) {
                val path = ktFile.virtualFile.path
                indexFile("file://$path", ktFile)
            }
        }
    }

    fun size(): Int = index.values.sumOf { it.size }

    fun clear() {
        index.clear()
    }
}
