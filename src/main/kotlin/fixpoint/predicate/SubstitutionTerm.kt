package fixpoint.predicate

import fixpoint.TermTransformer

data class SubstitutionTerm(val name: String, val substitution: List<AssigmentTerm>) : Term(){
    override fun transform(transformer: TermTransformer) = transformer.transformSubstitutionTerm(this)

    override fun transformChildren(transformer: TermTransformer): Term {
        val substitution = this.substitution.map { it.transform(transformer) as AssigmentTerm }
        return SubstitutionTerm(name, substitution)
    }

    override fun print() = "$$name${substitution.joinToString { "[${it.print()}]" }}"
}
