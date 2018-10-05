import org.jetbrains.liquidtype.LqT

const val trueConst = "true"

fun max(@LqT(trueConst) x: Int, @LqT("false") y: Int) = if (x > y) x else y

fun sum(k: Int): Int = if (k < 0) 0 else sum(k - 1) + k

fun foldn(n: Int, b: Int, f: (Int, Int) -> Int): Int {
    fun loop(i: Int, c: Int): Int = if (i < n) loop(i + 1, f(i, c)) else c
    return loop(0, b)
}

fun sub(a: IntArray, i: Int) = a[i]

fun arraymax(a: IntArray): Int {
    fun am(i: Int, m: Int) = max(sub(a, i), m)
    return foldn(a.size, 0, ::am)
}

