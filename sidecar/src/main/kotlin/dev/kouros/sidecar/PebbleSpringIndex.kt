package dev.kouros.sidecar

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

internal data class KotlinSourceLocation(
    val uri: String,
    val line: Int,
    val column: Int,
)

internal data class SpringViewFact(
    val sourceUri: String,
    val range: PebbleRange,
    val templateUri: String,
)

internal data class SpringModelAttributeFact(
    val templateUri: String,
    val variableName: String,
    val producerLocation: KotlinSourceLocation,
    val producerType: String?,
)

internal data class PebbleUsageLocation(
    val uri: String,
    val line: Int,
    val column: Int,
)

private data class PebbleResolvedVariable(
    val producerLocation: KotlinSourceLocation?,
    val producerType: String?,
)

internal class PebbleSpringIndex(
    private val templateIndex: PebbleTemplateIndex,
) {
    private val viewFactsByUri = linkedMapOf<String, MutableList<SpringViewFact>>()
    private val modelFactsByTemplateAndName = linkedMapOf<Pair<String, String>, MutableList<SpringModelAttributeFact>>()
    private val kotlinFilesByUri = linkedMapOf<String, KtFile>()

    fun rebuild(kotlinFilesByUri: Map<String, KtFile>) {
        viewFactsByUri.clear()
        modelFactsByTemplateAndName.clear()
        this.kotlinFilesByUri.clear()
        this.kotlinFilesByUri.putAll(kotlinFilesByUri.toSortedMap())

        for ((uri, ktFile) in this.kotlinFilesByUri) {
            indexFile(uri, ktFile)
        }
    }

    fun definitionForKotlinView(uri: String, line: Int, character: Int): String? =
        viewFactsByUri[uri]
            ?.firstOrNull { it.range.contains(line, character) }
            ?.templateUri

    fun definitionForPebbleSymbol(uri: String, line: Int, character: Int): KotlinSourceLocation? {
        val variable = templateIndex.facts(uri)
            ?.variableReferences
            ?.firstOrNull { it.range.contains(line, character) }
            ?: return null
        val segmentIndex = segmentIndexAt(variable, character) ?: return null
        val resolvedRoot = resolveVariableForRoot(uri, variable.segments.firstOrNull() ?: return null) ?: return null

        if (segmentIndex == 0) {
            return resolvedRoot.producerLocation ?: resolvedRoot.producerType?.let(::resolveTypeLocation)
        }

        val ownerType = resolveVariableType(uri, variable.segments.take(segmentIndex)) ?: return null
        val memberName = variable.segments.getOrNull(segmentIndex) ?: return null
        return resolveMemberLocation(ownerType, memberName)
    }

    fun producerTypeForPebbleVariableRoot(uri: String, line: Int, character: Int): String? {
        val variable = templateIndex.facts(uri)
            ?.variableReferences
            ?.firstOrNull { it.range.contains(line, character) }
            ?: return null
        val segmentIndex = segmentIndexAt(variable, character) ?: return null
        if (segmentIndex != 0) return null
        val rootName = variable.segments.firstOrNull() ?: return null
        return resolveVariableForRoot(uri, rootName)?.producerType
    }

    fun pebbleUsagesForDeclaration(declaration: PsiElement, declarationUri: String): List<PebbleUsageLocation> {
        val declarationLocation = declaration.toSourceLocation(declarationUri)
        val declarationName = (declaration as? KtNamedDeclaration)?.name ?: return emptyList()
        val usages = linkedSetOf<PebbleUsageLocation>()
        val declarationClassName = when (declaration) {
            is KtClassOrObject -> declaration.name
            is KtParameter -> if (declaration.hasValOrVar()) declaration.parents.filterIsInstance<KtClassOrObject>().firstOrNull()?.name else null
            is KtProperty -> declaration.parents.filterIsInstance<KtClassOrObject>().firstOrNull()?.name
            is KtNamedFunction -> declaration.parents.filterIsInstance<KtClassOrObject>().firstOrNull()?.name
            else -> null
        }

        for ((templateUri, facts) in allTemplateFacts()) {
            for (ref in facts.variableReferences) {
                val resolvedRoot = resolveVariableForRoot(templateUri, ref.segments.firstOrNull() ?: continue) ?: continue

                if (declarationLocation != null && resolvedRoot.producerLocation == declarationLocation) {
                    usages.add(PebbleUsageLocation(templateUri, ref.range.startLine, ref.range.startColumn))
                }

                if (declarationClassName != null && normalizedTypeName(resolvedRoot.producerType) == declarationClassName) {
                    when (declaration) {
                        is KtClassOrObject -> usages.add(PebbleUsageLocation(templateUri, ref.range.startLine, ref.range.startColumn))
                        is KtParameter -> if (declaration.hasValOrVar()) {
                            val idx = ref.segments.indexOf(declarationName)
                            if (idx > 0) usages.add(PebbleUsageLocation(templateUri, ref.range.startLine, segmentStartColumn(ref, idx)))
                        }
                        is KtProperty, is KtNamedFunction -> {
                            val idx = ref.segments.indexOf(declarationName)
                            if (idx > 0) usages.add(PebbleUsageLocation(templateUri, ref.range.startLine, segmentStartColumn(ref, idx)))
                        }
                    }
                }
            }
        }

        return usages.sortedWith(compareBy<PebbleUsageLocation>({ it.uri }, { it.line }, { it.column }))
    }

    private fun allTemplateFacts(): Sequence<Pair<String, PebbleTemplateFacts>> =
        templateIndex.allFacts().toSortedMap().asSequence().map { it.key to it.value }

    private fun indexFile(uri: String, ktFile: KtFile) {
        val functions = PsiTreeUtil.collectElementsOfType(ktFile, KtNamedFunction::class.java)
        for (function in functions) {
            indexFunction(uri, ktFile, function)
        }
    }

    private fun indexFunction(uri: String, ktFile: KtFile, function: KtNamedFunction) {
        val viewFacts = extractViewFacts(uri, ktFile, function)
        for (fact in viewFacts) {
            viewFactsByUri.getOrPut(uri) { mutableListOf() }.add(fact)
        }

        val templateUri = viewFacts.map { it.templateUri }.distinct().singleOrNull() ?: return
        val callExpressions = PsiTreeUtil.collectElementsOfType(function, KtCallExpression::class.java)
        for (call in callExpressions) {
            val calleeName = call.calleeExpression?.text ?: continue
            if (calleeName != "addAttribute" && calleeName != "addObject") continue

            val args = call.valueArguments
            if (args.size < 2) continue
            val variableName = staticStringValue(args[0].getArgumentExpression()) ?: continue
            val valueExpression = args[1].getArgumentExpression() ?: continue
            val producerElement = resolveProducerElement(function, valueExpression) ?: valueExpression
            val producerLocation = producerElement.toSourceLocation(uri) ?: continue
            val producerType = inferProducerType(producerElement)

            val fact = SpringModelAttributeFact(
                templateUri = templateUri,
                variableName = variableName,
                producerLocation = producerLocation,
                producerType = producerType,
            )
            modelFactsByTemplateAndName.getOrPut(templateUri to variableName) { mutableListOf() }.add(fact)
        }
    }

    private fun extractViewFacts(uri: String, ktFile: KtFile, function: KtNamedFunction): List<SpringViewFact> {
        val facts = mutableListOf<SpringViewFact>()

        fun collect(expression: KtExpression?) {
            val fact = viewFactFromExpression(uri, ktFile, expression)
            if (fact != null) facts.add(fact)
        }

        val body = function.bodyExpression
        when (body) {
            is KtBlockExpression -> {
                for (returnExpression in PsiTreeUtil.collectElementsOfType(body, KtReturnExpression::class.java)) {
                    collect(returnExpression.returnedExpression)
                }
            }
            else -> collect(body)
        }

        for (call in PsiTreeUtil.collectElementsOfType(function, KtCallExpression::class.java)) {
            val fact = viewFactFromExpression(uri, ktFile, call)
            if (fact != null) facts.add(fact)
        }

        return facts.distinctBy { Triple(it.range, it.templateUri, it.sourceUri) }
    }

    private fun viewFactFromExpression(uri: String, ktFile: KtFile, expression: KtExpression?): SpringViewFact? {
        val targetTemplate = when (expression) {
            is KtStringTemplateExpression -> staticStringValue(expression)
            is KtCallExpression -> {
                val calleeText = expression.calleeExpression?.text ?: return null
                if (calleeText != "ModelAndView" && !calleeText.endsWith(".ModelAndView")) return null
                staticStringValue(expression.valueArguments.firstOrNull()?.getArgumentExpression())
            }
            else -> null
        } ?: return null

        val templateUri = templateIndex.resolveTemplateName(uri, targetTemplate) ?: return null
        val range = expression?.toRange(ktFile) ?: return null
        return SpringViewFact(
            sourceUri = uri,
            range = range,
            templateUri = templateUri,
        )
    }

    private fun staticStringValue(expression: KtExpression?): String? {
        val stringTemplate = expression as? KtStringTemplateExpression ?: return null
        if (stringTemplate.hasInterpolation()) return null
        return stringTemplate.entries.joinToString(separator = "") { it.text }
    }

    private fun resolveProducerElement(function: KtNamedFunction, expression: KtExpression): PsiElement? {
        val nameExpression = expression as? KtNameReferenceExpression ?: return expression
        val targetName = nameExpression.getReferencedName()

        val parameter = function.valueParameters.firstOrNull { it.name == targetName }
        if (parameter != null) return parameter

        val localProperty = PsiTreeUtil.collectElementsOfType(function, KtProperty::class.java)
            .filter { it.name == targetName && it.textOffset <= nameExpression.textOffset }
            .maxByOrNull { it.textOffset }
        if (localProperty != null) return localProperty

        val classBody = function.parents.filterIsInstance<KtClassBody>().firstOrNull()
        val classProperty = classBody
            ?.declarations
            ?.filterIsInstance<KtProperty>()
            ?.firstOrNull { it.name == targetName }
        if (classProperty != null) return classProperty

        val fileProperty = function.containingKtFile.declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == targetName }
        if (fileProperty != null) return fileProperty

        return expression
    }

    private fun inferProducerType(element: PsiElement): String? = when (element) {
        is KtParameter -> element.typeReference?.text
        is KtProperty -> element.typeReference?.text ?: inferTypeFromInitializer(element.initializer)
        is KtNamedDeclaration -> null
        is KtExpression -> inferTypeFromInitializer(element)
        else -> null
    }

    private fun inferTypeFromInitializer(expression: KtExpression?): String? = when (expression) {
        is KtCallExpression -> expression.calleeExpression?.text
        is KtNameReferenceExpression -> expression.getReferencedName()
        else -> null
    }

    private fun resolveVariableForRoot(
        uri: String,
        rootName: String,
        visited: MutableSet<Pair<String, String>> = linkedSetOf(),
    ): PebbleResolvedVariable? {
        val key = uri to rootName
        if (!visited.add(key)) return null
        try {
            modelFactsByTemplateAndName[uri to rootName]
                ?.sortedWith(compareBy<SpringModelAttributeFact>({ it.producerLocation.uri }, { it.producerLocation.line }, { it.producerLocation.column }))
                ?.firstOrNull()
                ?.let { return PebbleResolvedVariable(it.producerLocation, it.producerType) }

            templateIndex.facts(uri)
                ?.variableHints
                ?.firstOrNull { it.name == rootName }
                ?.let { return PebbleResolvedVariable(producerLocation = null, producerType = it.type) }

            templateIndex.facts(uri)
                ?.forLoopAliases
                ?.firstOrNull { it.alias == rootName }
                ?.let { alias ->
                    val sourceResolved = resolveVariableForSegments(uri, alias.sourceSegments, visited) ?: return@let null
                    val elementType = collectionElementType(sourceResolved.producerType ?: return@let null) ?: return@let null
                    return PebbleResolvedVariable(
                        producerLocation = sourceResolved.producerLocation,
                        producerType = elementType,
                    )
                }

            templateIndex.incomingIncludeBindings(uri)
                .asSequence()
                .filter { it.targetVariableName == rootName }
                .mapNotNull { binding -> resolveVariableForSegments(binding.sourceTemplateUri, binding.sourceSegments, visited) }
                .firstOrNull()
                ?.let { return it }

            return null
        } finally {
            visited.remove(key)
        }
    }

    private fun resolveVariableForSegments(
        uri: String,
        segments: List<String>,
        visited: MutableSet<Pair<String, String>> = linkedSetOf(),
    ): PebbleResolvedVariable? {
        if (segments.isEmpty()) return null
        var resolved = resolveVariableForRoot(uri, segments.first(), visited) ?: return null
        for (segment in segments.drop(1)) {
            val ownerType = resolved.producerType ?: return null
            val memberType = resolveMemberType(ownerType, segment) ?: return null
            val memberLocation = resolveMemberLocation(ownerType, segment)
            resolved = PebbleResolvedVariable(
                producerLocation = memberLocation ?: resolved.producerLocation,
                producerType = memberType,
            )
        }
        return resolved
    }

    private fun resolveVariableType(uri: String, segments: List<String>): String? =
        resolveVariableForSegments(uri, segments)?.producerType

    private fun collectionElementType(typeName: String): String? {
        val normalized = typeName.removeSuffix("?").trim()
        if (normalized.startsWith("Array<") && normalized.endsWith(">")) {
            return normalized.substringAfter('<').substringBeforeLast('>').trim()
        }
        val genericBase = normalized.substringBefore('<').substringAfterLast('.')
        if (genericBase !in COLLECTION_TYPE_NAMES) return null
        return normalized.substringAfter('<').substringBeforeLast('>', missingDelimiterValue = "").trim().ifEmpty { null }
    }

    private fun resolveMemberType(typeName: String, memberName: String): String? {
        val targetClass = findClass(typeName) ?: return null

        targetClass.primaryConstructorParameters
            .firstOrNull { it.hasValOrVar() && it.name == memberName }
            ?.typeReference
            ?.text
            ?.let { return it }

        targetClass.declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == memberName }
            ?.typeReference
            ?.text
            ?.let { return it }

        targetClass.declarations
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull { it.name == memberName }
            ?.typeReference
            ?.text
            ?.let { return it }

        return null
    }

    private fun resolveTypeLocation(typeName: String): KotlinSourceLocation? {
        val targetClass = findClass(typeName) ?: return null
        val targetUri = kotlinFilesByUri.entries.firstOrNull { (_, file) -> file == targetClass.containingKtFile }?.key ?: return null
        return targetClass.toSourceLocation(targetUri)
    }

    private fun resolveMemberLocation(typeName: String, memberName: String): KotlinSourceLocation? {
        val targetClass = findClass(typeName) ?: return null
        val targetUri = kotlinFilesByUri.entries.firstOrNull { (_, file) -> file == targetClass.containingKtFile }?.key ?: return null

        targetClass.primaryConstructorParameters
            .firstOrNull { it.hasValOrVar() && it.name == memberName }
            ?.toSourceLocation(targetUri)
            ?.let { return it }

        targetClass.declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == memberName }
            ?.toSourceLocation(targetUri)
            ?.let { return it }

        targetClass.declarations
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull { it.name == memberName }
            ?.toSourceLocation(targetUri)
            ?.let { return it }

        return null
    }

    private fun findClass(typeName: String): KtClassOrObject? {
        val normalizedTypeName = normalizedTypeName(typeName)
        for ((_, ktFile) in kotlinFilesByUri) {
            val classes = PsiTreeUtil.collectElementsOfType(ktFile, KtClassOrObject::class.java)
            val targetClass = classes.firstOrNull { it.name == normalizedTypeName }
            if (targetClass != null) return targetClass
        }
        return null
    }

    private fun normalizedTypeName(typeName: String?): String? = typeName
        ?.substringBefore('<')
        ?.removeSuffix("?")
        ?.substringAfterLast('.')
        ?.trim()
        ?.ifEmpty { null }

    private fun segmentIndexAt(variable: PebbleVariableReference, character: Int): Int? {
        var column = variable.range.startColumn
        variable.segments.forEachIndexed { index, segment ->
            val start = column
            val end = start + segment.length
            if (character in start until end) return index
            column = end + 1
        }
        return null
    }

    private fun segmentStartColumn(variable: PebbleVariableReference, segmentIndex: Int): Int {
        var column = variable.range.startColumn
        repeat(segmentIndex) { index ->
            column += variable.segments[index].length + 1
        }
        return column
    }

    private fun PsiElement.toSourceLocation(uri: String): KotlinSourceLocation? {
        val containingKtFile = containingFile as? KtFile ?: return null
        val range = toRange(containingKtFile) ?: return null
        return KotlinSourceLocation(uri = uri, line = range.startLine, column = range.startColumn)
    }

    private fun PsiElement.toRange(ktFile: KtFile): PebbleRange? {
        val text = ktFile.text
        if (text.isEmpty()) return null
        val lineIndex = PebbleLineIndex(text)
        return lineIndex.range(textRange.startOffset, textRange.endOffset)
    }

    private companion object {
        val COLLECTION_TYPE_NAMES = setOf(
            "Array",
            "Collection",
            "Iterable",
            "List",
            "MutableCollection",
            "MutableIterable",
            "MutableList",
            "MutableSet",
            "Set",
        )
    }
}
