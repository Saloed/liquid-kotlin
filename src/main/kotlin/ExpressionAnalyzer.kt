import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCallWithAssert
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.TermFactory

abstract class ExpressionAnalyzer<T : KtExpression>(val expression: T) {
    protected lateinit var bindingContext: BindingContext
    protected lateinit var analysisTrace: List<KtExpression>
    abstract fun analyze(): LiquidType

    protected fun subExprAnalyzer(expression: KtExpression) = expression.LqTAnalyzer(bindingContext, analysisTrace + listOf(this.expression))

    companion object {
        fun analyzerForExpression(expression: KtExpression, bindingContext: BindingContext, analysisTrace: List<KtExpression>) = when (expression) {
            is KtConstantExpression -> ConstantExpressionAnalyzer(expression)
            is KtBinaryExpression -> BinaryExpressionAnalyzer(expression)
            is KtNameReferenceExpression -> NameReferenceExpressionAnalyzer(expression)
            is KtIfExpression -> IfExpressionAnalyzer(expression)
            is KtCallExpression -> CallExpressionAnalyzer(expression)
            is KtDotQualifiedExpression -> DotQualifiedExpressionAnalyzer(expression)
            is KtArrayAccessExpression -> ArrayAccessExpressionAnalyzer(expression)
            is KtFunction -> FunctionAnalyzer(expression)
            is KtDeclaration -> DeclarationAnalyzer(expression)
            else -> null
        }.apply {
            this?.bindingContext = bindingContext
            this?.analysisTrace = analysisTrace
        }
    }
}

class ConstantExpressionAnalyzer(expression: KtConstantExpression) : ExpressionAnalyzer<KtConstantExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo
        val value = TermFactory.fromConstant(expression.node.elementType, expression.text)
        val constraint = PredicateFactory.getEquality(typeInfo.variable, value)
        typeInfo.predicate = constraint
        return typeInfo
    }
}

class BinaryExpressionAnalyzer(expression: KtBinaryExpression) : ExpressionAnalyzer<KtBinaryExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo
        val lOperand = expression.left ?: throw IllegalArgumentException("Left operand undefined")
        val rOperand = expression.right ?: throw IllegalArgumentException("Right operand undefined")
        val operation = expression.operationToken
        val leftValue = subExprAnalyzer(lOperand).analyze()
        val rightValue = subExprAnalyzer(rOperand).analyze()
        val value = TermFactory.binaryTermForOpToken(operation, leftValue.variable, rightValue.variable)
        val constraint = PredicateFactory.getEquality(typeInfo.variable, value)
        typeInfo.predicate = constraint
        typeInfo.dependsOn.addAll(listOf(leftValue, rightValue))
        return typeInfo
    }
}

class NameReferenceExpressionAnalyzer(expression: KtNameReferenceExpression) : ExpressionAnalyzer<KtNameReferenceExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo
        val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression]
                ?: throw IllegalArgumentException("Unknown value reference target descriptor")
        val targetPsi = targetDescriptor.findPsi()
                ?: throw IllegalArgumentException("Unknown value reference target")
        val targetTypeInfo = NewLQTInfo.getOrException(targetPsi)
        val constraint = PredicateFactory.getEquality(typeInfo.variable, targetTypeInfo.variable)
        typeInfo.predicate = constraint
        typeInfo.dependsOn.add(targetTypeInfo)
        return typeInfo
    }
}


class IfExpressionAnalyzer(expression: KtIfExpression) : ExpressionAnalyzer<KtIfExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val cond = expression.condition ?: throw IllegalArgumentException("If without condition")
        val thenExpr = expression.getThen()
        val elseExpr = expression.getElse()


        if (thenExpr == null && elseExpr == null) throw IllegalArgumentException("If branches are null")
        if (thenExpr == null || elseExpr == null) throw NotImplementedError("Both branches of if must exists for now")


        val thenBranch = subExprAnalyzer(thenExpr).analyze()
        val elseBranch = subExprAnalyzer(elseExpr).analyze()
        val condTerm = subExprAnalyzer(cond).analyze()

        val value = TermFactory.getIf(condTerm.variable, thenBranch.variable, elseBranch.variable)
        val constraint = PredicateFactory.getEquality(typeInfo.variable, value)
        typeInfo.predicate = constraint
        typeInfo.dependsOn.addAll(listOf(condTerm, thenBranch, elseBranch))
        return typeInfo
    }
}


class CallExpressionAnalyzer(expression: KtCallExpression) : ExpressionAnalyzer<KtCallExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        if (expression in analysisTrace) {
            return typeInfo
        }

        val resolvedCall = expression.getCall(bindingContext)?.getResolvedCallWithAssert(bindingContext)
                ?: throw IllegalArgumentException("Function not found")

        val callArgumentsExpr = resolvedCall.valueArguments
                .map { it.value }
                .map { it.arguments.mapNotNull { it.getArgumentExpression() } }
                .map {
                    if (it.size == 1) it else throw IllegalStateException("Error in arguments $it")
                }
                .map { it.first() }
                .map {
                    subExprAnalyzer(it).analyze()
                }

        val funElement = resolvedCall.candidateDescriptor.findPsi()
                ?: throw IllegalArgumentException("Function PSI not found")

        val funVariable = if (funElement is KtFunction) {
            subExprAnalyzer(funElement).analyze()
        } else throw NotImplementedError("No function calls not implemented")

        val constraint = PredicateFactory.getEquality(typeInfo.variable, funVariable.variable)
        typeInfo.predicate = constraint
        typeInfo.dependsOn.add(funVariable)
        typeInfo.dependsOn.addAll(callArgumentsExpr)
        return typeInfo
    }
}

class FunctionAnalyzer(expression: KtFunction) : ExpressionAnalyzer<KtFunction>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        if (!expression.hasBody()) throw IllegalArgumentException("Function without body: ${expression.text}")
        if (expression.hasBlockBody()) throw NotImplementedError("Block body is unsupported yet")

        val body = expression.bodyExpression ?: throw IllegalStateException("WTF?")
        val value = subExprAnalyzer(body).analyze()
        val constraint = PredicateFactory.getEquality(typeInfo.variable, value.variable)
        typeInfo.predicate = constraint
        typeInfo.dependsOn.add(value)
        return typeInfo
    }
}

class DotQualifiedExpressionAnalyzer(expression: KtDotQualifiedExpression) : ExpressionAnalyzer<KtDotQualifiedExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)

        //todo: implement

        return typeInfo
    }
}


class ArrayAccessExpressionAnalyzer(expression: KtArrayAccessExpression) : ExpressionAnalyzer<KtArrayAccessExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)

        //todo: implement

        return typeInfo
    }
}


class DeclarationAnalyzer(expression: KtDeclaration) : ExpressionAnalyzer<KtDeclaration>(expression) {
    override fun analyze(): LiquidType = NewLQTInfo.getOrException(expression)
}

fun getExpressionAnalyzer(expression: KtExpression, bindingContext: BindingContext, analysisTrace: List<KtExpression>) =
        ExpressionAnalyzer.analyzerForExpression(expression, bindingContext, analysisTrace)
                ?: throw NotImplementedError("Aalysis for $expression not implemented yet")


fun hasExpressionAnalyzer(expression: KtExpression) =
        ExpressionAnalyzer.analyzerForExpression(expression, BindingContext.EMPTY, emptyList()) != null

fun KtExpression.LqTAnalyzer(bindingContext: BindingContext, analysisTrace: List<KtExpression> = emptyList()) =
        getExpressionAnalyzer(this, bindingContext, analysisTrace)
