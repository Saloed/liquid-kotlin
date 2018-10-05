import com.intellij.psi.PsiElement
import java.util.*

sealed class Term {
    data class Value(val condition: String) : Term()
    data class Variable(val reference: PsiElement) : Term() {
        override fun toString() = "Variable(reference=$reference ${reference.text})"
    }

    data class BinaryTerm(val left: Term, val right: Term) : Term() {
        override fun simplify() = BinaryTerm(left.simplify(), right.simplify())
    }

    data class UnaryTerm(val term: Term) : Term() {
        override fun simplify() = UnaryTerm(term.simplify())
    }

    data class UnaryExpression(val expr: String, val term: UnaryTerm) : Term()
    data class BinaryExpression(val expr: String, val term: BinaryTerm) : Term()

    data class Or(val term: BinaryTerm) : Term() {
        constructor(left: Term, right: Term) : this(BinaryTerm(left, right))

        override fun simplify(): Term {
            val base = term.simplify()
            if (base.left == TRUE || base.right == TRUE) return TRUE
            if (base.left == FALSE && base.right == FALSE) return FALSE
            return Or(base)
        }
    }

    data class And(val term: BinaryTerm) : Term() {

        constructor(left: Term, right: Term) : this(BinaryTerm(left, right))

        override fun simplify(): Term {
            val base = term.simplify()
            if (base.left == TRUE && base.right == TRUE) return TRUE
            if (base.left == FALSE || base.right == FALSE) return FALSE
            return And(base)
        }
    }

    data class Not(val term: UnaryTerm) : Term() {
        constructor(term: Term) : this(UnaryTerm(term))

        override fun simplify(): Term {
            val base = term.simplify()
            return when (base.term) {
                TRUE -> FALSE
                FALSE -> TRUE
                else -> Not(base)
            }
        }
    }

    companion object {
        val TRUE = Value("true")
        val FALSE = Value("false")
    }

    open fun simplify() = this

}

object LiquidTypeInfoStorage {
    private val typeConstraints = IdentityHashMap<PsiElement, Term>()


    operator fun get(element: PsiElement) = typeConstraints[element]
    operator fun set(element: PsiElement, typeInfo: Term) {
        val currentConstraint = typeConstraints[element]
        if (currentConstraint == null) {
            typeConstraints[element] = typeInfo.simplify()
        } else {
            typeConstraints[element] = Term.And(currentConstraint, typeInfo).simplify()
        }
    }

    fun putIfNotExists(element: PsiElement, default: Term): Term {
        val current = get(element)
        if (current != null) return current
        set(element, default)
        return default
    }

    fun unsafeTypeConstraints() = typeConstraints

    override fun toString() = typeConstraints.map { it.text() }.joinToString("\n")

}


fun Map.Entry<PsiElement, Term>.text() = "$key ${key.text} -> $value"