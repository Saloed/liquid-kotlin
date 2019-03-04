import com.intellij.psi.PsiElement
import org.apache.commons.lang.StringEscapeUtils
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.types.KotlinType
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
        expression: PsiElement,
        type: KexType,
        variable: Term,
        dispatchArgument: LiquidType?,
        extensionArgument: LiquidType?,
        arguments: Map<String, LiquidType>,
        val function: FunctionLiquidType
) : FunctionLiquidTypeBase(
        expression, type, variable, listOf(function), dispatchArgument, extensionArgument, arguments
) {

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
) : LiquidType(expression, type, variable, arrayListOf()) {
    companion object {
        fun create(expression: PsiElement, type: KotlinType): LiquidType {
            val kexType = type.toKexType()
            val variable = TermFactory.getValue(kexType, "${UIDGenerator.id}").apply {
                additionalInfo = ": ${expression.text}"
            }
            return ConstantLiquidType(type, expression, kexType, variable)
        }
    }
}


open class LiquidType(
        val expression: PsiElement,
        val type: KexType,
        val variable: Term,
        val dependsOn: MutableList<LiquidType>
) {

    open var predicate: Predicate? = null

    open val hasConstraints: Boolean
        get() = predicate != null


    open fun getPredicate(): List<Predicate> = if (predicate != null) listOf(predicate!!) else emptyList()


    open fun finalConstraint() = predicate ?: PredicateFactory.getBool(TermFactory.getTrue())

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

        fun createWithoutExpression(element: PsiElement, text: String, type: KexType): LiquidType {
            val factory = KtPsiFactory(element, false)
            val txt = StringEscapeUtils.escapeJava(text);
            val expr = factory.createStringTemplate(txt)
            return LiquidType(
                    expr,
                    type,
                    TermFactory.getValue(type, "${UIDGenerator.id}").apply {
                        additionalInfo = ": ${expr.text}"
                    },
                    arrayListOf()
            )
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
                versioned.arguments = lqt.arguments.map { makeVersion(it.value, version) }
                versioned.dispatchArgument = lqt.dispatchArgument?.let { makeVersion(it, version) }
                versioned.extensionArgument = lqt.extensionArgument?.let { makeVersion(it, version) }
                versioned.internalFunction = makeVersion(lqt.function, newVersion).cast()
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
