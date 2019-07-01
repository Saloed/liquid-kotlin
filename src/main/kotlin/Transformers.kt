import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.research.kex.ktype.KexVoid
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.*
import org.jetbrains.research.kex.state.term.BinaryTerm
import org.jetbrains.research.kex.state.term.CmpTerm
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.ValueTerm
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode

object SimplifyPredicates : Transformer<SimplifyPredicates> {
    override fun transformEqualityPredicate(predicate: EqualityPredicate): Predicate {
        val tlhv = this.transform(predicate.lhv)
        val trhv = this.transform(predicate.rhv)
        return when {
            tlhv == tf.getTrue() && trhv == tf.getTrue() -> Transformer.Stub
            tlhv == tf.getFalse() && trhv == tf.getFalse() -> Transformer.Stub
            else -> predicate
        }
    }

    override fun transformBinaryTerm(term: BinaryTerm): Term {
        val lhs = term.lhv.accept(this)
        val rhs = term.rhv.accept(this)

        return when (term.opcode) {
            is BinaryOpcode.Or -> when {
                lhs == tf.getTrue() -> tf.getTrue()
                rhs == tf.getTrue() -> tf.getTrue()
                else -> BinaryTerm(term.type, term.opcode, lhs, rhs)
            }
            is BinaryOpcode.And -> when {
                lhs == tf.getTrue() -> rhs
                rhs == tf.getTrue() -> lhs
                else -> BinaryTerm(term.type, term.opcode, lhs, rhs)
            }
            else -> BinaryTerm(term.type, term.opcode, lhs, rhs)
        }
    }

    override fun transformCmpTerm(term: CmpTerm): Term {
        val lhs = term.lhv.accept(this)
        val rhs = term.rhv.accept(this)
        return when (term.opcode) {
            is CmpOpcode.Eq -> when {
                lhs == tf.getTrue() && rhs == tf.getTrue() -> tf.getTrue()
                lhs == tf.getFalse() && rhs == tf.getFalse() -> tf.getTrue()

                lhs == tf.getTrue() && rhs == tf.getFalse() -> tf.getFalse()
                lhs == tf.getFalse() && rhs == tf.getTrue() -> tf.getFalse()

                lhs == tf.getTrue() -> rhs
                rhs == tf.getTrue() -> lhs

                else -> null
            }
            else -> null
        } ?: CmpTerm(term.type, term.opcode, lhs, rhs)
    }

}

class RenameVariables(val variablePrefix: Map<String, Int>) : Transformer<RenameVariables> {
    override fun transformValue(term: ValueTerm): ValueTerm {
        val prefix = variablePrefix[term.name] ?: return term
        return tf.getValue(term.type, "${prefix}_${term.name}").apply {
            debugInfo = term.debugInfo
        }
    }
}

object RemoveVoid : Transformer<RemoveVoid> {

    object StubTerm : Term("StubTerm", KexVoid, emptyList()) {
        override fun print() = "StubTerm"
        override fun <T : Transformer<T>> accept(t: Transformer<T>) = StubTerm
    }

    override fun transformValueTerm(term: ValueTerm): Term {
        if (term.type is KexVoid)
            return StubTerm
        return term
    }

    override fun transformTerm(term: Term): Term {
        val subterms = term.subterms.map { it.accept(this) }
        if (subterms.any { it is StubTerm }) return StubTerm
        return term
    }

    override fun transformPredicate(predicate: Predicate): Predicate {
        val operands = predicate.operands.map { it.accept(this) }
        if (operands.any { it is StubTerm }) return Transformer.Stub
        return predicate
    }
}

object ValueTermCollector : Transformer<ValueTermCollector> {
    private val variables = hashSetOf<Term>()

    override fun transformValueTerm(term: ValueTerm): Term {
        variables.add(term)
        return term
    }

    override fun apply(ps: PredicateState): PredicateState {
        variables.clear()
        return super.apply(ps)
    }

    operator fun invoke(ps: PredicateState): Set<Term> {
        apply(ps)
        return variables.toSet()
    }
}

object OptimizeEqualityChains : Transformer<OptimizeEqualityChains> {

    class ReplaceTerm(val replace: Term, val with: Term) : Transformer<ReplaceTerm> {
        override fun transformTerm(term: Term) = if (term == replace) with else super.transformTerm(term)
    }

    fun inlineSingleValueTerm(ps: PredicateState): PredicateState? {
        val values = ValueTermCollector(ps)
        val valueToInline = values.asSequence()
                .map { it to findUsages(it, ps) }
                .filter { (_, predicates) -> predicates.size == 2 }
                .filter { (_, predicates) -> predicates.all {
                    it is EqualityPredicate|| it is InequalityPredicate || it is ImplicationPredicate
                } }
                .filter { (term, predicates) ->
                    predicates.any {
                        it is EqualityPredicate && it.type == PredicateType.State() && (it.lhv == term || it.rhv == term)
                    }
                }
                .firstOrNull() ?: return null
        val usages = valueToInline.second
        val term = valueToInline.first
        val inlinePredicate = usages.firstOrNull { it is EqualityPredicate && (it.lhv == term || it.rhv == term) }
                .safeAs<EqualityPredicate>() ?: return null
        val substitution = when (term) {
            inlinePredicate.lhv -> inlinePredicate.rhv
            inlinePredicate.rhv -> inlinePredicate.lhv
            else -> return null
        }
        val inlineToPredicates = usages.filterNot { it == inlinePredicate }
        val psWithoutInlinePredicate = ps.filter { it != inlinePredicate }
        return ReplaceTerm(term, substitution).apply(psWithoutInlinePredicate)
    }

    override fun apply(ps: PredicateState): PredicateState {
        var newPs = ps
        while (true) {
            newPs = inlineSingleValueTerm(newPs) ?: return newPs.simplify()
        }
    }

    fun findUsages(term: Term, ps: PredicateState): List<Predicate> {
        val usages = arrayListOf<Predicate>()
        ps.map {
            if (termIsUsed(term, it)) usages.add(it)
            it
        }
        return usages
    }

    fun termIsUsed(term: Term, predicate: Predicate): Boolean = predicate.operands.any { termIsUsed(term, it) }
    fun termIsUsed(term: Term, visitedTerm: Term): Boolean = term == visitedTerm || visitedTerm.subterms.any { termIsUsed(term, it) }

}

