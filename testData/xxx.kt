import org.jetbrains.research.liquidtype.LqT

fun fix(@LqT("it > 0") x: Int) = x

fun aaa() = fix(boo(7))

//
//fun foo() = fix(Int.MAX_VALUE.minus(1))

fun boo(a: Int) = A(-(a - 1)).foo(a)

class A(val x: Int) {
    fun foo(y: Int) = x + y
}

//object A {
//    fun foo() = 3
//}
