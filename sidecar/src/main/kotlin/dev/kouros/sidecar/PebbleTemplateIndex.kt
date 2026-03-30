package dev.kouros.sidecar

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
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

internal data class PebbleVariableHint(
    val name: String,
    val type: String,
)

internal data class PebbleForLoopAlias(
    val alias: String,
    val sourceSegments: List<String>,
)

internal data class PebbleIncludeVariableBinding(
    val targetTemplate: String,
    val targetVariableName: String,
    val sourceSegments: List<String>,
)

internal data class PebbleTemplateFacts(
    val uri: String,
    val templateReferences: List<PebbleTemplateReference>,
    val variableReferences: List<PebbleVariableReference>,
    val variableHints: List<PebbleVariableHint>,
    val forLoopAliases: List<PebbleForLoopAlias>,
    val includeVariableBindings: List<PebbleIncludeVariableBinding>,
)

internal data class ResolvedIncludeBinding(
    val sourceTemplateUri: String,
    val targetTemplateUri: String,
    val targetVariableName: String,
    val sourceSegments: List<String>,
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
    private val variableChainPattern = Regex(
        """\b([A-Za-z_][A-Za-z0-9_]*)(\.(?:[A-Za-z_][A-Za-z0-9_]*)(?:\(\))?)*\b(?:\(\))?"""
    )
    private val pebVariablePattern = Regex("""\{#\s*@pebvariable\b(.*?)#}""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val pebVariableAttributePattern = Regex(
        """\b(name|type)\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s#}]+))"""
    )
    private val forLoopPattern = Regex(
        """\{%\s*for\s+([A-Za-z_][A-Za-z0-9_]*)\s+in\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*(?:\(\))?)*)\s*%}"""
    )
    private val includeWithPattern = Regex(
        """\{%\s*include\s+(['"])([^'"]+)\1\s+with\s*\{(.*?)}\s*%}""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val includeWithEntryPattern = Regex("""^\s*(["'])([^"']+)\1\s*:\s*(.*?)\s*$""")
    private val simpleVariableChainPattern = Regex(
        """^[A-Za-z_][A-Za-z0-9_]*(?:\.(?:[A-Za-z_][A-Za-z0-9_]*)(?:\(\))?)*$"""
    )

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
                val segments = chain.value
                    .split('.')
                    .map { it.removeSuffix("()").trim() }
                    .filter { it.isNotEmpty() }
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

        val variableHints = pebVariablePattern.findAll(text).mapNotNull { match ->
            val attributes = linkedMapOf<String, String>()
            for (attributeMatch in pebVariableAttributePattern.findAll(match.groupValues[1])) {
                val value = attributeMatch.groups[2]?.value
                    ?: attributeMatch.groups[3]?.value
                    ?: attributeMatch.groups[4]?.value
                    ?: continue
                attributes[attributeMatch.groupValues[1]] = value
            }
            val name = attributes["name"] ?: return@mapNotNull null
            val type = attributes["type"] ?: return@mapNotNull null
            PebbleVariableHint(name = name, type = type)
        }.toList()

        val forLoopAliases = forLoopPattern.findAll(text).mapNotNull { match ->
            val alias = match.groupValues[1].trim()
            val sourceSegments = parseVariableSegments(match.groupValues[2]) ?: return@mapNotNull null
            if (alias.isEmpty() || sourceSegments.isEmpty()) return@mapNotNull null
            PebbleForLoopAlias(alias = alias, sourceSegments = sourceSegments)
        }.toList()

        val includeVariableBindings = includeWithPattern.findAll(text).flatMap { match ->
            val targetTemplate = match.groupValues[2].trim()
            match.groupValues[3]
                .split(',')
                .asSequence()
                .mapNotNull { entry ->
                    val parsedEntry = includeWithEntryPattern.matchEntire(entry) ?: return@mapNotNull null
                    val targetVariableName = parsedEntry.groupValues[2].trim()
                    val sourceSegments = parseVariableSegments(parsedEntry.groupValues[3]) ?: return@mapNotNull null
                    PebbleIncludeVariableBinding(
                        targetTemplate = targetTemplate,
                        targetVariableName = targetVariableName,
                        sourceSegments = sourceSegments,
                    )
                }
        }.toList()

        return PebbleTemplateFacts(
            uri = uri,
            templateReferences = templateReferences,
            variableReferences = variableReferences,
            variableHints = variableHints,
            forLoopAliases = forLoopAliases,
            includeVariableBindings = includeVariableBindings,
        )
    }

    private fun parseVariableSegments(expression: String): List<String>? {
        val normalizedExpression = expression.trim()
        if (!simpleVariableChainPattern.matches(normalizedExpression)) return null
        val segments = normalizedExpression
            .split('.')
            .map { it.removeSuffix("()").trim() }
            .filter { it.isNotEmpty() }
        val root = segments.firstOrNull() ?: return null
        if (root in RESERVED_WORDS) return null
        return segments
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
    private val includeBindingsByResolvedTarget = linkedMapOf<String, MutableList<ResolvedIncludeBinding>>()

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
        return resolveTemplate(reference.sourceUri, reference.targetTemplate)
    }

    fun referencesAt(uri: String, line: Int, character: Int): List<PebbleTemplateReference> {
        val reference = findTemplateReferenceAt(uri, line, character)
        if (reference != null) {
            val targetUri = resolveTemplate(reference.sourceUri, reference.targetTemplate) ?: return emptyList()
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

    fun allFacts(): Map<String, PebbleTemplateFacts> = factsByUri.toMap()

    fun incomingIncludeBindings(targetTemplateUri: String): List<ResolvedIncludeBinding> =
        includeBindingsByResolvedTarget[targetTemplateUri].orEmpty().sortedWith(
            compareBy<ResolvedIncludeBinding>({ it.sourceTemplateUri }, { it.targetVariableName }, { it.sourceSegments.joinToString(".") })
        )

    fun resolveTemplateName(sourceUri: String, targetTemplate: String): String? =
        resolveTemplate(sourceUri, targetTemplate)

    private fun rebuild() {
        factsByUri.clear()
        aliasesToUris.clear()
        referencesByResolvedTarget.clear()
        includeBindingsByResolvedTarget.clear()

        for ((uri, text) in documents.toSortedMap()) {
            factsByUri[uri] = parser.parse(uri, text)
            for (alias in templateAliases(uri)) {
                aliasesToUris.getOrPut(alias) { linkedSetOf() }.add(uri)
            }
        }

        for (facts in factsByUri.values) {
            for (reference in facts.templateReferences) {
                val targetUri = resolveTemplate(reference.sourceUri, reference.targetTemplate) ?: continue
                referencesByResolvedTarget.getOrPut(targetUri) { mutableListOf() }.add(reference)
            }
            for (binding in facts.includeVariableBindings) {
                val targetUri = resolveTemplate(facts.uri, binding.targetTemplate) ?: continue
                includeBindingsByResolvedTarget.getOrPut(targetUri) { mutableListOf() }.add(
                    ResolvedIncludeBinding(
                        sourceTemplateUri = facts.uri,
                        targetTemplateUri = targetUri,
                        targetVariableName = binding.targetVariableName,
                        sourceSegments = binding.sourceSegments,
                    )
                )
            }
        }
    }

    private fun resolveTemplate(sourceUri: String, targetTemplate: String): String? {
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
                ?: resolveTemplateOnDisk(sourceUri, candidate)
        }
    }

    private fun resolveTemplateOnDisk(sourceUri: String, candidate: String): String? {
        val sourcePath = uriToPath(sourceUri) ?: return null

        sourcePath.parent?.let { parent ->
            val resolved = parent.resolve(candidate).normalize()
            if (Files.isRegularFile(resolved)) {
                return resolved.toUri().toString()
            }
        }

        val templateRoots = templateRoots(sourcePath)
        return templateRoots.firstNotNullOfOrNull { root ->
            val resolved = root.resolve(candidate).normalize()
            if (Files.isRegularFile(resolved)) {
                resolved.toUri().toString()
            } else {
                null
            }
        }
    }

    private fun templateRoots(sourcePath: Path): List<Path> {
        val normalized = sourcePath.normalize()
        val markers = listOf("templates", "views")
        val roots = linkedSetOf<Path>()
        for (index in 0 until normalized.nameCount) {
            if (normalized.getName(index).toString() in markers) {
                roots.add(normalized.root?.resolve(normalized.subpath(0, index + 1)) ?: normalized.subpath(0, index + 1))
            }
        }

        var current = normalized.parent
        while (current != null) {
            roots.add(current.resolve("src/main/resources/templates"))
            roots.add(current.resolve("src/main/resources/views"))
            roots.add(current.resolve("src/frontend/templates"))
            roots.add(current.resolve("build/frontend/templates"))
            roots.add(current.resolve("templates"))
            roots.add(current.resolve("views"))
            current = current.parent
        }

        return roots.filter { Files.isDirectory(it) }
    }

    private fun uriToPath(uri: String): Path? = try {
        Paths.get(URI(uri)).normalize()
    } catch (_: Exception) {
        null
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
