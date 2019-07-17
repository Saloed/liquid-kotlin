package fixpoint.predicate

data class NumericValueTerm(val number: Int) : ConstValueTerm<Int>() {
    override fun print() = "$number"
}
