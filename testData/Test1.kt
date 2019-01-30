import org.jetbrains.liquidtype.LqT

open class Vector(val elements: List<Int>)
data class Point(val x: Int, @LqT("it > 0") val  y: Int) : Vector(listOf(x, y))

fun square(a: Int) = if (a < 46340) a * a else 2147483647

// && it.elements.size == 2

fun checked(@LqT("it.x >= 0 && it.y >= 0") p: Point) = p.x + p.y

fun fooP(x: Int, y: Int) = checked(Point(x, y))

fun booP(x: Int, y: Int) = checked(Point(square(x), y))
