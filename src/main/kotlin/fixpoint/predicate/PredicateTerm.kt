package fixpoint.predicate

import fixpoint.TermTransformer

data class PredicateTerm(val name: String, val argument: Term): Term() {
    override fun transform(transformer: TermTransformer) = transformer.transformPredicateTerm(this)

    override fun transformChildren(transformer: TermTransformer): Term {
        val argument = this.argument.transform(transformer)
        return PredicateTerm(name, argument)
    }

    override fun print() = "($name ${argument.print()})"
}
