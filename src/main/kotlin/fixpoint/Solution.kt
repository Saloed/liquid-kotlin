package fixpoint

import fixpoint.predicate.Term

data class Solution(val name: String, val predicates: List<Term>) : Printable {
    override fun print() = predicates.joinToString(" && ") { it.print()}
}
