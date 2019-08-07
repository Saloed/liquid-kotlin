package fixpoint

import fixpoint.predicate.*

interface TermTransformer {
    fun transformTerm(term: Term) = term.transformChildren(this)

    fun transformBinaryTerm(term: BinaryTerm) = transformTerm(term)
    fun transformUnaryTerm(term: UnaryTerm) = transformTerm(term)
    fun transformVariableTerm(term: VariableTerm) = transformTerm(term)

    fun <T> transformConstValueTerm(term: ConstValueTerm<T>) = transformTerm(term)
    fun transformNumericValueTerm(term: NumericValueTerm) = transformConstValueTerm(term)
    fun transformBooleanValueTerm(term: BooleanValueTerm) = transformConstValueTerm(term)
    fun transformStringValueTerm(term: StringValueTerm) = transformConstValueTerm(term)

    fun transformPredicateTerm(term: PredicateTerm) = transformTerm(term)

    fun transformSubstitutionTerm(term: SubstitutionTerm) = transformTerm(term)
    fun transformAssigmentTerm(term: AssigmentTerm) = transformTerm(term)

}
