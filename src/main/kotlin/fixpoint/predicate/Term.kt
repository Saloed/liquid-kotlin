package fixpoint.predicate

import fixpoint.Printable
import fixpoint.TermTransformer

abstract class Term: Printable{
    override fun toString() = print()

    abstract fun transform(transformer: TermTransformer): Term

    abstract fun transformChildren(transformer: TermTransformer): Term

}
