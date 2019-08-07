package fixpoint.predicate

import fixpoint.TermTransformer

data class StringValueTerm(val value: String) : ConstValueTerm<String>() {
    override fun print() = "\"$value\""
    override fun transform(transformer: TermTransformer) = transformer.transformStringValueTerm(this)
}
