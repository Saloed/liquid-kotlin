package fixpoint.predicate

import fixpoint.TermTransformer

data class VariableTerm(val name: String) : Term() {
    override fun transform(transformer: TermTransformer) = transformer.transformVariableTerm(this)

    override fun transformChildren(transformer: TermTransformer) = this

    override fun print() = "$name"

}
