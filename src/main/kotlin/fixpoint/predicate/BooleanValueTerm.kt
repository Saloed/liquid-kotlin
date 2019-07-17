package fixpoint.predicate

import org.jetbrains.research.kex.util.toInt

data class BooleanValueTerm(val value: Boolean) : ConstValueTerm<Boolean>() {
    override fun print() = "${value.toInt()}"
}
