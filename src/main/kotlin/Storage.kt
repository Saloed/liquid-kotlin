import com.google.common.collect.ImmutableMap
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.SlicedMapImpl
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import org.jetbrains.research.kex.ktype.KexBool
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode

typealias TypeConstraintMap<K, V> = HashMap<K, V>

data class LiquidTypeInfo(private val typeConstraints: TypeConstraintMap<PsiElement, MutableList<Term>>) {

    operator fun get(element: PsiElement) = typeConstraints[element]
    operator fun set(element: PsiElement, typeInfo: Term) {
        typeConstraints.getOrPut(element) { arrayListOf() } += typeInfo
    }

    fun <T : Transformer<T>> accept(t: Transformer<T>) = typeConstraints.toList()
            .mapSecond { it.map { t.transform(it).accept(t) } }
            .toLiquidTypeInfo()

    operator fun contains(element: PsiElement) = element in typeConstraints

    fun unsafeTypeConstraints() = typeConstraints

    override fun toString() = typeConstraints.map { "${it.key.text} -> ${it.value.map { it.deepToString() }}" }.joinToString("\n")

    companion object {
        fun empty() = LiquidTypeInfo(TypeConstraintMap())
    }

    fun Iterable<Pair<PsiElement, List<Term>>>.toLiquidTypeInfo(): LiquidTypeInfo {
        val result = TypeConstraintMap<PsiElement, MutableList<Term>>()
        for ((key, value) in this) {
            result[key] = value.toMutableList()
        }
        return LiquidTypeInfo(result)
    }

}

object LiquidTypeInfoStorage {
    private val liquidTypeInfo = LiquidTypeInfo.empty()
    val liquidTypeConstraints = LiquidTypeInfo.empty()
    val liquidTypeValues = TypeConstraintMap<Term, Term>()

    operator fun get(element: PsiElement) = liquidTypeInfo[element]
    operator fun set(element: PsiElement, typeInfo: Term) = liquidTypeInfo.set(element, typeInfo)
    operator fun contains(element: PsiElement) = liquidTypeInfo.contains(element)
    fun <T : Transformer<T>> accept(t: Transformer<T>) = liquidTypeInfo.accept(t)
    fun unsafeTypeInfo() = liquidTypeInfo

    fun getConstraints(element: PsiElement) = liquidTypeConstraints[element]
    fun addConstraint(element: PsiElement, typeInfo: Term) = liquidTypeConstraints.set(element, typeInfo)
}

fun Term.toPredicateState(): PredicateState = BasicState(listOf(PredicateFactory.getBool(this)))
fun List<Term>.toPredicateState(): PredicateState = BasicState(this.map { PredicateFactory.getBool(it) })
fun List<Term>.combineWithAnd() = when (size) {
    0 -> throw IllegalStateException("Empty constraint")
    1 -> first()
    2 -> TermFactory.getBinary(KexBool, BinaryOpcode.And(), this[0], this[1])
    else -> fold(TermFactory.getTrue() as Term) { acc: Term, localTerm: Term ->
        TermFactory.getBinary(KexBool, BinaryOpcode.And(), acc, localTerm)
    }
}


fun getTruePredicateState(): PredicateState = BasicState(listOf(PredicateFactory.getBool(TermFactory.getTrue())))

fun Map.Entry<PsiElement, StateBuilder>.text() = "$key ${key.text} -> ${value.current.toString().trim('\n')}"

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
