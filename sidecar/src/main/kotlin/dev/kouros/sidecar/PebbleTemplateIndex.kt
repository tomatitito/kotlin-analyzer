package dev.kouros.sidecar

import java.net.URI
import java.nio.file.Paths

internal enum class PebbleReferenceKind {
    INCLUDE,
    EXTENDS,
    IMPORT,
}

internal data class PebbleRange(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
) {
    fun contains(line: Int, column: Int): Boolean {
        if (line < startLine || line > endLine) return false
        if (startLine == endLine) {
            return column in startColumn..<endColumn
        }
        if (line == startLine) return column >= startColumn
        if (line == endLine) return column < endColumn
        return true
    }
}

internal data class PebbleTemplateReference(
    val sourceUri: String,
    val targetTemplate: String,
    val kind: PebbleReferenceKind,
    val range: PebbleRange,
)

internal data class PebbleVariableReference(
    val sourceUri: String,
    val segments: List<String>,
    val range: PebbleRange,
)

internal data class PebbleTemplateFacts(
    val uri: String,
    val templateReferences: List<PebbleTemplateReference>,
    val variableReferences: List<PebbleVariableReference>,
)

internal class PebbleLineIndex(text: String) {
    private val lineStarts = buildList {
        add(0)
        text.forEachIndexed { index, ch ->
            if (ch == '\n') add(index + 1)
        }
    }

    fun range(startOffset: Int, endOffsetExclusive: Int): PebbleRange {
        val start = position(startOffset)
        val end = position(endOffsetExclusive)
        return PebbleRange(
            startLine = start.first,
            startColumn = start.second,
            endLine = end.first,
            endColumn = end.second,
        )
    }

    private fun position(offset: Int): Pair<Int, Int> {
        val clamped = offset.coerceAtLeast(0)
        var low = 0
        var high = lineStarts.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val lineStart = lineStarts[mid]
            val nextLineStart = if (mid == lineStarts.lastIndex) Int.MAX_VALUE else lineStarts[mid + 1]
            if (clamped < lineStart) {
                high = mid - 1
            } else if (clamped >= nextLineStart) {
                low = mid + 1
            } else {
                return (mid + 1) to (clamped - lineStart)
            }
        }
        val lastLine = lineStarts.lastIndex
        return (lastLine + 1) to (clamped - lineStarts[lastLine])
    }
}

internal class PebbleTemplateParser {
    private val templateDirectivePattern =
        Regex("""\{%\s*(include|extends|import)\s+(['"])([^'"]+)\2""")
    private val expressionBlockPattern = Regex("""\{\{.*?}}""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val variableChainPattern = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)(\.[A-Za-z_][A-Za-z0-9_]*)*\b""")

    fun parse(uri: String, text: String): PebbleTemplateFacts {
        val lines = PebbleLineIndex(text)

        val templateReferences = templateDirectivePattern.findAll(text).map { match ->
            val directive = when (match.groupValues[1]) {
                "include" -> PebbleReferenceKind.INCLUDE
                "extends" -> PebbleReferenceKind.EXTENDS
                else -> PebbleReferenceKind.IMPORT
            }
            val literalRange = match.groups[3]!!.range
            PebbleTemplateReference(
                sourceUri = uri,
                targetTemplate = match.groupValues[3],
                kind = directive,
                range = lines.range(literalRange.first, literalRange.last + 1),
            )
        }.toList()

        val variableReferences = expressionBlockPattern.findAll(text).flatMap { block ->
            variableChainPattern.findAll(block.value).mapNotNull { chain ->
                val segments = chain.value.split('.')
                val root = segments.firstOrNull() ?: return@mapNotNull null
                if (root in RESERVED_WORDS) return@mapNotNull null
                val start = block.range.first + chain.range.first
                val endExclusive = block.range.first + chain.range.last + 1
                PebbleVariableReference(
                    sourceUri = uri,
                    segments = segments,
                    range = lines.range(start, endExclusive),
                )
            }
        }.toList()

        return PebbleTemplateFacts(
            uri = uri,
            templateReferences = templateReferences,
            variableReferences = variableReferences,
        )
    }

    private companion object {
        val RESERVED_WORDS = setOf(
            "and",
            "as",
            "block",
            "else",
            "elseif",
            "embed",
            "endblock",
            "endfor",
            "endif",
            "endmacro",
            "extends",
            "false",
            "filter",
            "for",
            "from",
            "if",
            "import",
            "in",
            "include",
            "is",
            "macro",
            "not",
            "null",
            "only",
            "or",
            "set",
            "true",
            "verbatim",
            "with",
        )
    }
}

internal class PebbleDocumentStore {
    private val documents = linkedMapOf<String, String>()

    fun put(uri: String, text: String) {
        documents[uri] = text
    }

    fun remove(uri: String) {
        documents.remove(uri)
    }

    fun get(uri: String): String? = documents[uri]

    fun all(): Map<String, String> = documents.toMap()
}

internal class PebbleTemplateIndex(
    private val parser: PebbleTemplateParser = PebbleTemplateParser(),
) {
    private val documents = linkedMapOf<String, String>()
    private val factsByUri = linkedMapOf<String, PebbleTemplateFacts>()
    private val aliasesToUris = linkedMapOf<String, MutableSet<String>>()
    private val referencesByResolvedTarget = linkedMapOf<String, MutableList<PebbleTemplateReference>>()

    fun update(uri: String, text: String) {
        documents[uri] = text
        rebuild()
    }

    fun remove(uri: String) {
        documents.remove(uri)
        rebuild()
    }

    fun definition(uri: String, line: Int, character: Int): String? {
        val reference = findTemplateReferenceAt(uri, line, character) ?: return null
        return resolveTemplate(reference.targetTemplate)
    }

    fun referencesAt(uri: String, line: Int, character: Int): List<PebbleTemplateReference> {
        val reference = findTemplateReferenceAt(uri, line, character)
        if (reference != null) {
            val targetUri = resolveTemplate(reference.targetTemplate) ?: return emptyList()
            return referencesToTemplate(targetUri)
        }
        return referencesToTemplate(uri)
    }

    fun referencesToTemplate(uri: String): List<PebbleTemplateReference> =
        referencesByResolvedTarget[uri].orEmpty().sortedWith(
            compareBy<PebbleTemplateReference>({ it.sourceUri }, { it.range.startLine }, { it.range.startColumn })
        )

    fun findTemplateReferenceAt(uri: String, line: Int, character: Int): PebbleTemplateReference? =
        factsByUri[uri]
            ?.templateReferences
            ?.firstOrNull { it.range.contains(line, character) }

    fun facts(uri: String): PebbleTemplateFacts? = factsByUri[uri]

    private fun rebuild() {
        factsByUri.clear()
        aliasesToUris.clear()
        referencesByResolvedTarget.clear()

        for ((uri, text) in documents.toSortedMap()) {
            factsByUri[uri] = parser.parse(uri, text)
            for (alias in templateAliases(uri)) {
                aliasesToUris.getOrPut(alias) { linkedSetOf() }.add(uri)
            }
        }

        for (facts in factsByUri.values) {
            for (reference in facts.templateReferences) {
                val targetUri = resolveTemplate(reference.targetTemplate) ?: continue
                referencesByResolvedTarget.getOrPut(targetUri) { mutableListOf() }.add(reference)
            }
        }
    }

    private fun resolveTemplate(targetTemplate: String): String? {
        val normalized = normalizeTemplateName(targetTemplate)
        val candidates = buildList {
            add(normalized)
            if (!normalized.endsWith(".peb") && !normalized.endsWith(".pebble")) {
                add("$normalized.peb")
                add("$normalized.pebble")
            } else {
                add(normalized.substringBeforeLast('.'))
            }
        }

        return candidates.firstNotNullOfOrNull { candidate ->
            aliasesToUris[candidate]?.sorted()?.firstOrNull()
        }
    }

    private fun templateAliases(uri: String): Set<String> {
        val pathString = try {
            Paths.get(URI(uri)).normalize().toString().replace('\\', '/')
        } catch (_: Exception) {
            return emptySet()
        }
        val fileName = pathString.substringAfterLast('/')
        val aliases = linkedSetOf<String>()
        val templatesMarker = "/templates/"
        val relative = pathString.substringAfter(templatesMarker, missingDelimiterValue = fileName)
        val normalizedRelative = normalizeTemplateName(relative)
        aliases.add(normalizedRelative)
        aliases.add(normalizedRelative.substringBeforeLast('.', normalizedRelative))
        aliases.add(normalizeTemplateName(fileName))
        aliases.add(normalizeTemplateName(fileName.substringBeforeLast('.', fileName)))
        return aliases
    }

    private fun normalizeTemplateName(name: String): String =
        name.trim().removePrefix("/").removePrefix("./").replace('\\', '/')
}
