package analysis

import fixpoint.*
import fixpoint.predicate.*
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.nj2k.replace

class AnalysisContext(val functionConstraints: Map<FunctionDescriptor, FunctionConstraint>) {


    private var bindId = 0
    private var varId = 0
    private var constraintId = 0
    private var wfId = 0

    val binds = hashMapOf<Int, Bind>()
    private val elementBinds = hashMapOf<IrElement, Bind>()

    operator fun set(element: IrElement, bind: Bind) {
        elementBinds[element] = bind
        binds[bind.id] = bind
    }

    operator fun get(element: IrElement) = elementBinds[element]

    val constraints = mutableListOf<Constraint>()
    val wfConstraints = mutableListOf<WFConstraint>()


    fun generateUniqueName() = "var_${varId++}"

    fun createVariable(name: String, type: IrType? = null) = when {
        type != null && type.isBoolean() -> PredicateTerm("Prop", VariableTerm(name))
        else -> VariableTerm(name)
    }

    fun createEnvironmentForBinds(binds: List<Bind>, except: List<Bind> = emptyList()): Environment {
        val badIds = except.map { it.id }.toSet()
        val envIds = binds.map { it.id }.filterNot { it in badIds }.sortedBy { it }
        return Environment(envIds)
    }

    fun createEquality(lhs: Term, rhs: Term) = when {
        lhs is PredicateTerm || rhs is PredicateTerm -> BinaryTerm(lhs, BinaryOperation.PREDICATE_EQUAL, rhs)
        else -> BinaryTerm(lhs, BinaryOperation.EQUAL, rhs)
    }

    fun createWf(environment: Environment, reft: Predicate) = WFConstraint(environment, reft, wfId++)

    fun createConstraint(environment: Environment, lhs: Predicate, rhs: Predicate) = Constraint(environment, lhs, rhs, constraintId++)

    fun createPredicate(name: String, type: IrType, terms: List<Term>) = Predicate(name, type.toFixpointType(), terms)

    fun createBind(name: String, type: IrType, term: Term) = createBind(name, type, listOf(term))

    fun createBind(name: String, type: IrType, terms: List<Term>) = createBind(name, type.toFixpointType(), terms)

    fun createBind(name: String, type: Type, terms: List<Term>): Bind {
        val predicate = Predicate(name, type, terms)
        return Bind(bindId++, name, predicate)
    }


    private fun IrType.toFixpointType() = when {
        isBoolean() -> Type.NamedType("bool")
        isString() -> Type.NamedType("Str")
        isInt() -> Type.NamedType("int")
        else -> Type.NamedType("${toKotlinType()}")
    }

    override fun toString() = (binds.values + constraints + wfConstraints).joinToString("\n") { it.print() }
}



