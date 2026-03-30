package dev.kouros.sidecar

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
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

internal class PebbleSpringIndex(
    private val templateIndex: PebbleTemplateIndex,
) {
    private val viewFactsByUri = linkedMapOf<String, MutableList<SpringViewFact>>()
    private val modelFactsByTemplateAndName = linkedMapOf<Pair<String, String>, MutableList<SpringModelAttributeFact>>()

    fun rebuild(kotlinFilesByUri: Map<String, KtFile>) {
        viewFactsByUri.clear()
        modelFactsByTemplateAndName.clear()

        for ((uri, ktFile) in kotlinFilesByUri.toSortedMap()) {
            indexFile(uri, ktFile)
        }
    }

    fun definitionForKotlinView(uri: String, line: Int, character: Int): String? =
        viewFactsByUri[uri]
            ?.firstOrNull { it.range.contains(line, character) }
            ?.templateUri

    fun definitionForPebbleVariableRoot(uri: String, line: Int, character: Int): SpringModelAttributeFact? {
        val variable = templateIndex.facts(uri)
            ?.variableReferences
            ?.firstOrNull { it.range.contains(line, character) }
            ?: return null
        val rootName = variable.segments.firstOrNull() ?: return null
        return modelFactsByTemplateAndName[uri to rootName]
            ?.sortedWith(compareBy<SpringModelAttributeFact>({ it.producerLocation.uri }, { it.producerLocation.line }, { it.producerLocation.column }))
            ?.firstOrNull()
    }

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
}
