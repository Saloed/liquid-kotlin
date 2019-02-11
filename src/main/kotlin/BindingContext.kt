import com.google.common.collect.ImmutableMap
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.diagnostics.SimpleDiagnostics
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.SlicedMapImpl
import org.jetbrains.kotlin.util.slicedMap.WritableSlice

class MyBindingContext(private val bindingContext: BindingContext) : BindingContext {
    override fun <K : Any?, V : Any?> getKeys(slice: WritableSlice<K, V>?) =
            bindingContext.getKeys(slice).union(localData.getKeys(slice))

    override fun getType(expression: KtExpression) = get(BindingContext.EXPRESSION_TYPE_INFO, expression)?.type

    override fun getDiagnostics() = bindingContext.diagnostics

    override fun addOwnDataTo(trace: BindingTrace, commitDiagnostics: Boolean) {
        bindingContext.addOwnDataTo(trace, commitDiagnostics)
    }

    override fun <K : Any?, V : Any?> getSliceContents(slice: ReadOnlySlice<K, V>) =
            ImmutableMap.copyOf(bindingContext.getSliceContents(slice) + localData.getSliceContents(slice))

    private val localData = SlicedMapImpl(true)

    override operator fun <K, V> get(slice: ReadOnlySlice<K, V>, key: K): V? = bindingContext[slice, key]
            ?: localData[slice, key]

    operator fun <K, V> set(slice: WritableSlice<K, V>, key: K, value: V) = localData.put(slice, key, value)

}

class MergedBindingContext(val contexts: List<BindingContext>) : BindingContext {
    override fun <K : Any?, V : Any?> getKeys(slice: WritableSlice<K, V>?): Collection<K> = contexts.flatMap { it.getKeys(slice) }
    override fun <K : Any?, V : Any?> getSliceContents(slice: ReadOnlySlice<K, V>): ImmutableMap<K, V> =
            ImmutableMap.copyOf(
                    contexts.flatMap { it.getSliceContents(slice).entries }
            )


    override fun getType(expression: KtExpression): KotlinType? = contexts
            .map { it.getType(expression) }
            .firstOrNull { it != null }

    override fun <K : Any?, V : Any?> get(slice: ReadOnlySlice<K, V>?, key: K): V? = contexts
            .map { it.get(slice, key) }
            .firstOrNull { it != null }

    override fun getDiagnostics(): Diagnostics = SimpleDiagnostics(contexts.flatMap { it.diagnostics.all() })

    override fun addOwnDataTo(trace: BindingTrace, commitDiagnostics: Boolean) = TODO()
}

fun BindingContext.mergeWith(other: BindingContext): BindingContext = MergedBindingContext(listOf(this, other))
