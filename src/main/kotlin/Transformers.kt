import ClassInfo.typeInfo
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.research.kex.ktype.KexClass
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.ktype.KexVoid
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.EqualityPredicate
import org.jetbrains.research.kex.state.predicate.Predicate
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

class OptimizeEqualityChains : Transformer<OptimizeEqualityChains> {
    data class Substitution(val predicate: Predicate, val replace: Term, val with: Term)

    val substitute = mutableListOf<Substitution>()
    val remove = mutableListOf<Predicate>()

    override fun apply(ps: PredicateState): PredicateState {
        val values = ValueTermCollector(ps)
        val usages = values.zipMap { findUsages(it, ps) }

        val usagesToOptimize = usages
                .filter { (_, predicates) -> predicates.all { it is EqualityPredicate } }
                .filter { (_, predicates) -> predicates.size == 2 }

        for ((term, termUsages) in usagesToOptimize) {
            optimize(term, termUsages)
        }

        mergeSubstitutions()

        return ps
    }

    fun optimize(term: Term, usages: List<Predicate>) {
        val inline = usages.firstOrNull { it is EqualityPredicate && (it.lhv == term || it.rhv == term) }
                .safeAs<EqualityPredicate>() ?: return
        val substitution = when (term) {
            inline.lhv -> inline.rhv
            inline.rhv -> inline.lhv
            else -> return
        }
        remove.add(inline)
        usages.filterNot { it == inline }.forEach {
            substitute.add(Substitution(it, term, substitution))
        }
    }

    data class MergeChain(val start: Term, val end: Term)

    fun mergeSubstitutions() {
        val replaceWith = substitute.groupBy { it.with }
        val replaceIt = substitute.map { it.replace to it }.toMap()
        val used = hashSetOf<Substitution>()
        val chains = mutableListOf<MergeChain>()

        fun visit(substitution: Substitution) {
            if (substitution in used) return
            used.add(substitution)
            if (substitution.replace in replaceWith) {
                //todo: create merge chain
            }
            replaceIt[substitution.with]?.let { visit(it) }
        }

        for (substitution in substitute) {
            visit(substitution)
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

