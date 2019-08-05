import java.util.ArrayList
import org.jetbrains.research.liquidtype.LqT

//val top = 1

object TestJava {
//
//    val outer = 2
//
//    fun foo(param: Int) {
//        val t = top
//        val out = outer
//        val p = param
//    }

    fun huy(){
        var x = 1
        x = x + 1
        x += 2
        val y = x
    }

    fun foo(): Boolean {
        val x = ArrayList<Int>()
        x.add("0")
        x.add("1")
        x.clear()
//        val a = x.isEmpty()
//        return a
        if (x.isEmpty())
            return true
        else
            return false
    }

    fun check(@LqT("it") b: Boolean) = b

    fun boo() = check(foo())

}
