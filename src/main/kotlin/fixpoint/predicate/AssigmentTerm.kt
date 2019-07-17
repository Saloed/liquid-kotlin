package fixpoint.predicate

data class AssigmentTerm(val lhs: Term, val rhs: Term): Term(){
    override fun print() = "${lhs.print()} := ${rhs.print()}"
}
