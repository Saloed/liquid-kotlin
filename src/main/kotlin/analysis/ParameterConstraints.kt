package analysis

import fixpoint.Bind
import fixpoint.predicate.Term
import fixpoint.predicate.VariableTerm
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType

data class ParameterConstraints(
        val parameters: Map<IrValueParameter, List<Term>>,
        val dependency: TermDependencyCollector.TermDependency
) {
    fun resolveInContext(context: AnalysisContext, arguments: Map<ValueParameterDescriptor, Term>) {
        val paramsWithType = parameters.keys.map { it.name.asString() to it.type }.toMap()
        val argumentsByName = arguments.mapKeys { (arg, _) -> arg.name.asString() }
        val prefix = context.generateUniqueName()

        val binds = resolveBindsInContext(context, prefix, dependency.binds, argumentsByName, paramsWithType)
        context.binds.putAll(binds.map { it.id to it })

        val environment = context.createEnvironmentForBinds(context.binds.values.toList())

        for ((param, constraint) in parameters) {
            if (constraint.isEmpty()) continue
            val paramName = param.name.asString().withPrefix(prefix)
            val lhs = run {
                val itTerm = context.createVariable("it", param.type)
                val paramTerm = context.createVariable(paramName, param.type)
                val term = context.createEquality(itTerm, paramTerm)
                context.createPredicate("it", param.type, listOf(term))
            }
            val rhs = run {
                val rhsConstraint = constraint
                        .map { addPrefixToVariables(prefix, it) }
                        .map { it.substituteVariables(mapOf(paramName to VariableTerm("it"))) }
                context.createPredicate("it", param.type, rhsConstraint)
            }
            val paramConstraint = context.createConstraint(environment, lhs, rhs)
            context.constraints.add(paramConstraint)
        }

    }

    private fun resolveBindsInContext(
            context: AnalysisContext,
            prefix: String,
            binds: List<Bind>,
            arguments: Map<String, Term>,
            params: Map<String, IrType>
    ) = binds.map {
        val name = it.name.withPrefix(prefix)
        val terms = when {
            it.name in params -> {
                val argument = arguments[it.name]
                        ?: throw IllegalArgumentException("Parameter ${it.name} is not provided")
                val type = params[it.name]!!
                val variable = context.createVariable(name, type)
                listOf(context.createEquality(variable, argument))
            }
            else -> it.predicate.constraint.map { addPrefixToVariables(prefix, it) }
        }
        context.createBind(name, it.predicate.type, terms)
    }

    private fun addPrefixToVariables(prefix: String, term: Term): Term {
        val variables = term.collectVariables()
        val renaming = variables.map { it.name to VariableTerm(it.name.withPrefix(prefix)) }.toMap()
        return term.substituteVariables(renaming)
    }

    private fun String.withPrefix(prefix: String) = "${prefix}_${this}"

}
