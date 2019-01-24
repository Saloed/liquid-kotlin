import com.google.common.base.Objects
import com.google.common.collect.ImmutableMap
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.SlicedMapImpl
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import java.util.concurrent.atomic.AtomicInteger


object UIDGenerator {
    private val generator = AtomicInteger(0)
    val id: Int
        get() = generator.incrementAndGet()
}

data class LiquidType(
        val expression: KtExpression,
        val type: KotlinType,
        val variable: Term,
        var predicate: Predicate?,
        val dependsOn: MutableList<LiquidType>
) {
    val hasConstraints: Boolean
        get() = predicate != null


    fun finalConstraint() = predicate ?: PredicateFactory.getBool(TermFactory.getTrue())

    companion object {
        fun create(expression: KtExpression, type: KotlinType) = LiquidType(
                expression,
                type,
                TermFactory.getValue(type.toKexType(), "${UIDGenerator.id}").apply {
                    additionalInfo = ": ${expression.text}"
                },
                null,
                arrayListOf()
        )
    }

    override fun hashCode() = Objects.hashCode(expression, type, variable)

    override fun equals(other: Any?) = this === other
            || other is LiquidType
            && expression == other.expression && type == other.type && variable == other.variable

    override fun toString() = "${variable.name} $type ${expression.text} | $predicate"
}

object NewLQTInfo {
    val typeInfo = HashMap<PsiElement, LiquidType>()
    fun getOrException(element: PsiElement) = typeInfo[element]
            ?: throw IllegalStateException("Type for $element expected")

}


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
