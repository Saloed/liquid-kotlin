package fixpoint.predicate

data class UnaryTerm(val operation: UnaryOperation, val term: Term) :Term(){
    override fun print() = "(${operation.print()} ${term.print()})"
}

