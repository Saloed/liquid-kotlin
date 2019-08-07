package fixpoint.predicate

import fixpoint.TermTransformer

data class BinaryTerm(val lhs: Term, val operation: BinaryOperation, val rhs: Term): Term() {
    override fun transform(transformer: TermTransformer) = transformer.transformBinaryTerm(this)

    override fun transformChildren(transformer: TermTransformer): Term {
        val lhs = this.lhs.transform(transformer)
        val rhs = this.rhs.transform(transformer)
        return BinaryTerm(lhs, operation, rhs)
    }

    override fun print() = "(${lhs.print()} ${operation.print()} ${rhs.print()})"
}
