package dev.kouros.sidecar

import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

internal data class NavigationTarget(
    val title: String,
    val targetUri: String,
    val selection: JsonObject,
)

internal fun KaSession.findImplementationNavigationTargets(
    ctx: BridgeContext,
    interfaceDeclaration: KtClass,
): List<NavigationTarget> {
    val interfaceSymbol = interfaceDeclaration.symbol as? KaNamedClassSymbol ?: return emptyList()
    if (interfaceSymbol.classKind != KaClassKind.INTERFACE) return emptyList()
    val interfaceClassId = interfaceSymbol.classId ?: return emptyList()

    return ctx.allSessionKtFiles()
        .asSequence()
        .flatMap { file ->
            PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java).asSequence()
        }
        .filter { candidate -> candidate !== interfaceDeclaration }
        .filter { candidate -> isConcreteImplementationCandidate(candidate) }
        .mapNotNull { candidate ->
            val candidateSymbol = candidate.symbol as? KaNamedClassSymbol ?: return@mapNotNull null
            val implementsInterface = candidateSymbol.superTypes
                .filterIsInstance<KaClassType>()
                .any { superType ->
                    val superSymbol = superType.symbol as? KaNamedClassSymbol
                    superSymbol?.classKind == KaClassKind.INTERFACE &&
                        superSymbol.classId == interfaceClassId
                }
            if (!implementsInterface) return@mapNotNull null

            val candidateName = candidate.name ?: return@mapNotNull null
            navigationTargetFor(ctx, candidate, "Go to Implementation: $candidateName")
        }
        .distinctBy { it.targetUri to it.title }
        .toList()
}

internal fun KaSession.findInterfaceNavigationTargets(
    ctx: BridgeContext,
    classOrObject: KtClassOrObject,
): List<NavigationTarget> {
    val classSymbol = classOrObject.symbol as? KaNamedClassSymbol ?: return emptyList()
    if (classSymbol.classKind == KaClassKind.INTERFACE) return emptyList()

    return classSymbol.superTypes
        .filterIsInstance<KaClassType>()
        .mapNotNull { superType ->
            val superSymbol = superType.symbol as? KaNamedClassSymbol ?: return@mapNotNull null
            if (superSymbol.classKind != KaClassKind.INTERFACE) return@mapNotNull null
            val interfacePsi = superSymbol.psi as? KtClassOrObject ?: return@mapNotNull null
            val interfaceName = interfacePsi.name ?: return@mapNotNull null
            navigationTargetFor(ctx, interfacePsi, "Go to Interface: $interfaceName")
        }
        .distinctBy { it.targetUri to it.title }
        .toList()
}

internal fun isConcreteImplementationCandidate(candidate: KtClassOrObject): Boolean {
    if (candidate is KtObjectDeclaration && candidate.isCompanion()) return false
    if (candidate is KtClass && (candidate.isInterface() || candidate.isEnum() || candidate.hasModifier(KtTokens.ABSTRACT_KEYWORD))) {
        return false
    }
    return true
}

internal fun navigationTargetFor(
    ctx: BridgeContext,
    declaration: KtClassOrObject,
    title: String,
): NavigationTarget? {
    val targetUri = ctx.fileUriForKtFile(declaration.containingKtFile) ?: return null
    val nameIdentifier = declaration.nameIdentifier ?: return null
    val document = declaration.containingKtFile.viewProvider.document ?: return null
    return NavigationTarget(
        title = title,
        targetUri = targetUri,
        selection = ctx.selectionForOffset(document, nameIdentifier.textOffset, nameIdentifier.textLength),
    )
}
