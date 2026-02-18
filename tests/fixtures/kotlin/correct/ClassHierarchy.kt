// FIXTURE: correct/ClassHierarchy.kt
// EXPECT: no-diagnostics

package fixtures.correct

interface Drawable {
    fun draw(): String
    fun area(): Double
}

interface Resizable {
    fun resize(factor: Double)
}

abstract class Shape(val name: String) : Drawable {
    abstract override fun area(): Double

    override fun draw(): String = "Drawing $name"

    open fun describe(): String = "$name with area ${area()}"
}

class Circle(val radius: Double) : Shape("Circle"), Resizable {
    override fun area(): Double = Math.PI * radius * radius

    override fun resize(factor: Double) {
        // Circle is immutable, would return new instance in real code
    }

    override fun describe(): String = "Circle(r=$radius)"
}

class Rectangle(val width: Double, val height: Double) : Shape("Rectangle") {
    override fun area(): Double = width * height

    companion object {
        fun square(side: Double): Rectangle = Rectangle(side, side)
    }
}

object ShapeFactory {
    fun createCircle(radius: Double): Circle = Circle(radius)
    fun createSquare(side: Double): Rectangle = Rectangle.square(side)
}

fun demonstrateHierarchy() {
    val shapes: List<Shape> = listOf(
        Circle(5.0),
        Rectangle(3.0, 4.0),
        Rectangle.square(7.0),
        ShapeFactory.createCircle(2.5)
    )

    for (shape in shapes) {
        println(shape.draw())
        println(shape.describe())
    }
}
