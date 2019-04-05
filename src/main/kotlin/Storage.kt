import com.intellij.psi.PsiElement
import org.apache.commons.lang.StringEscapeUtils
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.ChainState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kex.state.term.ValueTerm
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.HashMap
import kotlin.reflect.jvm.isAccessible


object UIDGenerator {
    private val generator = AtomicInteger(0)
    val id: Int
        get() = generator.incrementAndGet()
}

class CallExpressionLiquidType(
        expression: PsiElement,
        type: KexType,
        variable: Term,
        dispatchArgument: LiquidType?,
        extensionArgument: LiquidType?,
        arguments: Map<String, LiquidType>,
        val function: FunctionLiquidType
) : FunctionLiquidTypeBase(
        expression, type, variable, listOf(function), dispatchArgument, extensionArgument, arguments
)

class FunctionLiquidType(
        expression: PsiElement,
        type: KexType,
        variable: Term,
        dispatchArgument: LiquidType?,
        extensionArgument: LiquidType?,
        parameters: Map<String, LiquidType>,
        val returnValue: LiquidType?
) : FunctionLiquidTypeBase(
        expression, type, variable, emptyListIfNull(returnValue), dispatchArgument, extensionArgument, parameters
)

abstract class FunctionLiquidTypeBase(
        expression: PsiElement,
        type: KexType,
        variable: Term,
        additionalDependencies: List<LiquidType>,
        val dispatchArgument: LiquidType?,
        val extensionArgument: LiquidType?,
        val arguments: Map<String, LiquidType>
) : LiquidType(
        expression, type, variable,
        (additionalDependencies +
                emptyListIfNull(dispatchArgument) +
                emptyListIfNull(extensionArgument) +
                arguments.values
                ).toMutableList()
) {
    val allArguments = arguments.values + emptyListIfNull(dispatchArgument) + emptyListIfNull(extensionArgument)
}


class ConstantLiquidType(
        val ktType: KotlinType,
        expression: PsiElement,
        type: KexType,
        variable: Term
) : LiquidType(expression, type, variable, mutableListOf()) {
    companion object {
        fun create(expression: PsiElement, type: KotlinType): LiquidType {
            val kexType = type.toKexType()
            val variable = createVariable(kexType, expression)
            return ConstantLiquidType(type, expression, kexType, variable)
        }
    }
}

class BlockLiquidType(
        expression: PsiElement,
        type: KexType,
        variable: Term,
        dependsOn: MutableList<LiquidType>
) : LiquidType(expression, type, variable, dependsOn)

class ReturnLiquidType(
        expression: PsiElement,
        type: KexType,
        variable: Term,
        dependsOn: MutableList<LiquidType>,
        val conditionPath: PredicateState,
        val function: KtCallableDeclaration
) : LiquidType(expression, type, variable, dependsOn)


open class LiquidType(
        val expression: PsiElement,
        val type: KexType,
        val variable: Term,
        val dependsOn: MutableList<LiquidType>
) {

    private var predicate: PredicateState? = null

    val hasConstraints: Boolean
        get() = predicate != null


    fun addEmptyPredicate() {
        if (this.predicate != null)
            return reportError("Try to add predicate to: [$this] | EMPTY ")
        predicate = BasicState()
    }

    fun addPredicate(predicate: Predicate) {
        if (this.predicate != null)
            return reportError("Try to add predicate to: [$this] | $predicate ")
        this.predicate = BasicState(listOf(predicate))
    }

    fun addPredicate(predicate: PredicateState) {
        if (this.predicate != null)
            return reportError("Try to add predicate STATE to: [$this] | $predicate ")
        this.predicate = predicate
    }

    fun getPredicate(): PredicateState = if (predicate != null) predicate!! else BasicState()

    fun getRawPredicate() = predicate

    fun withVersions() = VersionedLiquidType.makeVersion(this)


    fun collectDescendants(withSelf: Boolean = false, predicate: (LiquidType) -> Boolean): List<LiquidType> {
        val result = arrayListOf<LiquidType>()
        val visited = hashSetOf<LiquidType>()
        val toVisit = LinkedList<LiquidType>()
        if (withSelf) {
            toVisit.add(this)
        } else {
            visited.add(this)
            toVisit.addAll(dependsOn)
        }
        while (toVisit.isNotEmpty()) {
            val current = toVisit.pop()
            if (current in visited) continue
            visited.add(current)
            if (predicate(current)) result.add(current)
            toVisit.addAll(current.dependsOn)
        }
        return result
    }

    companion object {
        fun create(expression: KtExpression, type: KotlinType) = LiquidType(
                expression,
                type.toKexType(),
                createVariable(type.toKexType(), expression),
                mutableListOf()
        )

        fun createWithoutExpression(element: PsiElement, text: String, type: KexType): LiquidType {
            val factory = KtPsiFactory(element, false)
            val txt = StringEscapeUtils.escapeJava(text);
            val expr = factory.createStringTemplate(txt)
            return LiquidType(
                    expr,
                    type,
                    createVariable(type, expr),
                    mutableListOf()
            )
        }

        fun createVariable(type: KexType) = TermFactory.getValue(type, "${UIDGenerator.id}")
        fun createVariable(type: KexType, expression: PsiElement) = createVariable(type).apply {
            debugInfo = ": ${expression.text.replace('\n', ' ').trim().replace(Regex("\\s+"), " ")}"
        }

    }

    override fun hashCode() = Objects.hash(expression, type, variable)

    override fun equals(other: Any?) = this === other
            || other is LiquidType
            && expression == other.expression && type == other.type && variable == other.variable

    override fun toString() = "${variable.name} $type ${expression.text} | $predicate"
}

class VersionedFunctionLiquidType(
        lqt: FunctionLiquidType,
        version: Int,
        depends: MutableList<VersionedLiquidType>
) : VersionedFunctionLiquidTypeBase(lqt, version, depends) {

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
) : VersionedFunctionLiquidTypeBase(lqt, version, depends) {

    var internalFunction: VersionedFunctionLiquidType? = null
        set(value) {
            field = value ?: throw IllegalStateException("Try to set null field")
            depends.add(value)
        }

    val function: VersionedFunctionLiquidType
        get() = internalFunction ?: throw IllegalStateException("Try to get unset field")

}


abstract class VersionedFunctionLiquidTypeBase(
        lqt: FunctionLiquidTypeBase,
        version: Int,
        depends: MutableList<VersionedLiquidType>
) : VersionedLiquidType(lqt, version, depends) {

    var arguments: List<VersionedLiquidType> = emptyList()
        set(value) {
            field = value
            depends.addAll(value)
        }

    var dispatchArgument: VersionedLiquidType? = null
        set(value) {
            if (value != null) {
                field = value
                depends.add(value)
            }
        }

    var extensionArgument: VersionedLiquidType? = null
        set(value) {
            if (value != null) {
                field = value
                depends.add(value)
            }
        }
}

open class VersionedLiquidType(val lqt: LiquidType, val version: Int, val depends: MutableList<VersionedLiquidType>) {
    override fun toString() = "{V $version $lqt}"

    override fun hashCode() = Objects.hash(lqt, version)

    override fun equals(other: Any?) = this === other
            || other is VersionedLiquidType
            && lqt == other.lqt && version == other.version


    fun getPredicate(): PredicateState {
        val renameMap = collectRenamingMap()
        val renamer = RenameVariables(renameMap)
        return lqt.getPredicate().map(renamer::transform)
    }

    fun collectRenamingMap(): Map<String, Int> {
        val versions = depends.map { it.version }.toSet() + listOf(version)
        val dependencies = dependencies().withVersions(versions)

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

    sealed class Dependency(val lqt: VersionedLiquidType) {
        class Cycle(lqt: VersionedLiquidType) : Dependency(lqt)
        class Normal(lqt: VersionedLiquidType, val depends: List<Dependency>) : Dependency(lqt)

        fun predicateState(): PredicateState = when (this) {
            is Cycle -> BasicState()
            is Normal -> {
                val deps = depends.map { it.predicateState() }.chain().simplify()
                val myPs = lqt.getPredicate()
                if (deps.isEmpty) myPs
                else ChainState(deps, myPs)
            }
        }

        fun withVersions(versions: Set<Int>): List<VersionedLiquidType> = when (this) {
            is Cycle -> emptyList()
            is Normal -> {
                val deps = depends.flatMap { it.withVersions(versions) }
                if (lqt.version in versions) deps + listOf(lqt)
                else deps
            }
        }
    }

    private fun dependencies(visited: MutableSet<VersionedLiquidType>): Dependency {
        if (this in visited) return Dependency.Cycle(this)
        visited.add(this)
        val deps = depends.map { it.dependencies(visited) }
        return Dependency.Normal(this, deps)
    }

    private fun dependencies() = dependencies(mutableSetOf())


    fun collectPredicates(includeSelf: Boolean): PredicateState {
        val dependency = dependencies()
        if (includeSelf) return dependency.predicateState()
        if (dependency is Dependency.Cycle) return BasicState()
        return dependency.cast<Dependency.Normal>()
                .depends.map { it.predicateState() }
                .chain()
    }


    companion object {
        fun makeVersion(lqt: LiquidType) = LiquidTypeVersioner().makeVersion(lqt)
        fun create(lqt: LiquidType, version: Int) = when (lqt) {
            is CallExpressionLiquidType -> VersionedCallLiquidType(lqt, version, mutableListOf())
            is FunctionLiquidType -> VersionedFunctionLiquidType(lqt, version, mutableListOf())
            else -> VersionedLiquidType(lqt, version, mutableListOf())
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
                versioned.arguments = lqt.arguments.map { makeVersion(it.value, version) }
                versioned.internalFunction = makeVersion(lqt.function, newVersion).cast()
                versioned.dispatchArgument = lqt.dispatchArgument?.let { makeVersion(it, version) }
                versioned.extensionArgument = lqt.extensionArgument?.let { makeVersion(it, version) }
                val otherDependencies = lqt.dependsOn
                        .filterNot { it in lqt.allArguments }
                        .filterNot { it == lqt.function }
                versioned.depends.addAll(otherDependencies.map { makeVersion(it, version) })
            }
            is FunctionLiquidType -> {
                val versioned = new.cast<VersionedFunctionLiquidType>()
                versioned.arguments = lqt.arguments.map { makeVersion(it.value, version) }
                versioned.dispatchArgument = lqt.dispatchArgument?.let { makeVersion(it, version) }
                versioned.extensionArgument = lqt.extensionArgument?.let { makeVersion(it, version) }
                versioned.returnValue = lqt.returnValue?.let { makeVersion(it, version) }
                val otherDependencies = lqt.dependsOn
                        .filterNot { it in lqt.allArguments }
                        .filterNot { it == lqt.returnValue }
                versioned.depends.addAll(otherDependencies.map { makeVersion(it, version) })
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
            ?: throw IllegalStateException("Type for $element expected: ${element.text}")

}
