package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance

internal fun BridgeContext.typeHierarchyPrepare(uri: String, line: Int, character: Int): JsonObject {
    val result = JsonObject()
    val itemsArray = JsonArray()

    val currentSession = session ?: run {
        result.add("items", itemsArray)
        return result
    }
    val ktFile = findKtFile(currentSession, uri) ?: run {
        result.add("items", itemsArray)
        return result
    }

    try {
        val offset = lineColToOffset(ktFile, line, character) ?: run {
            result.add("items", itemsArray)
            return result
        }

        val element = ktFile.findElementAt(offset)
        if (element != null) {
            val classOrObject = PsiTreeUtil.getParentOfType(
                element, KtClassOrObject::class.java, false
            )
            if (classOrObject != null) {
                val document = ktFile.viewProvider.document
                if (document != null) {
                    val item = buildTypeHierarchyItem(classOrObject, ktFile, document)
                    if (item != null) {
                        itemsArray.add(item)
                    }
                }
            }
        }
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: typeHierarchyPrepare failed: ${e.javaClass.name}: ${e.message}")
    }

    result.add("items", itemsArray)
    return result
}

internal fun BridgeContext.typeHierarchySupertypes(uri: String, line: Int, character: Int, name: String): JsonObject {
    val result = JsonObject()
    val supertypesArray = JsonArray()

    val currentSession = session ?: run {
        result.add("supertypes", supertypesArray)
        return result
    }
    val ktFile = findKtFile(currentSession, uri) ?: run {
        result.add("supertypes", supertypesArray)
        return result
    }

    try {
        analyze(ktFile) {
            val offset = lineColToOffset(ktFile, line, character) ?: run {
                result.add("supertypes", supertypesArray)
                return@analyze
            }

            val element = ktFile.findElementAt(offset) ?: run {
                result.add("supertypes", supertypesArray)
                return@analyze
            }

            val classOrObject = PsiTreeUtil.getParentOfType(
                element, KtClassOrObject::class.java, false
            ) ?: run {
                result.add("supertypes", supertypesArray)
                return@analyze
            }

            val classSymbol = classOrObject.symbol as? KaNamedClassSymbol ?: run {
                result.add("supertypes", supertypesArray)
                return@analyze
            }

            for (superType in classSymbol.superTypes) {
                if (superType is KaClassType) {
                    val superSymbol = superType.symbol
                    val superName = if (superSymbol is KaNamedSymbol) {
                        superSymbol.name.asString()
                    } else {
                        superType.render(
                            KaTypeRendererForSource.WITH_SHORT_NAMES,
                            Variance.INVARIANT
                        )
                    }

                    if (superName == "Any" && classSymbol.superTypes.size > 1) continue

                    val superObj = JsonObject()
                    superObj.addProperty("name", superName)

                    val kind = when {
                        superSymbol is KaNamedClassSymbol && superSymbol.classKind == KaClassKind.INTERFACE -> "interface"
                        superSymbol is KaNamedClassSymbol && superSymbol.classKind == KaClassKind.ENUM_CLASS -> "enum"
                        superSymbol is KaNamedClassSymbol && superSymbol.classKind == KaClassKind.OBJECT -> "object"
                        else -> "class"
                    }
                    superObj.addProperty("kind", kind)

                    val superPsi = superSymbol.psi
                    if (superPsi != null) {
                        val superFile = superPsi.containingFile
                        val superDoc = superFile?.viewProvider?.document
                        if (superFile != null && superDoc != null) {
                            val superOffset = superPsi.textOffset
                            val superLine = superDoc.getLineNumber(superOffset) + 1
                            val superLineStart = superDoc.getLineStartOffset(superDoc.getLineNumber(superOffset))
                            val superCol = superOffset - superLineStart

                            superObj.addProperty("uri", "file://${superFile.virtualFile.path}")
                            superObj.addProperty("line", superLine)
                            superObj.addProperty("column", superCol)
                        } else {
                            superObj.addProperty("uri", "")
                            superObj.addProperty("line", 0)
                            superObj.addProperty("column", 0)
                        }
                    } else {
                        superObj.addProperty("uri", "")
                        superObj.addProperty("line", 0)
                        superObj.addProperty("column", 0)
                    }

                    supertypesArray.add(superObj)
                }
            }
        }
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: typeHierarchySupertypes failed: ${e.javaClass.name}: ${e.message}")
    }

    result.add("supertypes", supertypesArray)
    return result
}

private fun buildTypeHierarchyItem(
    classOrObject: KtClassOrObject,
    ktFile: KtFile,
    document: com.intellij.openapi.editor.Document,
): JsonObject? {
    val name = classOrObject.name ?: return null
    val nameIdentifier = classOrObject.nameIdentifier ?: return null

    val declOffset = nameIdentifier.textOffset
    val declLine = document.getLineNumber(declOffset) + 1
    val declLineStart = document.getLineStartOffset(document.getLineNumber(declOffset))
    val declCol = declOffset - declLineStart

    val kind = when {
        classOrObject is KtObjectDeclaration -> "object"
        (classOrObject as? KtClass)?.isInterface() == true -> "interface"
        (classOrObject as? KtClass)?.isEnum() == true -> "enum"
        else -> "class"
    }

    val item = JsonObject()
    item.addProperty("name", name)
    item.addProperty("kind", kind)
    item.addProperty("uri", "file://${ktFile.virtualFile.path}")
    item.addProperty("line", declLine)
    item.addProperty("column", declCol)
    return item
}
