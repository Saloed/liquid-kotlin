import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.js.descriptorUtils.getJetTypeFqName
import org.jetbrains.kotlin.js.descriptorUtils.nameIfStandardType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.types.DeferredType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.checker.isClassType
import org.jetbrains.kotlin.types.typeUtil.isBoolean
import org.jetbrains.kotlin.types.typeUtil.isChar
import org.jetbrains.kotlin.types.typeUtil.isInt
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.*
import org.jetbrains.research.kex.state.term.*
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.CM
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode
import org.jetbrains.research.kfg.ir.value.instruction.UnaryOpcode

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

fun getClassType(type: SimpleType) = KexClass(CM.getByName(type.getJetTypeFqName(false)))

fun getSimpleType(type: SimpleType) = when {
    type.isClassType -> getClassType(type)
    else -> throw IllegalArgumentException("Unknown type $type")
}

fun KexType.makeReference() = KexReference(this)

fun KotlinType.toKexType(): KexType = when {
    isBoolean() -> KexBool
    isInt() -> KexInt
    isChar() -> KexChar
//    isArrayType(this) -> getArrayType(this)
    this is SimpleType -> getSimpleType(this)
    this is DeferredType -> unwrap().toKexType()
    else -> throw IllegalArgumentException("Unknown type $this")
}

fun Term.deepToString(): String = "($this [${this.subterms.map { it.deepToString() }}])"

fun Term.treeRepresentation(): String = when (this) {
    is BinaryTerm -> "$opcode"
    is CmpTerm -> "$opcode"
    else -> "${this.javaClass.simpleName} $this"
}

fun Term.printTermTree(offset: Int = 0): String = "${" ".repeat(offset)} | ${this.treeRepresentation()} \n" + subterms.joinToString("\n") { it.printTermTree(offset + 4) }

fun <T : Transformer<T>> Term.deepAccept(t: Transformer<T>) = t.transform(this).accept(t)


fun TermFactory.getIf(cond: Term, thenExpr: Term, elseExpr: Term): Term {
    if (cond.type !is KexBool) throw IllegalArgumentException("If condition must be KexBool but $cond")
    if (thenExpr.type != elseExpr.type) throw IllegalArgumentException("If branches must type equal but ($thenExpr) and ($elseExpr)")
    return IfTerm(thenExpr.type, cond, thenExpr, elseExpr)
}

fun UnaryOpcode.Companion.fromOperationToken(token: IElementType) = when (token) {
    KtTokens.MINUS -> UnaryOpcode.NEG
    else -> throw IllegalArgumentException("Not unary operation: $token")
}

fun Predicate.asTerm() = when (this) {
    is EqualityPredicate -> TermFactory.getCmp(CmpOpcode.Eq(), lhv, rhv)
    is InequalityPredicate -> TermFactory.getCmp(CmpOpcode.Neq(), lhv, rhv)
    is ImplicationPredicate -> TermFactory.implication(lhv, rhv)
    else -> throw IllegalArgumentException("No conversion from predicate $this of type ${this::class.simpleName} to Term")
}

fun <T : Term> Term.collectDescendantsOfType(predicate: (Term) -> Boolean): List<T> =
        subterms.flatMap { it.collectDescendantsOfType<T>(predicate) } + if (predicate(this)) listOf(this as T) else emptyList()


fun TermFactory.implication(lhs: Term, rhs: Term) = getBinary(BinaryOpcode.Implies(), lhs, rhs)


fun PredicateFactory.getBool(term: Term) = getEquality(term, TermFactory.getTrue())

fun List<Predicate>.collectToPredicateState(): PredicateState = BasicState(this)


fun Term.toPredicateState(): PredicateState = BasicState(listOf(PredicateFactory.getBool(this)))
fun List<Term>.toPredicateState(): PredicateState = BasicState(this.map { PredicateFactory.getBool(it) })

fun List<Term>.combineWith(combiner: (Term, Term) -> Term) = when (size) {
    0 -> throw IllegalStateException("Empty constraint")
    1 -> first()
    2 -> combiner(this[0], this[1])
    else -> asSequence().drop(1).fold(this[0]) { acc: Term, localTerm: Term ->
        combiner(acc, localTerm)
    }
}

fun List<Term>.combineWithAnd() = combineWith { lhs, rhs ->
    TermFactory.getBinary(KexBool, BinaryOpcode.And(), lhs, rhs)
}

fun List<Term>.combineWithOr() = combineWith { lhs, rhs ->
    TermFactory.getBinary(KexBool, BinaryOpcode.Or(), lhs, rhs)
}
