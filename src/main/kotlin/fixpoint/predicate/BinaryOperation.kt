package fixpoint.predicate

import fixpoint.Printable

enum class BinaryOperation: Printable {
    AND, GTE, LTE, EQUAL, NOT_EQUAL, GREATER, LOWER, PLUS, MINUS, PREDICATE_EQUAL;

    override fun print() = when(this){
        AND -> "&&"
        GTE -> ">="
        LTE -> "<="
        EQUAL -> "~~"
        NOT_EQUAL -> "!="
        GREATER -> ">"
        LOWER -> "<"
        PLUS -> "+"
        MINUS -> "-"
        PREDICATE_EQUAL -> "<=>"
    }
}
