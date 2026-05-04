package dev.kouros.sidecar

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

class LibraryTypeIndex {
    private val typesByShortName = ConcurrentHashMap<String, List<String>>()

    fun rebuildFromClasspath(classpath: List<String>) {
        val discovered = linkedMapOf<String, MutableSet<String>>()

        for (entry in classpath) {
            val file = File(entry)
            if (!file.isFile || file.extension.lowercase() != "jar") continue

            try {
                JarFile(file).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val jarEntry = entries.nextElement()
                        val fqn = classEntryToFqn(jarEntry.name) ?: continue
                        val shortName = fqn.substringAfterLast('.')
                        discovered.getOrPut(shortName) { linkedSetOf() }.add(fqn)
                    }
                }
            } catch (_: Exception) {
                // Ignore unreadable jars; import assist is best-effort.
            }
        }

        typesByShortName.clear()
        for ((shortName, fqns) in discovered) {
            typesByShortName[shortName] = fqns.sorted()
        }
    }

    fun findByShortName(shortName: String): List<String> =
        typesByShortName[shortName] ?: emptyList()

    /**
     * Searches for types whose FQN starts with the given prefix.
     * Used for import statement completions where the user is typing a qualified path.
     */
    fun searchByFqnPrefix(fqnPrefix: String, limit: Int = 100): List<IndexedLibraryType> {
        val normalizedPrefix = fqnPrefix.lowercase()
        return typesByShortName.values.asSequence()
            .flatten()
            .filter { it.lowercase().startsWith(normalizedPrefix) }
            .sorted()
            .take(limit)
            .map { fqn ->
                IndexedLibraryType(
                    shortName = fqn.substringAfterLast('.'),
                    fqn = fqn,
                )
            }
            .toList()
    }

    fun search(query: String, limit: Int = 100): List<IndexedLibraryType> {
        val all = typesByShortName.entries.asSequence()
            .flatMap { (shortName, fqns) ->
                fqns.asSequence().map { fqn ->
                    IndexedLibraryType(shortName = shortName, fqn = fqn)
                }
            }

        val filtered = if (query.isEmpty()) {
            all
        } else {
            val lowerQuery = query.lowercase()
            all.filter { it.shortName.lowercase().contains(lowerQuery) }
        }

        return filtered.sortedBy { it.fqn }.take(limit).toList()
    }

    private fun classEntryToFqn(entryName: String): String? {
        if (!entryName.endsWith(".class")) return null
        if (entryName.startsWith("META-INF/")) return null

        val className = entryName.removeSuffix(".class")
        if (className.endsWith("/package-info")) return null
        if (className == "module-info") return null

        val relativeName = className.substringAfterLast('/')
        if ('$' in relativeName) return null

        val fqn = className.replace('/', '.')
        if (fqn.isBlank()) return null

        val shortName = fqn.substringAfterLast('.')
        if (shortName.isEmpty() || !shortName.first().isJavaIdentifierStart()) return null
        if (!shortName.drop(1).all { it.isJavaIdentifierPart() }) return null

        return fqn
    }
}

data class IndexedLibraryType(
    val shortName: String,
    val fqn: String,
)
