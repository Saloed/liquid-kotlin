package fixpoint.predicate

import fixpoint.TermTransformer

data class NumericValueTerm(val number: Int) : ConstValueTerm<Int>() {
    override fun print() = "$number"
    override fun transform(transformer: TermTransformer) = transformer.transformNumericValueTerm(this)
}
