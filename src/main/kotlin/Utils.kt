import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.staticProperties
import kotlin.reflect.jvm.isAccessible

public inline fun <X, T, R> Iterable<Pair<X, T>>.mapSecond(transform: (T) -> R) =
        this.map { it.first to transform(it.second) }

public inline fun <X, T, R> Iterable<Pair<T, X>>.mapFirst(transform: (T) -> R) =
        this.map { transform(it.first) to it.second }

public inline fun <T, R> Iterable<T>.zipMap(transform: (T) -> R) =
        this.map { it to transform(it) }

public inline fun <T, X> Iterable<Pair<T?, X?>>.pairNotNull() =
        this.filter { it.first != null && it.second != null }
                .map { it.first!! to it.second!! }

fun reportError(message: String) = System.err.println(message)

private fun <T : Any> callFunction(func: KFunction<*>, receiver: T) = try {
    when (func.parameters.size) {
        0 -> func.call()
        1 -> func.call(receiver)
        else -> null
    }
} catch (ex: Exception) {
    println("Unexpected getter call: $ex")
    null
}

fun <T : Any> KClass<out T>.inspectProperties(receiver: T): Map<KCallable<*>, Any?> {
    members.forEach {
        it.isAccessible = true
    }
    val properties = memberProperties.map { it as KProperty1<T, Any?> }.map { it to it.get(receiver) }
    val getters = memberFunctions.filter { it.name.startsWith("get") ||  it.name.startsWith("is") }

    val zeroGetters = getters.filter { it.parameters.isEmpty() }.map { it to it.call() }
    val singleGetters = getters.filter { it.parameters.size == 1 }.mapNotNull {
        try {
            it to it.call(receiver)
        } catch (ex: Exception) {
            println("Unexpected getter call: $ex")
            null
        }
    }

    return (properties + zeroGetters + singleGetters).map { it as Pair<KCallable<*>, Any?> }.toMap()
}

fun BindingContext.inspectBindingContext(element: PsiElement) =
        BindingContext::class.staticProperties
                .map { it.get() }
                .filterIsInstance<WritableSlice<PsiElement, *>>()
                .mapNotNull { slice ->
                    try {
                        get(slice, element)?.let { slice to it }
                    } catch (ex: Exception) {
                        null
                    }
                }


