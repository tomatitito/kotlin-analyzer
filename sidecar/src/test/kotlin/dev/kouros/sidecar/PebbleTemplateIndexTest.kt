package dev.kouros.sidecar

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PebbleTemplateIndexTest {

    @Test
    fun `parser captures template and variable references with stable ranges`() {
        val uri = "file:///workspace/src/main/resources/templates/users/detail.peb"
        val parser = PebbleTemplateParser()
        val facts = parser.parse(
            uri = uri,
            text = """
                {% extends "layouts/base" %}
                {% include "shared/card.peb" %}
                {{ user.name }}
            """.trimIndent(),
        )

        assertEquals(2, facts.templateReferences.size)
        assertEquals(1, facts.variableReferences.size)

        val extendsRef = facts.templateReferences.first()
        assertEquals(PebbleReferenceKind.EXTENDS, extendsRef.kind)
        assertEquals("layouts/base", extendsRef.targetTemplate)
        assertEquals(PebbleRange(1, 12, 1, 24), extendsRef.range)
        assertTrue(extendsRef.range.contains(1, 12))
        assertTrue(extendsRef.range.contains(1, 23))

        val variableRef = facts.variableReferences.single()
        assertEquals(listOf("user", "name"), variableRef.segments)
        assertEquals(PebbleRange(3, 3, 3, 12), variableRef.range)
    }

    @Test
    fun `index resolves template definition using template aliases`() {
        val index = PebbleTemplateIndex()
        val baseUri = "file:///workspace/src/main/resources/templates/layouts/base.peb"
        val pageUri = "file:///workspace/src/main/resources/templates/users/detail.peb"

        index.update(baseUri, "<html>{% block body %}{% endblock %}</html>")
        index.update(pageUri, """{% extends "layouts/base" %}""")

        assertEquals(baseUri, index.definition(pageUri, line = 1, character = 15))

        val refs = index.referencesToTemplate(baseUri)
        assertEquals(1, refs.size)
        assertEquals(pageUri, refs.single().sourceUri)
        assertEquals(PebbleReferenceKind.EXTENDS, refs.single().kind)
    }

    @Test
    fun `index invalidates old template edges on document change and removal`() {
        val index = PebbleTemplateIndex()
        val baseUri = "file:///workspace/src/main/resources/templates/layouts/base.peb"
        val altUri = "file:///workspace/src/main/resources/templates/layouts/alt.peb"
        val pageUri = "file:///workspace/src/main/resources/templates/users/detail.peb"

        index.update(baseUri, "base")
        index.update(altUri, "alt")
        index.update(pageUri, """{% extends "layouts/base" %}""")

        assertEquals(baseUri, index.definition(pageUri, line = 1, character = 15))
        assertEquals(1, index.referencesToTemplate(baseUri).size)

        index.update(pageUri, """{% extends "layouts/alt" %}""")
        assertEquals(altUri, index.definition(pageUri, line = 1, character = 15))
        assertTrue(index.referencesToTemplate(baseUri).isEmpty())
        assertEquals(1, index.referencesToTemplate(altUri).size)

        index.remove(pageUri)
        assertTrue(index.referencesToTemplate(altUri).isEmpty())
        assertNull(index.definition(pageUri, line = 1, character = 15))
    }

    @Test
    fun `compiler bridge exposes pebble definition and references as json locations`() {
        val bridge = CompilerBridge()
        val baseUri = "file:///workspace/src/main/resources/templates/layouts/base.peb"
        val pageUri = "file:///workspace/src/main/resources/templates/users/detail.peb"

        bridge.updatePebbleFile(baseUri, "base")
        bridge.updatePebbleFile(pageUri, """{% extends "layouts/base" %}""")

        val definition = bridge.pebbleDefinition(pageUri, line = 1, character = 15)
        val definitionLocations = definition.getAsJsonArray("locations")
        assertEquals(1, definitionLocations.size())
        assertEquals(baseUri, definitionLocations[0].asJsonObject.get("uri").asString)

        val references = bridge.pebbleReferences(baseUri, line = 1, character = 0)
        val referenceLocations = references.getAsJsonArray("locations")
        assertEquals(1, referenceLocations.size())
        val location = referenceLocations[0].asJsonObject
        assertEquals(pageUri, location.get("uri").asString)
        assertEquals(1, location.get("line").asInt)
        assertEquals(12, location.get("column").asInt)

        bridge.removePebbleFile(pageUri)
        val afterRemoval = bridge.pebbleReferences(baseUri, line = 1, character = 0)
        assertTrue(afterRemoval.getAsJsonArray("locations").isEmpty)
    }

    @Test
    fun `index returns reference under cursor when target template is unresolved`() {
        val index = PebbleTemplateIndex()
        val pageUri = "file:///workspace/src/main/resources/templates/users/detail.peb"

        index.update(pageUri, """{% include "missing/template" %}""")

        val reference = index.findTemplateReferenceAt(pageUri, line = 1, character = 14)
        assertNotNull(reference)
        assertEquals("missing/template", reference.targetTemplate)
        assertTrue(index.referencesAt(pageUri, line = 1, character = 14).isEmpty())
    }
}
