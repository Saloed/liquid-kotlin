import org.jetbrains.liquidtype.LqT

data class Point(val x: Int, val y: Int)

fun checked(@LqT("it.x >= 0 && it.y >= 0") p: Point) = p.x + p.y

fun isPositive(it: Int) = it >= 0 && it < Int.MAX_VALUE

fun correct(@LqT("isPositive(it)") x: Int, @LqT("isPositive(it)") y: Int) = checked(Point(x, y))

fun incorrect(@LqT("true") x: Int, y: Int) = checked(Point(x, y))
