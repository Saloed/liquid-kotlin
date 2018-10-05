import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode

private object TokenMapping {
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
}

fun CmpOpcode.fromKtToken(token: KtSingleValueToken) = TokenMapping.cmpTokenMap[token]
        ?: throw IllegalArgumentException("Unknown token type")

fun CmpOpcode.isCmpToken(token: KtSingleValueToken) = token in TokenMapping.cmpTokenMap

fun BinaryOpcode.fromKtToken(token: KtSingleValueToken) = TokenMapping.binaryTokenMap[token]
        ?: throw IllegalArgumentException("Unknown token type")

fun BinaryOpcode.isBinaryToken(token: KtSingleValueToken) = token in TokenMapping.binaryTokenMap
