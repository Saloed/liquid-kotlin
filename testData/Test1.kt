import org.jetbrains.liquidtype.LqT

object Test1 {

    open class Vector(val elements: List<Int>)
    data class Point(val x: Int, @LqT("it > 0") val y: Int) : Vector(listOf(x, y))

    fun square(a: Int) = if (0 <= a && a <= 46339) a * a else 2147483645

    fun abs(a: Int) = if (a >= 0) a else -a

    // && it.elements.size == 2

    fun inRange(it: Int) = it < Int.MAX_VALUE && it > Int.MIN_VALUE

    //fun inRange(it: Int) = it < 2147483645 && it > -2147483645

    fun checked(@LqT("it.x >= 0 && it.y >= 0") p: Point) = p.x + p.y

    fun fooP(@LqT("inRange(it)") x: Int, @LqT("inRange(it)") y: Int) = checked(Point(x, y))

    fun booP(@LqT("inRange(it)") x: Int, @LqT("inRange(it)") y: Int) = checked(Point(abs(x), y))

}
