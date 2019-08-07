package fixpoint.predicate

import fixpoint.TermTransformer
import org.jetbrains.research.kex.util.toInt

data class BooleanValueTerm(val value: Boolean) : ConstValueTerm<Boolean>() {
    override fun print() = "${value.toInt()}"
    override fun transform(transformer: TermTransformer) = transformer.transformBooleanValueTerm(this)

}
