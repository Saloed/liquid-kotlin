package fixpoint.predicate

import fixpoint.Printable

enum class UnaryOperation: Printable {
    NOT;

    override fun print() = when(this){
        NOT -> "~"
    }
}
