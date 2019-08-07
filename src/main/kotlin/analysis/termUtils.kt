package analysis

import fixpoint.TermTransformer
import fixpoint.predicate.Term
import fixpoint.predicate.VariableTerm


internal class VariableSubstitution(val substitution: Map<String, Term>) : TermTransformer {
    override fun transformVariableTerm(term: VariableTerm) = substitution[term.name] ?: term
}


fun Term.substituteVariables(substitution: Map<String, Term>): Term {
    val substitutor = VariableSubstitution(substitution)
    return this.transform(substitutor)
}


internal class VariableCollector : TermTransformer {
    val variables = mutableSetOf<VariableTerm>()
    override fun transformVariableTerm(term: VariableTerm): Term {
        variables.add(term)
        return super.transformVariableTerm(term)
    }
}

fun Term.collectVariables(): List<VariableTerm> {
    val collector = VariableCollector()
    transform(collector)
    return collector.variables.toList()
}
