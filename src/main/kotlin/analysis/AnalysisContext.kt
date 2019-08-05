package analysis

import fixpoint.*
import fixpoint.predicate.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.toKotlinType

class AnalysisContext {


    private var bindId = 0
    private var varId = 0
    private var constraintId = 0
    private var wfId = 0

    val binds = hashMapOf<IrElement, Bind>()
    val constraints = mutableListOf<Constraint>()
    val wfConstraints = mutableListOf<WFConstraint>()


    fun generateUniqueName() = "var_${varId++}"

    fun createVariable(name: String, type: IrType? = null) = when{
        type != null && type.isBoolean() -> PredicateTerm("Prop", VariableTerm(name))
        else -> VariableTerm(name)
    }

    fun createEquality(lhs: Term, rhs: Term) = when{
        lhs is PredicateTerm || rhs is PredicateTerm -> BinaryTerm(lhs, BinaryOperation.PREDICATE_EQUAL, rhs)
        else -> BinaryTerm(lhs, BinaryOperation.EQUAL, rhs)
    }

    fun createWf(environment: Environment, reft: Predicate) = WFConstraint(environment, reft, wfId++)

    fun createConstraint(environment: Environment, lhs: Predicate, rhs: Predicate) = Constraint(environment, lhs, rhs, constraintId++)

    fun createPredicate(name: String, type: IrType, terms: List<Term>) = Predicate(name, type.toFixpointType(), terms)

    fun createBind(name: String, type: IrType, term: Term) = createBind(name, type, listOf(term))

    fun createBind(name: String, type: IrType, terms: List<Term>): Bind {
        val predicate = Predicate(name, type.toFixpointType(), terms)
        return Bind(bindId++, name, predicate)
    }

    private fun IrType.toFixpointType() = when {
        isBoolean() -> Type.NamedType("bool")
        isString() ->  Type.NamedType("Str")
        else -> Type.NamedType("${toKotlinType()}")
    }

    override fun toString() = (binds.values + constraints + wfConstraints).joinToString("\n") { it.print() }
}



