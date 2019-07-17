package fixpoint.predicate

data class BinaryTerm(val lhs: Term, val operation: BinaryOperation, val rhs: Term): Term() {
    override fun print() = "(${lhs.print()} ${operation.print()} ${rhs.print()})"
}
