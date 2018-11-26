import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.research.kex.ktype.KexBool
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.term.*
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode

import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.IllegalArgumentException
import kotlin.collections.HashSet

interface MyTransformer<T : MyTransformer<T>> : Transformer<T> {
    override fun transformBase(predicate: Predicate): Predicate = when (predicate) {
        is BoolPredicate -> transformBoolPredicate(predicate)
        else -> super.transformBase(predicate)
    }

    fun transformBoolPredicate(predicate: BoolPredicate): Predicate = predicate.accept(this)

    override fun transform(term: Term): Term = when (term) {
        is TypeTerm -> transformTypeTerm(term)
        is EmptyTerm -> transformEmptyTerm(term)
        is ParameterTerm -> transformParameterTerm(term)
        is FunctionReferenceTerm -> transformFunctionReference(term)
        is ReferenceTerm -> transformReferenceTerm(term)
        is TypeValueTerm -> transformTypeValueTerm(term)
        else -> super.transform(term)
    }

    fun transformTypeTerm(term: TypeTerm): Term = term.accept(this)
    fun transformTypeValueTerm(term: TypeValueTerm): Term = term.accept(this)
    fun transformFunctionReference(term: FunctionReferenceTerm): Term = term.accept(this)
    fun transformParameterTerm(term: ParameterTerm): Term = term.accept(this)
    fun transformReferenceTerm(term: ReferenceTerm): Term = term.accept(this)

    fun transformEmptyTerm(term: EmptyTerm): Term {
        println("EmptyTerm: $term")
        return term
    }


}

class TransformReferences(val bindingContext: BindingContext, val anonymize: Boolean = true) : MyTransformer<TransformReferences> {

    private val valueTermFroElement = HashMap<PsiElement, ValueTerm>()
    private val referenceIdGenerator = AtomicInteger(0)

    override fun transformReferenceTerm(term: ReferenceTerm): Term {
        val referenceTarget = bindingContext[BindingContext.REFERENCE_TARGET, term.ref]
                ?: throw IllegalArgumentException("No target for reference ${term.ref}")
        val targetPsi = referenceTarget.findPsi()
                ?: throw IllegalArgumentException("No PSI for reference target $referenceTarget")
        return valueTermFroElement.getOrPut(targetPsi) {
            val name = if (anonymize) "${referenceIdGenerator.getAndIncrement()}" else targetPsi.text
            tf.getValue(term.type, "ReferenceValue[$name]")
        }
    }
}

//
//class SimplifyTruePredicates : MyTransformer<SimplifyTruePredicates> {
//    override fun transformBoolPredicate(predicate: BoolPredicate): Predicate {
//        val transformed = predicate.accept(this)
//        if (
//            transformed is BoolPredicate
//            && transformed.term is ConstBoolTerm
//            && transformed.term.value
//        ) return Transformer.Stub
//        return transformed
//    }
//}


class TypeValueInliner(val typeConstraints: LiquidTypeInfo) : MyTransformer<TypeValueInliner> {
    override fun transformTypeValueTerm(term: TypeValueTerm): Term = typeConstraints[term.typeElement]?.combineWithAnd()
            ?: term
}

class DummyTypeReferenceValue(val anonymize: Boolean = true) : MyTransformer<DummyTypeReferenceValue> {

    private val valueTermFroElement = HashMap<PsiElement, ValueTerm>()
    private val valueTermForValueElement = HashMap<PsiElement, ValueTerm>()
    private val typeIdGenerator = AtomicInteger(0)

    override fun transformTypeTerm(term: TypeTerm): Term = valueTermFroElement.getOrPut(term.typeElement) {
        val name = if (anonymize) "${typeIdGenerator.getAndIncrement()}" else term.typeElement.text
        tf.getValue(KexBool, "TypeValue[$name]")
    }

    override fun transformTypeValueTerm(term: TypeValueTerm): Term = valueTermForValueElement.getOrPut(term.typeElement) {
        val name = if (anonymize) "${typeIdGenerator.getAndIncrement()}" else term.typeElement.text
        tf.getValue(term.type, "Value[$name]")
    }
}

class DeepTypeReferenceInlineTransformer(val typeConstraints: LiquidTypeInfo, val fallback: Boolean = false) : MyTransformer<DeepTypeReferenceInlineTransformer> {
    override fun transformTypeTerm(term: TypeTerm): Term {
        val state = typeConstraints[term.typeElement]
                ?: if (fallback) listOf(tf.getTrue()) else error("Type Info not found ${term.typeElement.text}")
        return state.combineWithAnd().deepAccept(this)
    }
}

class DeepReferenceSubstitutionTransformer(
        val substitution: Map<PsiElement, PsiElement>,
        val bindingContext: BindingContext,
        val typeConstraints: LiquidTypeInfo
) : MyTransformer<DeepReferenceSubstitutionTransformer> {

    val ignore = HashSet<Term>() //fixme: tricky hack

    override fun transformTypeTerm(term: TypeTerm): Term {

        fun local(): Term {
            if (term in ignore) return term
            if (term.typeElement in substitution) {
                val newElement = substitution[term.typeElement]!!
                val new = tf.elementType(if (newElement is KtNameReferenceExpression) bindingContext[BindingContext.REFERENCE_TARGET, newElement]?.findPsi()!! else newElement)
                ignore.add(new)
                return new
            }
            val state = typeConstraints[term.typeElement]
                    ?: throw IllegalArgumentException("No type for ${term.typeElement.text}")
            return state.combineWithAnd().deepAccept(this)
        }

        val res = local()
        println("Transform | ${term.typeElement.text} | ${res}")
        return res
    }

    override fun transformTypeValueTerm(term: TypeValueTerm): Term =
            substitution[term.typeElement]?.let { tf.elementValue(it, term.type) } ?: term

    override fun transformReferenceTerm(term: ReferenceTerm): Term {
        println("$term")
        val referenceSubstitution = substitution[term.ref]
        if (referenceSubstitution != null) return tf.elementType(referenceSubstitution)
        val referenceTarget = bindingContext[BindingContext.REFERENCE_TARGET, term.ref]
                ?: throw IllegalArgumentException("No target for reference ${term.ref}")
        val targetPsi = referenceTarget.findPsi()
                ?: throw IllegalArgumentException("No PSI for reference target $referenceTarget")
        val targetSubstitution = substitution[targetPsi]
        if (targetSubstitution != null) return tf.elementType(targetSubstitution)
        return term
    }
}

class TypeReferenceInliner(val typeConstraints: LiquidTypeInfo) : MyTransformer<TypeReferenceInliner> {
    override fun transformTypeTerm(term: TypeTerm): Term {
        val state = typeConstraints[term.typeElement]
                ?: error("Type Info not found ${term.typeElement.javaClass} ${term.typeElement.text}")
        return state.combineWithAnd()
    }

    override fun transformTypeValueTerm(term: TypeValueTerm): Term = typeConstraints[term.typeElement]?.combineWithAnd()
            ?: term
}

class ReplaceSelfReferenceWithTrue(val element: PsiElement) : MyTransformer<ReplaceSelfReferenceWithTrue> {
    override fun transformTypeTerm(term: TypeTerm): Term = if (term.typeElement == element) tf.getTrue() else term
}

class RemoveTypeReferenceRecursion(val element: PsiElement, val typeConstraints: LiquidTypeInfo) : MyTransformer<RemoveTypeReferenceRecursion> {
    override fun transformTypeTerm(term: TypeTerm): Term =
            if (term.typeElement != element) term else typeConstraints[element]!!
                    .combineWithAnd()
                    .deepAccept(ReplaceSelfReferenceWithTrue(element))
}

class BinaryExpressionSimplifier : MyTransformer<BinaryExpressionSimplifier> {


    override fun transformNegTerm(term: NegTerm): Term {
        val operand = term.operand
        return if (operand is NegTerm) operand.operand.accept(this) else operand.accept(this)
    }

    override fun transformBinaryTerm(term: BinaryTerm): Term {
        val lhs = term.lhv.accept(this)
        val rhs = term.rhv.accept(this)

        return when (term.opcode) {
            is BinaryOpcode.Or -> when {
                lhs == tf.getTrue() -> tf.getTrue()
                rhs == tf.getTrue() -> tf.getTrue()
                else -> BinaryTerm(term.type, term.opcode, lhs, rhs)
            }
            is BinaryOpcode.And -> when {
                lhs == tf.getTrue() -> rhs
                rhs == tf.getTrue() -> lhs
                else -> BinaryTerm(term.type, term.opcode, lhs, rhs)
            }
//            is BinaryOpcode.Implies -> when {
//                lhs == tf.getTrue() -> rhs
//                else -> BinaryTerm(term.type, term.opcode, lhs, rhs)
//            }
            else -> BinaryTerm(term.type, term.opcode, lhs, rhs)
        }
    }

    override fun transformCmpTerm(term: CmpTerm): Term {
        val lhs = term.lhv.accept(this)
        val rhs = term.rhv.accept(this)
        return if (term.opcode == CmpOpcode.Eq() && lhs == rhs) tf.getTrue() else CmpTerm(term.type, term.opcode, lhs, rhs)
    }

}

