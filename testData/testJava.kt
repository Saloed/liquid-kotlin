import java.util.ArrayList
import org.jetbrains.liquidtype.LqT

//val top = 1

//object TestJava {
//
//    val outer = 2
//
//    fun foo(param: Int) {
//        val t = top
//        val out = outer
//        val p = param
//    }

//    fun foo(): Boolean {
//        val x = ArrayList<Int>()
//        x.add("0")
//        x.add("1")
//        x.clear()
////        val a = x.isEmpty()
////        return a
//        if (x.isEmpty())
//            return true
//        else
//            return false
//    }
//
//    fun boo(): Boolean {
//        val x = ArrayList<Int>()
//        x.add("0")
//        x.add("1")
//        return x.size == 2
//    }

    fun test3fn(): Boolean {
        val a = listOf("1", "2", "3")
        return a.size == 3
    }

    fun check(@LqT("it") b: Boolean) = b

//    fun test1() = check(foo())
//    fun test2() = check(boo())
    fun test3() = check(test3fn())


//}
