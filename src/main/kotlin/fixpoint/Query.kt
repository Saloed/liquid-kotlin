package fixpoint

import fixpoint.predicate.Term


sealed class Type : Printable {
    data class NamedType(val name: String) : Type() {
        override fun print() = name
    }

    data class IndexedType(val idx: Int) : Type() {
        override fun print() = "@($idx)"
    }

    data class TypeWithArguments(val name: String, val arguments: List<Type>) : Type() {
        override fun print() = "($name ${arguments.joinToString(" ") { it.print() }})"
    }

    data class Function(val number: Int, val arguments: List<Type>) : Type() {
        override fun print() = "func($number,[${arguments.joinToString("; ") { it.print() }}])"
    }
}

data class Predicate(val name: String, val type: Type, val constraint: List<Term>) : Printable {
    override fun print() = "{$name : ${type.print()} | [${constraint.joinToString("; ") { it.print() }}]}"
}

data class QualifierArgument(val name: String, val type: Type) : Printable {
    override fun print() = "$name : ${type.print()}"
}

data class Qualifier(val name: String, val arguments: List<QualifierArgument>, val term: Term) : Printable {
    override fun print() = "qualif $name (${arguments.joinToString(", ") { it.print() }}) : ((${term.print()}))"
}

data class Constant(val name: String, val type: Type) : Printable {
    override fun print() = "constant $name : (${type.print()})"
}

data class Bind(val id: Int, val name: String, val predicate: Predicate) : Printable {
    override fun print() = "bind $id $name : ${predicate.print()}"
}

data class Environment(val ids: List<Int>) : Printable {
    override fun print() = "env [${ids.joinToString("; ") { "$it" }}]"
}

data class Constraint(val env: Environment, val lhs: Predicate, val rhs: Predicate, val id: Int) : Printable {
    override fun print() = """
    constraint:    
        ${env.print()}
        lhs ${lhs.print()}
        rhs ${rhs.print()}
        id $id tag [$id]
    """.trimIndent()
}

data class WFConstraint(val env: Environment, val reft: Predicate, val id: Int) : Printable {
    override fun print() = """
    wf:    
        ${env.print()}
        reft ${reft.print()}
    """.trimIndent()
}

data class Query(
        val qualifiers: List<Qualifier>,
        val constants: List<Constant>,
        val binds: List<Bind>,
        val constraints: List<Constraint>,
        val wf: List<WFConstraint>
) : Printable {
    override fun print() = (qualifiers + constants + binds + constraints + wf).joinToString("\n") { it.print() }
}

