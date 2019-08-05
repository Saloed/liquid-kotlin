package fixpoint.predicate

data class StringValueTerm(val value: String) : ConstValueTerm<String>() {
    override fun print() = "\"$value\""
}
