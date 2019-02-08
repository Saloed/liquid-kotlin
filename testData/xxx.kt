import org.jetbrains.liquidtype.LqT

fun fix(@LqT("it > 0") x: Int) = x

fun foo() = fix(Int.MAX_VALUE.minus(1))
