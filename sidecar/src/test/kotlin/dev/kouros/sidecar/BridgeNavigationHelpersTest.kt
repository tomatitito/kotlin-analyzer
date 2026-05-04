package dev.kouros.sidecar

import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeNavigationHelpersTest {

    @Test
    fun `NavigationTarget data class is accessible`() {
        val target = NavigationTarget(
            title = "Go to Interface: Foo",
            targetUri = "file:///test.kt",
            selection = com.google.gson.JsonObject(),
        )
        kotlin.test.assertEquals("Go to Interface: Foo", target.title)
        kotlin.test.assertEquals("file:///test.kt", target.targetUri)
    }

    @Test
    fun `isConcreteImplementationCandidate rejects interfaces and enums`() {
        val ctx = BridgeContext()
        ctx.initialize(
            projectRoot = "",
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = emptyList(),
        )
        val session = ctx.session!!
        val psiFactory = org.jetbrains.kotlin.psi.KtPsiFactory(session.project)

        val interfaceFile = psiFactory.createFile("interface Foo")
        val interfaceDecl = interfaceFile.declarations.first() as KtClass
        assertFalse(isConcreteImplementationCandidate(interfaceDecl))

        val classFile = psiFactory.createFile("class Bar")
        val classDecl = classFile.declarations.first() as KtClass
        assertTrue(isConcreteImplementationCandidate(classDecl))

        val enumFile = psiFactory.createFile("enum class Baz")
        val enumDecl = enumFile.declarations.first() as KtClass
        assertFalse(isConcreteImplementationCandidate(enumDecl))

        ctx.shutdown()
    }
}
