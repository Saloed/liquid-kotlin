import org.jetbrains.liquidtype.LqT

const val trueConst = "true"

fun max(@LqT(trueConst) x: Int, @LqT("it > 100") y: Int) = if (x > y) x else y

fun fuck(i: Int, @LqT("it >= 100") c: Int) = max(i, c + 1)

fun sum(k: Int): Int = if (k < 0) 0 else sum(k - 1) + k

fun loopMax(a: IntArray, i: Int, @LqT("it > 200") c: Int, n: Int): Int = if (i < n) loopMax(a, i + 1, max(sub(a, i), c), n) else c

fun foldMax(@LqT("true") a: IntArray, n: Int, b: Int) = loopMax(a, 0, b, n)

fun sub(a: IntArray, i: Int) = a[i]

fun arraymax(a: IntArray) = foldMax(a, a.size, 0)
