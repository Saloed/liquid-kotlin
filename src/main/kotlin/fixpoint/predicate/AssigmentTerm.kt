package fixpoint.predicate

import fixpoint.TermTransformer

data class AssigmentTerm(val lhs: Term, val rhs: Term): Term(){
    override fun transform(transformer: TermTransformer) = transformer.transformAssigmentTerm(this)

    override fun transformChildren(transformer: TermTransformer): Term {
        val lhs = this.lhs.transform(transformer)
        val rhs = this.rhs.transform(transformer)
        return AssigmentTerm(lhs, rhs)
    }

    override fun print() = "${lhs.print()} := ${rhs.print()}"
}
