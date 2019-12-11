import ClassInfo.typeInfo
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.js.descriptorUtils.getJetTypeFqName
import org.jetbrains.kotlin.js.descriptorUtils.nameIfStandardType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.resolve.constants.*
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.isClassType
import org.jetbrains.kotlin.types.typeUtil.isBoolean
import org.jetbrains.kotlin.types.typeUtil.isChar
import org.jetbrains.kotlin.types.typeUtil.isInt
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.ChoiceState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.predicate.PredicateType
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode
import org.jetbrains.research.kfg.ir.value.instruction.UnaryOpcode
import java.util.*

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
    return getBinary(left.type, binaryToken, left, right)
}


fun TermFactory.binaryTermForOpToken(token: IElementType, left: Term, right: Term) = when {
    token.isBinaryOperationToken() -> getBinary(left.type, TokenMapping.binaryFromKtToken(token), left, right)
    token.isCmpToken() -> getCmp(TokenMapping.cmpFromKtToken(token), left, right)
    else -> throw IllegalArgumentException("Unknown operation type")
}

fun TermFactory.fromConstant(value: ConstantValue<*>) = when (value) {
    is BooleanValue -> getBool(value.value)
    is ByteValue -> getByte(value.value)
    is DoubleValue -> getDouble(value.value)
    is FloatValue -> getFloat(value.value)
    is IntValue -> getInt(value.value)
    is LongValue -> getLong(value.value)
    is NullValue -> getNull()
    is ShortValue -> getShort(value.value)
    is StringValue -> getString(value.value)
    else -> throw IllegalArgumentException("Constant type is unknown: $value")
}

fun TermFactory.fromConstant(type: IElementType, value: String) = when (type) {
    KtNodeTypes.BOOLEAN_CONSTANT -> getBool(value.toBoolean())
    KtNodeTypes.INTEGER_CONSTANT -> getInt(value.toInt())
    KtNodeTypes.STRING_TEMPLATE -> getString(value)
    else -> throw IllegalArgumentException("Unknown constant type")
}

fun isArrayType(type: KotlinType): Boolean {
    val name = type.nameIfStandardType?.identifier ?: return false
    return name.endsWith("Array")
}

fun getArrayType(type: KotlinType): KexArray {
    val name = type.nameIfStandardType?.identifier
            ?: throw IllegalStateException("Name must be not null")
    return when (name) {
        "IntArray" -> KexArray(KexInt())
        "CharArray" -> KexArray(KexChar())
        else -> throw IllegalArgumentException("Unknown Array type")
    }
}

fun getClassType(type: SimpleType): KexClass {
    val typeName = type.getJetTypeFqName(false).replace('.', '/')
    val ktName = JavaTypeConverter.javaToKotlin(typeName)
    val name = ktName ?: typeName
    return KexClass(name).also {
        it.typeInfo = type
    }
}

fun getTypeParameter(type: SimpleType): KexType {
    val descriptor = TypeUtils.getTypeParameterDescriptorOrNull(type)
            ?: throw IllegalArgumentException("Type parameter descriptor is unknown")
    return descriptor.upperBounds.first().toKexType()
}

fun getSimpleType(type: SimpleType) = when {
    type.isClassType -> getClassType(type)
    type.isTypeParameter() -> getTypeParameter(type)
    else -> throw IllegalArgumentException("Unknown type $type")
}

fun KexType.makeReference() = KexReference(this)

fun KotlinType.toKexType(): KexType = when {
    isBoolean() -> KexBool()
    isInt() -> KexInt()
    isChar() -> KexChar()
//    isArrayType(this) -> getArrayType(this)
    this is SimpleType -> getSimpleType(this)
    this is DeferredType -> unwrap().toKexType()
    this is FlexibleType -> unwrap().lowerIfFlexible().toKexType()
    else -> throw IllegalArgumentException("Unknown type $this")
}


fun PredicateFactory.getIf(cond: Term, thenExpr: Term, elseExpr: Term, result: Term): PredicateState {
    val trueState = BasicState(listOf(
            getEquality(cond, TermFactory.getTrue(), PredicateType.Path()),
            getEquality(result, thenExpr)
    ))

    val falseState = BasicState(listOf(
            getEquality(cond, TermFactory.getFalse(), PredicateType.Path()),
            getEquality(result, elseExpr)
    ))

    return ChoiceState(listOf(trueState, falseState))
}


fun unaryOpcodeFromOperationToken(token: IElementType) = when (token) {
    KtTokens.MINUS -> UnaryOpcode.NEG
    else -> throw IllegalArgumentException("Not unary operation: $token")
}

fun <T : Term> Term.collectDescendantsOfType(predicate: (Term) -> Boolean): List<T> =
        subterms.flatMap { it.collectDescendantsOfType<T>(predicate) } + if (predicate(this)) listOf(this as T) else emptyList()


fun PredicateFactory.getBool(term: Term) = getEquality(term, TermFactory.getTrue())

fun List<Term>.combineWith(combiner: (Term, Term) -> Term) = when (size) {
    0 -> TermFactory.getTrue()//throw IllegalStateException("Empty constraint")
    1 -> first()
    2 -> combiner(this[0], this[1])
    else -> asSequence().drop(1).fold(this[0]) { acc: Term, localTerm: Term ->
        combiner(acc, localTerm)
    }
}

fun List<Term>.combineWithAnd() = combineWith { lhs, rhs ->
    TermFactory.getBinary(KexBool(), BinaryOpcode.And(), lhs, rhs)
}

fun List<Term>.combineWithOr() = combineWith { lhs, rhs ->
    TermFactory.getBinary(KexBool(), BinaryOpcode.Or(), lhs, rhs)
}

object TermDebug {
    val termInfo = IdentityHashMap<Term, String>()
}

var Term.debugInfo: String
    get() = TermDebug.termInfo[this] ?: ""
    set(value) {
        TermDebug.termInfo[this] = value
    }
