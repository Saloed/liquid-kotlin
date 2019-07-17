package fixpoint.predicate

data class PredicateTerm(val name: String, val argument: Term): Term() {
    override fun print() = "($name ${argument.print()})"
}
