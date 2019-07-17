package fixpoint.predicate

import fixpoint.Printable

abstract class Term: Printable{
    override fun toString() = print()
}
