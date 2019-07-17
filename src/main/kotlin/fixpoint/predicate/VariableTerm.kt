package fixpoint.predicate

data class VariableTerm(val name: String) : Term() {
    override fun print() = "$name"

}
