import org.jetbrains.research.kex.smt.model.SMTModel
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.ChainState
import org.jetbrains.research.kex.state.ChoiceState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.EqualityPredicate
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.transformer.Transformer

object PsWithModelPrinter {

    fun print(ps: PredicateState, model: SMTModel) = when (ps) {
        is BasicState -> print(ps, model)
        is ChoiceState -> print(ps, model)
        is ChainState -> print(ps, model)
        else -> throw IllegalStateException("Not possible")
    }

    private fun print(ps: BasicState, model: SMTModel): String {
        val sb = StringBuilder()
        sb.appendln("(")
        ps.predicates.forEach { sb.appendln("  ${print(it, model)}") }
        sb.append(")")
        return sb.toString()
    }

    private fun print(ps: ChoiceState, model: SMTModel): String {
        val sb = StringBuilder()
        sb.appendln("(BEGIN")
        sb.append(ps.choices.joinToString { " <OR> {${print(it, model)}}" })
        sb.append(" END)")
        return sb.toString()
    }

    private fun print(ps: ChainState, model: SMTModel): String {
        val sb = StringBuilder()
        sb.append(print(ps.base, model))
        sb.append(" -> ")
        sb.append(print(ps.curr, model))
        return sb.toString()
    }

    private fun print(predicate: Predicate, model: SMTModel): String = when (predicate) {
        is EqualityPredicate -> {
            if (predicate.lhv !in model.assignments) "$predicate"
            else "$predicate   $$$   ${model.assignments[predicate.lhv]}"
        }
        else -> "$predicate"
    }

}

object PsModelInliner {

    class ModelInliner(val model: SMTModel) : Transformer<ModelInliner> {
        override fun transformTerm(term: Term) = model.assignments[term]?.also {
            it.debugInfo = "$term"
        } ?: super.transformTerm(term)
    }

    fun inline(ps: PredicateState, model: SMTModel) = listOf(ModelInliner(model), SimplifyPredicates).apply(ps).simplify()
}