package fixpoint.predicate

import fixpoint.TermTransformer

data class UnaryTerm(val operation: UnaryOperation, val term: Term) :Term(){
    override fun transform(transformer: TermTransformer) = transformer.transformUnaryTerm(this)

    override fun transformChildren(transformer: TermTransformer): Term {
        val term = this.term.transform(transformer)
        return UnaryTerm(operation, term)
    }

    override fun print() = "(${operation.print()} ${term.print()})"
}

