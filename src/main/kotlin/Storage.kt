import com.google.common.base.Objects
import com.google.common.collect.ImmutableMap
import com.intellij.psi.PsiElement
import com.intellij.util.reverse
import org.jetbrains.kotlin.js.backend.ast.metadata.HasMetadata
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.SlicedMapImpl
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList


object UIDGenerator {
    private val generator = AtomicInteger(0)
    val id: Int
        get() = generator.incrementAndGet()
}

class CallExpressionLiquidType(
        expression: KtExpression,
        type: KexType,
        variable: Term,
        val parameters: List<LiquidType>,
        val arguments: List<LiquidType>
) : LiquidType(expression, type, variable, (parameters + arguments).toMutableList()) {

    override var predicate: Predicate? = null
        set(_) = throw IllegalAccessException("Try to set predicate on call expression Liquid Type")

    val predicates = arrayListOf<Predicate>()

    override val hasConstraints: Boolean
        get() = predicates.isNotEmpty()

    override fun finalConstraint() = when {
        predicates.isEmpty() -> PredicateFactory.getBool(TermFactory.getTrue())
        predicates.size == 1 -> predicates[0]
        else -> throw IllegalStateException("Try to get final constraints for call expression")
    }

    override fun getPredicate(): List<Predicate> = predicates

}

open class LiquidType(
        val expression: KtExpression,
        val type: KexType,
        val variable: Term,
        val dependsOn: MutableList<LiquidType>
) {

    open var predicate: Predicate? = null

    open val hasConstraints: Boolean
        get() = predicate != null


    open fun getPredicate(): List<Predicate> = if (predicate != null) listOf(predicate!!) else emptyList()


    open fun finalConstraint() = predicate ?: PredicateFactory.getBool(TermFactory.getTrue())


    fun collectTypeDependencies(): List<LiquidType> {
        val result = mutableSetOf<LiquidType>()
        val toVisit = LinkedList<LiquidType>()
        toVisit.add(this)
        while (toVisit.isNotEmpty()) {
            val current = toVisit.pop()
            if (current in result) continue
            result.add(current)
            toVisit.addAll(current.dependsOn)
        }
        return result.toList()
    }

    fun collectPredicates(includeSelf: Boolean): List<Predicate> =
            collectTypeDependencies()
                    .filter { includeSelf || it != this }
                    .flatMap { it.getPredicate() }


    companion object {
        fun create(expression: KtExpression, type: KotlinType) = LiquidType(
                expression,
                type.toKexType(),
                TermFactory.getValue(type.toKexType(), "${UIDGenerator.id}").apply {
                    additionalInfo = ": ${expression.text}"
                },
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
