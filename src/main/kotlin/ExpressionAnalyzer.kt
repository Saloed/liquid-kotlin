import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.idea.core.copied
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCallWithAssert
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.research.kex.ktype.KexBool
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kex.state.term.Term


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
            is KtConstructorCalleeExpression -> CallAnalyzer(expression)
            is KtCallExpression -> CallAnalyzer(expression)
            is KtDotQualifiedExpression -> DotQualifiedExpressionAnalyzer(expression)
            is KtArrayAccessExpression -> ArrayAccessExpressionAnalyzer(expression)
            is KtConstructor<*> -> ConstructorAnalyzer(expression)
            is KtFunction -> FunctionAnalyzer(expression)
            is KtDeclaration -> DeclarationAnalyzer(expression)
            is KtStringTemplateExpression -> StringTemplateAnalyzer(expression)
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


class CallAnalyzer(expression: KtExpression) : ExpressionAnalyzer<KtExpression>(expression) {

    data class CallArgumentsInfo(
            val callExpr: LiquidType,
            val substitutionTerm: Term,
            val arguments: List<LiquidType>,
            val parameters: List<LiquidType>
    )

    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val resolvedCall = expression.getCall(bindingContext)?.getResolvedCallWithAssert(bindingContext)
                ?: throw IllegalArgumentException("Function not found")

        return when (resolvedCall.candidateDescriptor) {
            is SimpleFunctionDescriptor -> analyzeFunctionCall(resolvedCall as ResolvedCall<SimpleFunctionDescriptor>)
            is ConstructorDescriptor -> analyzeConstructorCall(resolvedCall as ResolvedCall<ConstructorDescriptor>)
            else -> throw  NotImplementedError("Unknown call expression type $resolvedCall")
        }
    }

    private fun analyzeCallArguments(resolvedCall: ResolvedCall<*>): CallArgumentsInfo {
        val element = resolvedCall.candidateDescriptor.findPsi().safeAs<KtExpression>()
                ?: throw IllegalArgumentException("Function PSI not found")

        val typeInfo = subExprAnalyzer(element).analyze()

        val callArgumentsExpr = resolvedCall.valueArguments.toList()
        val arguments = callArgumentsExpr
                .map { it.second }
                .map { it.arguments.mapNotNull { it.getArgumentExpression() } }
                .map {
                    if (it.size == 1) it else throw IllegalStateException("Error in arguments $it")
                }
                .map { it.first() }
                .map {
                    subExprAnalyzer(it).analyze()
                }
        val parameters = callArgumentsExpr
                .map { it.first }
                .map {
                    it.findPsi().safeAs<KtExpression>() ?: throw IllegalArgumentException("Parameter $it PSI not found")
                }
                .map {
                    subExprAnalyzer(it).analyze()
                }

        val substitution = arguments.zip(parameters)
                .mapFirst { it.variable }
                .mapSecond { it.variable }
                .map { TermFactory.equalityTerm(it.second, it.first) }
                .combineWithAnd()

        return CallArgumentsInfo(typeInfo, substitution, arguments, parameters)
    }

    private fun analyzeFunctionCall(resolvedCall: ResolvedCall<SimpleFunctionDescriptor>): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        if (expression in analysisTrace) {
            return typeInfo
        }

        val callConstraints = analyzeCallArguments(resolvedCall)

        val callResult = PredicateFactory.getEquality(typeInfo.variable, callConstraints.callExpr.variable)
        val callWithArguments = PredicateFactory.getBool(callConstraints.substitutionTerm)

        val callTypeInfo = CallExpressionLiquidType(
                typeInfo.expression,
                typeInfo.type,
                typeInfo.variable,
                callConstraints.parameters,
                callConstraints.arguments
        )
        callTypeInfo.predicates.add(callResult)
        callTypeInfo.predicates.add(callWithArguments)
        callTypeInfo.dependsOn.add(callConstraints.callExpr)

        NewLQTInfo.typeInfo[expression] = callTypeInfo
        return callTypeInfo
    }

    private fun analyzeConstructorCall(resolvedCall: ResolvedCall<ConstructorDescriptor>): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val callConstraints = analyzeCallArguments(resolvedCall)

        val callTypeInfo = CallExpressionLiquidType(
                typeInfo.expression,
                typeInfo.type,
                typeInfo.variable,
                callConstraints.parameters,
                callConstraints.arguments
        )

        val objectPredicate = PredicateFactory.getNew(callTypeInfo.variable)
        callTypeInfo.predicates.add(objectPredicate)
        callTypeInfo.predicates.add(PredicateFactory.getBool(callConstraints.substitutionTerm))

        val constructor = resolvedCall.candidateDescriptor
        val constructorParameters = constructor.valueParameters

        for (field in constructorParameters) {
            val psi = field.findPsi() ?: throw IllegalArgumentException("No PSI for field")
            val parameterLqt = NewLQTInfo.getOrException(psi)
            val fieldTerm = TermFactory.getField(
                    parameterLqt.type.makeReference(),
                    callTypeInfo.variable,
                    TermFactory.getString(field.name.identifier)
            )
            val predicate = PredicateFactory.getFieldStore(fieldTerm, parameterLqt.variable)
            val trickyHack = parameterLqt.expression.copied()
            val lqt = LiquidType(trickyHack, KexBool, fieldTerm, arrayListOf(parameterLqt))
            lqt.predicate = predicate
            callTypeInfo.dependsOn.add(lqt)
            NewLQTInfo.typeInfo[trickyHack] = lqt
        }

        NewLQTInfo.typeInfo[expression] = callTypeInfo

        return callTypeInfo
    }

}

class FunctionAnalyzer(expression: KtFunction) : ExpressionAnalyzer<KtFunction>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        if (!expression.hasBody()) throw IllegalArgumentException("Function without body: ${expression.text}")
        if (expression.hasBlockBody()) throw NotImplementedError("Block body is unsupported yet")
        val body = expression.bodyExpression ?: throw IllegalStateException("WTF?")

        val dependencies = expression.valueParameters.map {
            subExprAnalyzer(it).analyze()
        }

        val value = subExprAnalyzer(body).analyze()
        val constraint = PredicateFactory.getEquality(typeInfo.variable, value.variable)
        typeInfo.predicate = constraint
        typeInfo.dependsOn.add(value)
        typeInfo.dependsOn.addAll(dependencies)
        return typeInfo
    }
}


class ConstructorAnalyzer(expression: KtConstructor<*>) : ExpressionAnalyzer<KtConstructor<*>>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val dependencies = expression.getValueParameters().map {
            subExprAnalyzer(it).analyze()
        }
        typeInfo.predicate = PredicateFactory.getBool(TermFactory.getTrue())
        typeInfo.dependsOn.addAll(dependencies)
        return typeInfo
    }

}

class DotQualifiedExpressionAnalyzer(expression: KtDotQualifiedExpression) : ExpressionAnalyzer<KtDotQualifiedExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val receiver = subExprAnalyzer(expression.receiverExpression).analyze()
        val selectorExpr = expression.selectorExpression
                ?: throw IllegalArgumentException("Selector expression expected")

        if (selectorExpr !is KtNameReferenceExpression)
            throw NotImplementedError("Only name reference selectors are supported")

        val fieldName = selectorExpr.getReferencedName()
        val fieldTerm = TermFactory.getField(
                typeInfo.type.makeReference(),
                receiver.variable,
                TermFactory.getString(fieldName)
        )
        val fieldValue = TermFactory.getFieldLoad(typeInfo.type, fieldTerm)
        val constraint = PredicateFactory.getEquality(typeInfo.variable, fieldValue)
        typeInfo.predicate = constraint
        typeInfo.dependsOn.add(receiver)
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

class StringTemplateAnalyzer(expression: KtStringTemplateExpression) : ExpressionAnalyzer<KtStringTemplateExpression>(expression) {
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
