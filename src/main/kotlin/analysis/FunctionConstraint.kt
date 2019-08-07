package analysis

import fixpoint.predicate.Term
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.ir.declarations.IrValueParameter

abstract class FunctionConstraint(val parameterConstraints: ParameterConstraints) {
    abstract fun substitute(arguments: Map<ValueParameterDescriptor, Term>, returnValue: Term): List<Term>
}

class FunctionConstraintImpl(
        val function: FunctionDescriptor,
        val constraint: List<Term>,
        val arguments: Map<IrValueParameter, String>,
        val returnName: String,
        parameterConstraints: ParameterConstraints
) : FunctionConstraint(parameterConstraints) {

    override fun substitute(arguments: Map<ValueParameterDescriptor, Term>, returnValue: Term): List<Term> {
        val argumentSubstitution = this.arguments.map { (param, name) ->
            name to (arguments[param.descriptor.original] ?: throw IllegalArgumentException("Parameter $param is not provided"))
        }.toMap()
        val substitution = argumentSubstitution + (returnName to returnValue)
        return constraint.map { it.substituteVariables(substitution) }
    }
}

class FunctionConstraintEmpty(parameterConstraints: ParameterConstraints) : FunctionConstraint(parameterConstraints) {
    override fun substitute(arguments: Map<ValueParameterDescriptor, Term>, returnValue: Term) = emptyList<Term>()
}

