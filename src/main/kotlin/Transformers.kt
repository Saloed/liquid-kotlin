import cucumber.api.java.it.Ma
import org.jetbrains.research.kex.state.predicate.EqualityPredicate
import org.jetbrains.research.kex.state.term.BinaryTerm
import org.jetbrains.research.kex.state.term.CmpTerm
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.ValueTerm
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode


object SimplifyPredicates : Transformer<SimplifyPredicates> {
    override fun transformEqualityPredicate(predicate: EqualityPredicate) = EqualityPredicate(
            predicate.lhv.accept(this),
            predicate.rhv.accept(this),
            predicate.type,
            predicate.location
    )

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
            additionalInfo = term.additionalInfo
        }
    }
}
