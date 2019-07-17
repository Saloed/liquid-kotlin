package fixpoint.predicate

data class SubstitutionTerm(val name: String, val substitution: List<AssigmentTerm>) : Term(){
    override fun print() = "$$name${substitution.joinToString { "[${it.print()}]" }}"
}
