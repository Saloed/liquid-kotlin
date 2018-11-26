import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.js.descriptorUtils.nameIfStandardType
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isBoolean
import org.jetbrains.kotlin.types.typeUtil.isChar
import org.jetbrains.kotlin.types.typeUtil.isInt
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.predicate.PredicateType
import org.jetbrains.research.kex.state.term.BinaryTerm
import org.jetbrains.research.kex.state.term.CmpTerm
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.ir.Location
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode

object TokenMapping {
    val cmpTokenMap = hashMapOf(
            KtTokens.EQEQ to CmpOpcode.Eq(),
            KtTokens.EXCLEQ to CmpOpcode.Neq(),
            KtTokens.GT to CmpOpcode.Gt(),
            KtTokens.LT to CmpOpcode.Lt(),
            KtTokens.GTEQ to CmpOpcode.Ge(),
            KtTokens.LTEQ to CmpOpcode.Le()
    )

    val binaryTokenMap = hashMapOf(
            KtTokens.PLUS to BinaryOpcode.Add(),
            KtTokens.MINUS to BinaryOpcode.Sub(),
            KtTokens.MUL to BinaryOpcode.Mul(),
            KtTokens.DIV to BinaryOpcode.Div(),
            KtTokens.PERC to BinaryOpcode.Rem(),
            KtTokens.ANDAND to BinaryOpcode.And(),
            KtTokens.OROR to BinaryOpcode.Or()
    )


    fun cmpFromKtToken(token: IElementType) = TokenMapping.cmpTokenMap[token]
            ?: throw IllegalArgumentException("Unknown token type")

    fun binaryFromKtToken(token: IElementType) = TokenMapping.binaryTokenMap[token]
            ?: throw IllegalArgumentException("Unknown token type")

}

fun IElementType.isCmpToken() = this in TokenMapping.cmpTokenMap

fun IElementType.isBinaryOperationToken() = this in TokenMapping.binaryTokenMap

fun TermFactory.compareTerm(token: IElementType, left: Term, right: Term): Term {
    val cmpToken = TokenMapping.cmpFromKtToken(token)
    return getCmp(cmpToken, left, right)
}

fun TermFactory.equalityTerm(lhs: Term, rhs: Term) = compareTerm(KtTokens.EQEQ, lhs, rhs)

fun TermFactory.binaryTerm(token: IElementType, left: Term, right: Term): Term {
    val binaryToken = TokenMapping.binaryFromKtToken(token)
    return getBinary(binaryToken, left, right)
}


fun TermFactory.binaryTermForOpToken(token: IElementType, left: Term, right: Term) = when {
    token.isBinaryOperationToken() -> getBinary(TokenMapping.binaryFromKtToken(token), left, right)
    token.isCmpToken() -> getCmp(TokenMapping.cmpFromKtToken(token), left, right)
    else -> throw IllegalArgumentException("Unknown operation type")
}

fun TermFactory.fromConstant(type: IElementType, value: String) = when (type) {
    KtNodeTypes.BOOLEAN_CONSTANT -> getBool(value.toBoolean())
    KtNodeTypes.INTEGER_CONSTANT -> getInt(value.toInt())
    KtNodeTypes.STRING_TEMPLATE -> getString(value)
    else -> throw IllegalArgumentException("Unknown constant type")
}

fun TermFactory.fromValue(value: Any?) = when (value) {
    is Boolean -> getBool(value)
    is Int -> getInt(value)
    is String -> getString(value)
    else -> throw IllegalArgumentException("Unknown value type")
}

fun TermFactory.emptyTerm(message: String = "huy") = EmptyTerm(message)

fun isArrayType(type: KotlinType): Boolean {
    val name = type.nameIfStandardType?.identifier ?: return false
    return name.endsWith("Array")
}

fun getArrayType(type: KotlinType): KexArray {
    val name = type.nameIfStandardType?.identifier ?: throw IllegalStateException("Name must be not null")
    return when (name) {
        "IntArray" -> KexArray(KexInt)
        "CharArray" -> KexArray(KexChar)
        else -> throw IllegalArgumentException("Unknown Array type")
    }
}

fun KotlinType.toKexType() = when {
    isBoolean() -> KexBool
    isInt() -> KexInt
    isChar() -> KexChar
    isArrayType(this) -> getArrayType(this)
    else -> throw IllegalArgumentException("Unknown type")
}

fun TermFactory.reference(ref: KtNameReferenceExpression, context: BindingContext): Term {
    val target = context[BindingContext.REFERENCE_TARGET, ref] as? ValueDescriptor
            ?: throw IllegalArgumentException("Unknown reference target")
    val kexType = target.type.toKexType()
    return ReferenceTerm(ref, kexType)
}

fun TermFactory.funCall(ref: KtNamedFunction, context: BindingContext): Term {
    val parameterDescriptor = context[BindingContext.FUNCTION, ref]
            ?: throw IllegalArgumentException("Unknown parameter")
    val kexType = parameterDescriptor.returnType?.toKexType()
            ?: throw IllegalArgumentException("Function return type is null")
    return FunctionReferenceTerm(ref, kexType)
}

fun TermFactory.funParameter(ref: KtParameter, context: BindingContext): Term {
    val parameterDescriptor = context[BindingContext.VALUE_PARAMETER, ref]
            ?: throw IllegalArgumentException("Unknown parameter")
    val kexType = parameterDescriptor.type.toKexType()
    return ParameterTerm(ref, kexType)
}

fun Term.deepToString(): String = "($this [${this.subterms.map { it.deepToString() }}])"

fun Term.treeRepresentation(): String = when (this) {
    is BinaryTerm -> "$opcode"
    is CmpTerm -> "$opcode"
    else -> "${this.javaClass.simpleName} $this"
}

fun Term.printTermTree(offset: Int = 0): String = "${" ".repeat(offset)} | ${this.treeRepresentation()} \n" + subterms.joinToString("\n") { it.printTermTree(offset + 4) }

fun <T : Transformer<T>> Term.deepAccept(t: Transformer<T>) = t.transform(this).accept(t)

fun TermFactory.elementType(element: PsiElement) = when (element) {
    is KtNameReferenceExpression -> throw IllegalArgumentException("WTF????")
    else -> TypeTerm(element)
}

fun TermFactory.elementValue(element: PsiElement, context: BindingContext) = when (element) {
    is KtNameReferenceExpression -> {
        val targetDescriptor = context[BindingContext.REFERENCE_TARGET, element]
                ?: throw IllegalArgumentException("Unknown value reference target descriptor")
        when (targetDescriptor) {
            is PropertyDescriptor -> {
                val type = targetDescriptor.returnType
                        ?: throw IllegalArgumentException("No type for property $element")
                elementValue(element, type.toKexType())
            }
            else -> {
                val targetPsi = targetDescriptor.findPsi()
                        ?: throw IllegalArgumentException("Unknown value reference target")

                val targetValue = targetDescriptor as? ValueDescriptor
                        ?: throw IllegalArgumentException("Unknown value reference target value")
                val kexType = targetValue.type.toKexType()
                elementValue(targetPsi, kexType)
            }
        }
    }
    is KtNamedFunction -> {
        val descriptor = context[BindingContext.FUNCTION, element]
                ?: throw IllegalArgumentException("No descriptor for function declaration $element")
        val type = descriptor.returnType
                ?: throw IllegalArgumentException("No type for function declaration $element")
        elementValue(element, type.toKexType())
    }
    is KtDeclaration -> {
        val descriptor = context[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
                ?: throw IllegalArgumentException("No descriptor for declaration $element")
        val targetValue = descriptor as? ValueDescriptor
                ?: throw IllegalArgumentException("Unknown type info for element $descriptor")
        elementValue(element, targetValue.type.toKexType())
    }
    else -> throw IllegalArgumentException("Unexpected element $element")
}


fun <T : Term> Term.collectDescendantsOfType(predicate: (Term) -> Boolean): List<T> =
        subterms.flatMap { it.collectDescendantsOfType<T>(predicate) } + if (predicate(this)) listOf(this as T) else emptyList()

fun TermFactory.elementConstraint(element: PsiElement, bindingContext: BindingContext) =
        LiquidTypeInfoStorage[elementValue(element, bindingContext).typeElement]?.combineWithAnd() ?: getTrue()

fun TermFactory.elementValue(element: PsiElement, type: KexType) = TypeValueTerm(element, type)

fun TermFactory.implication(lhs: Term, rhs: Term) = getBinary(BinaryOpcode.Implies(), lhs, rhs)

fun PredicateFactory.getBool(term: Term) = getEquality(term, TermFactory.getTrue())

class EmptyTerm(val message: String) : Term(message, KexBool, emptyList()) {
    override fun <T : Transformer<T>> accept(t: Transformer<T>) = TODO()
    override fun print() = message
}

class FunctionReferenceTerm(val ref: KtNamedFunction, type: KexType) : Term("", type, emptyList()) {
    override fun <T : Transformer<T>> accept(t: Transformer<T>) = this
    override fun print() = "Call[${ref.name}]"
}

class ReferenceTerm(val ref: KtNameReferenceExpression, type: KexType) : Term("", type, emptyList()) {
    override fun <T : Transformer<T>> accept(t: Transformer<T>) = this
    override fun print() = "Reference[${ref.text}: $type]"
}

class ParameterTerm(val ref: KtParameter, type: KexType) : Term("", type, emptyList()) {
    override fun <T : Transformer<T>> accept(t: Transformer<T>) = this
    override fun print() = "Parameter[${ref.name}: $type]"
}

class TypeTerm(val typeElement: PsiElement) : Term("", KexBool, emptyList()) {
    override fun <T : Transformer<T>> accept(t: Transformer<T>) = this
    override fun print() = "TypeConstraint[$typeElement | ${typeElement.text}]"
}

class TypeValueTerm(val typeElement: PsiElement, type: KexType) : Term("", type, emptyList()) {
    override fun <T : Transformer<T>> accept(t: Transformer<T>) = this
    override fun print() = "ValueConstraint[$type | ${typeElement.text}]"
}

class BoolPredicate(val term: Term) : Predicate(PredicateType.Assume(), Location(), listOf(term)) {
    override fun <T : Transformer<T>> accept(t: Transformer<T>) = t.pf.getBool(t.transform(term))
    override fun print() = "Bool[${term.print()}]"
}

