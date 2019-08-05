import org.jetbrains.research.liquidtype.LqT

object Test2 {

    fun fix(@LqT("it > 0") x: Int) = x

    class IntBox(initial: Int) {
        var value = initial
        fun increment() {
            value++
        }
    }

    fun test() {
        val a = IntBox(-7)
        while (a.value < 0) {
            a.increment()
        }
        a.increment()
        fix(a.value)
    }

}
