package fixpoint.predicate

import fixpoint.TermTransformer


abstract class ConstValueTerm<T> : Term(){
    override fun transform(transformer: TermTransformer) = transformer.transformConstValueTerm(this)
    override fun transformChildren(transformer: TermTransformer) = this
}
