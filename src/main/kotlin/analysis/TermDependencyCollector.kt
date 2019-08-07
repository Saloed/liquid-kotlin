package analysis

import fixpoint.Bind
import fixpoint.Constraint
import fixpoint.WFConstraint
import fixpoint.predicate.Term
import fixpoint.predicate.VariableTerm
import org.jetbrains.kotlin.backend.common.pop

class TermDependencyCollector(val context: AnalysisContext) {

    data class TermDependency(
            val binds: List<Bind>,
            val constraints: List<Constraint>,
            val wfConstraints: List<WFConstraint>
    ) {
        fun merge(other: TermDependency) = TermDependency(
                (binds + other.binds).toSet().toList(),
                (constraints + other.constraints).toSet().toList(),
                (wfConstraints + other.wfConstraints).toSet().toList()
        )

        companion object {
            val EMPTY = TermDependency(emptyList(), emptyList(), emptyList())
        }
    }

    fun collect(term: Term): TermDependency {
        val binds = collectBinds(term)
        // todo: collect constraints
        return TermDependency(binds, emptyList(), emptyList())
    }


    private fun collectBinds(term: Term): List<Bind> {
        val result = mutableSetOf<Bind>()
        val processedVariables = mutableSetOf<VariableTerm>()
        val variables = mutableListOf<VariableTerm>()
        variables.addAll(term.collectVariables())
        while (variables.isNotEmpty()) {
            val current = variables.pop()
            processedVariables.add(current)
            val binds = context.binds.values
                    .filter { it.name == current.name }
                    .filterNot { it in result }
            result.addAll(binds)
            val nexVariables = binds
                    .flatMap { it.predicate.constraint }
                    .flatMap { it.collectVariables() }
                    .filterNot { it in processedVariables }
            variables.addAll(nexVariables)
        }
        return result.toList()
    }


}
