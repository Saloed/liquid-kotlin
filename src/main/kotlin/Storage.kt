import com.google.common.base.Objects
import com.google.common.collect.ImmutableMap
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.diagnostics.SimpleDiagnostics
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.SlicedMapImpl
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kex.state.term.ValueTerm
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.HashMap


object UIDGenerator {
    private val generator = AtomicInteger(0)
    val id: Int
        get() = generator.incrementAndGet()
}


class CallExpressionLiquidType(
        expression: KtExpression,
        type: KexType,
        variable: Term,
        val arguments: List<LiquidType>,
        val function: FunctionLiquidType
) : LiquidType(expression, type, variable, (arguments + listOf(function)).toMutableList()) {

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

class FunctionLiquidType(
        expression: KtExpression,
        type: KexType,
        variable: Term,
        val parameters: List<LiquidType>,
        val returnValue: LiquidType?
) : LiquidType(expression, type, variable, (parameters + if (returnValue != null) listOf(returnValue) else emptyList()).toMutableList())

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


    fun withVersions() = VersionedLiquidType.makeVersion(this)

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

class VersionedFunctionLiquidType(
        lqt: FunctionLiquidType,
        version: Int,
        depends: MutableList<VersionedLiquidType>
) : VersionedLiquidType(lqt, version, depends) {
    var parameters: List<VersionedLiquidType> = emptyList()
        set(value) {
            field = value
            depends.addAll(value)
        }
    var returnValue: VersionedLiquidType? = null
        set(value) {
            if (value != null) {
                field = value
                depends.add(value)
            }
        }
}


class VersionedCallLiquidType(
        lqt: CallExpressionLiquidType,
        version: Int,
        depends: MutableList<VersionedLiquidType>
) : VersionedLiquidType(lqt, version, depends) {
    var arguments: List<VersionedLiquidType> = emptyList()
        set(value) {
            field = value
            depends.addAll(value)
        }

    var internalFunction: VersionedFunctionLiquidType? = null
        set(value) {
            field = value ?: throw IllegalStateException("Try to set null field")
            depends.add(value)
        }

    val function: VersionedFunctionLiquidType
        get() = internalFunction ?: throw IllegalStateException("Try to get unset field")


}

open class VersionedLiquidType(val lqt: LiquidType, val version: Int, val depends: MutableList<VersionedLiquidType>) {
    override fun toString() = "{V $version $lqt}"

    override fun hashCode() = Objects.hashCode(lqt, version)

    override fun equals(other: Any?) = this === other
            || other is VersionedLiquidType
            && lqt == other.lqt && version == other.version


    fun getPredicate(): List<Predicate> {
        val renameMap = collectRenamingMap()
        val renamer = RenameVariables(renameMap)
        return lqt.getPredicate().map(renamer::transform)
    }


    fun finalConstraint(): Predicate {
        val renameMap = collectRenamingMap()
        val renamer = RenameVariables(renameMap)
        return renamer.transform(lqt.finalConstraint())
    }

    private fun collectRenamingMap(): Map<String, Int> {
        val versions = depends.map { it.version }.toSet() + listOf(version)
        val dependencies = collectTypeDependencies().filter {
            it.version in versions
        }
        val depsVars = dependencies
                .flatMap { vlqt ->
                    vlqt.lqt.variable
                            .collectDescendantsOfType<ValueTerm> { it is ValueTerm }
                            .map { it.name to vlqt.version }
                }
        val itVars = lqt.variable
                .collectDescendantsOfType<ValueTerm> { it is ValueTerm }
                .map { it.name to version }
        return (depsVars + itVars).toMap()

    }

    private fun collectTypeDependencies(): List<VersionedLiquidType> {
        val result = mutableSetOf<VersionedLiquidType>()
        val toVisit = LinkedList<VersionedLiquidType>()
        toVisit.add(this)
        while (toVisit.isNotEmpty()) {
            val current = toVisit.pop()
            if (current in result) continue
            result.add(current)
            toVisit.addAll(current.depends)
        }
        return result.toList()
    }


    fun collectPredicates(includeSelf: Boolean): List<Predicate> =
            collectTypeDependencies()
                    .filter { includeSelf || it != this }
                    .flatMap { it.getPredicate() }


    companion object {
        fun makeVersion(lqt: LiquidType) = LiquidTypeVersioner().makeVersion(lqt)
        fun create(lqt: LiquidType, version: Int) = when (lqt) {
            is CallExpressionLiquidType -> VersionedCallLiquidType(lqt, version, arrayListOf())
            is FunctionLiquidType -> VersionedFunctionLiquidType(lqt, version, arrayListOf())
            else -> VersionedLiquidType(lqt, version, arrayListOf())
        }
    }
}

class LiquidTypeVersioner {
    val typeVersions = HashMap<Pair<LiquidType, Int>, VersionedLiquidType>()
    fun makeVersion(lqt: LiquidType, version: Int = 0): VersionedLiquidType {
        val current = typeVersions[lqt to version]
        if (current != null) return current
        val new = VersionedLiquidType.create(lqt, version)
        typeVersions[lqt to version] = new

        when (lqt) {
            is CallExpressionLiquidType -> {
                val versioned = new.cast<VersionedCallLiquidType>()
                val newVersion = UIDGenerator.id
                versioned.arguments = lqt.arguments.map { makeVersion(it, version) }
                versioned.internalFunction = makeVersion(lqt.function, newVersion).cast()
                val otherDependencies = lqt.dependsOn
                        .filterNot { it in lqt.arguments }
                        .filterNot { it == lqt.function }
                versioned.depends.addAll(otherDependencies.map { makeVersion(it, version) })
            }
            is FunctionLiquidType -> {
                val versioned = new.cast<VersionedFunctionLiquidType>()
                versioned.parameters = lqt.parameters.map { makeVersion(it, version) }
                versioned.returnValue = lqt.returnValue?.let { makeVersion(it, version) }
            }
            else -> {
                new.depends.addAll(lqt.dependsOn.map { makeVersion(it, version) })
            }
        }

        return new
    }

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