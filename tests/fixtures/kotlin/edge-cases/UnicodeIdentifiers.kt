// FIXTURE: edge-cases/UnicodeIdentifiers.kt
// EXPECT: no-diagnostics

package fixtures.edgecases

val наименование = "test"
val 名前 = "name"
val `spaces allowed` = "backtick identifiers"

fun grüßen(name: String): String = "Hallo, $name!"

fun main() {
    println(наименование)
    println(名前)
    println(`spaces allowed`)
    println(grüßen("Welt"))
}
