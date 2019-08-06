package fixpoint

import fixpoint.predicate.Term

data class Solution(val name: String, val predicates: List<Term>) : Printable {
    override fun print() = "$name: ${predicates.joinToString(" && ") { it.print()}}"
}
