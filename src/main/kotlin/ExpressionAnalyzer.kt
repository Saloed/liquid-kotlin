import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.cfg.pseudocode.Pseudocode
import org.jetbrains.kotlin.cfg.pseudocode.PseudocodeUtil
import org.jetbrains.kotlin.cfg.pseudocode.instructions.Instruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.jumps.ConditionalJumpInstruction
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.core.copied
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.isConstant
import org.jetbrains.kotlin.idea.search.usagesSearch.descriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.getTargetFunction
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCallWithAssert
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.research.kex.ktype.KexBool
import org.jetbrains.research.kex.ktype.KexVoid
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kfg.ir.value.instruction.UnaryOpcode


typealias AnalysisTrace = Trace<PsiElement>

abstract class ExpressionAnalyzer<T : KtExpression>(val expression: T) {
    private var functionDispatchReceiver: LiquidType? = null
    private var functionExtensionReceiver: LiquidType? = null

    var cfg: Pseudocode? = null

    protected lateinit var bindingContext: BindingContext
    protected lateinit var analysisTrace: AnalysisTrace

    var annotationInfo: AnnotationInfo? = null

    abstract fun analyze(): LiquidType

    protected fun subExprAnalyzer(expression: KtExpression) = expression.LqTAnalyzer(bindingContext, analysisTrace + this.expression)
            .also { it.functionDispatchReceiver = this.functionDispatchReceiver }
            .also { it.functionExtensionReceiver = this.functionExtensionReceiver }
            .also { it.annotationInfo = this.annotationInfo }
            .also { it.cfg = this.cfg }

    protected fun functionSubExprAnalyzer(
            expression: KtExpression,
            dispatch: LiquidType?,
            extension: LiquidType?,
            pseudocode: Pseudocode
    ) = subExprAnalyzer(expression)
            .apply {
                functionDispatchReceiver = dispatch
                functionExtensionReceiver = extension
                cfg = pseudocode
            }

    internal fun getLiquidTypeForReceiverDescriptor(receiver: ReceiverParameterDescriptor): LiquidType {
        val psi = receiver.findPsiWithProxy().safeAs<KtExpression>() ?: run {
            val factory = KtPsiFactory(expression, false)
            val expr = factory.createStringTemplate(receiver.name.asString())
            PsiProxy.storage[receiver] = expr
            expr
        }
        return LiquidType.create(psi, receiver.type)
    }


    internal fun getLiquidTypeForReceiverValue(receiver: ReceiverValue) = when (receiver) {
        is ExpressionReceiver -> subExprAnalyzer(receiver.expression).analyze()
        is ImplicitReceiver -> functionDispatchReceiver
                ?: throw IllegalArgumentException("Implicit receiver is not present")
        else -> throw NotImplementedError("Receiver is not supported: $receiver")
    }

    companion object {
        fun analyzerForExpression(expression: KtExpression, bindingContext: BindingContext, analysisTrace: AnalysisTrace) = (
                if (expression.isConstant())
                    CompileTimeConstantAnalyzer(expression)
                else when (expression) {
                    is KtConstantExpression -> ConstantExpressionAnalyzer(expression)
                    is KtBinaryExpression -> BinaryExpressionAnalyzer(expression)
                    is KtNameReferenceExpression -> NameReferenceExpressionAnalyzer(expression)
                    is KtIfExpression -> IfExpressionAnalyzer(expression)
                    is KtParenthesizedExpression -> ParenthesizedAnalyzer(expression)

                    is KtWhileExpression -> SkipAnalyzer(expression)
                    is KtDoWhileExpression -> SkipAnalyzer(expression)
                    is KtForExpression -> SkipAnalyzer(expression)
                    is KtBreakExpression -> SkipAnalyzer(expression)
                    is KtContinueExpression -> SkipAnalyzer(expression)

                    is KtConstructorCalleeExpression -> CallAnalyzer(expression)
                    is KtCallExpression -> CallAnalyzer(expression)
                    is KtDotQualifiedExpression -> DotQualifiedExpressionAnalyzer(expression)
                    is KtSafeQualifiedExpression -> SafeQualifiedExpressionAnalyzer(expression)
                    is KtArrayAccessExpression -> ArrayAccessExpressionAnalyzer(expression)
                    is KtConstructor<*> -> ConstructorAnalyzer(expression)
                    is KtFunction -> FunctionAnalyzer(expression)
                    is KtDeclaration -> DeclarationAnalyzer(expression)
                    is KtStringTemplateExpression -> StringTemplateAnalyzer(expression)
                    is KtUnaryExpression -> UnaryExpressionAnalyzer(expression)
                    is KtBlockExpression -> BlockExpressionAnalyzer(expression)
                    is KtReturnExpression -> ReturnAnalyzer(expression)
                    else -> null
                }).apply {
            this?.bindingContext = bindingContext
            this?.analysisTrace = analysisTrace
        }
    }
}

class CompileTimeConstantAnalyzer(expression: KtExpression) : ExpressionAnalyzer<KtExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val constTypeInfo = typeInfo.safeAs<ConstantLiquidType>()
                ?: throw IllegalArgumentException("Not a constant: ${expression.text}")

        val value = ConstantExpressionEvaluator.getConstant(expression, bindingContext)
                ?.toConstantValue(constTypeInfo.ktType)
                ?: throw IllegalArgumentException("Constant value expected for $expression")

        val valueTerm = TermFactory.fromConstant(value)
        val constraint = PredicateFactory.getEquality(typeInfo.variable, valueTerm)
        typeInfo.addPredicate(constraint)
        return typeInfo
    }

}

class ConstantExpressionAnalyzer(expression: KtConstantExpression) : ExpressionAnalyzer<KtConstantExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo
        val value = TermFactory.fromConstant(expression.node.elementType, expression.text)
        val constraint = PredicateFactory.getEquality(typeInfo.variable, value)
        typeInfo.addPredicate(constraint)
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
        val operationReference = expression.operationReference //todo: resolve operation with reference
        val leftValue = subExprAnalyzer(lOperand).analyze()
        val rightValue = subExprAnalyzer(rOperand).analyze()
        val value = TermFactory.binaryTermForOpToken(operation, leftValue.variable, rightValue.variable)
        val constraint = PredicateFactory.getEquality(typeInfo.variable, value)
        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.addAll(listOf(leftValue, rightValue))
        return typeInfo
    }
}

class NameReferenceExpressionAnalyzer(expression: KtNameReferenceExpression) : ExpressionAnalyzer<KtNameReferenceExpression>(expression) {

    private fun checkDeclarationReferenceInsideDeclarationContext(expression: KtExpression, target: PsiElement): Boolean {
        val annotation = annotationInfo ?: return false
        if (target !is KtDeclaration) return false
        annotation.expression.findDescendantOfType<KtExpression> { it == expression } ?: return false
        val targetCtx = target.context ?: return false
        val annotationCtx = annotation.declaration.context ?: return false
        return targetCtx == annotationCtx
    }

    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo
        val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression]
                ?: throw IllegalArgumentException("Unknown value reference target descriptor")

        val targetPsi = targetDescriptor.findPsiWithProxy()
                ?: throw IllegalArgumentException("Unknown value reference target")

        val isDeclarationInternal = checkDeclarationReferenceInsideDeclarationContext(expression, targetPsi)

        if (!isDeclarationInternal && targetDescriptor is PropertyDescriptor) {
            val getter = targetDescriptor.getter
                    ?: throw IllegalArgumentException("Try to get field without getter: ${expression.text}")
            val resolvedCall = expression.getCall(bindingContext)?.getResolvedCallWithAssert(bindingContext)
                    ?: throw IllegalArgumentException("Getter function not found")

            val dispatchValue = resolvedCall.dispatchReceiver?.let { getLiquidTypeForReceiverValue(it) }
            val extensionValue = resolvedCall.extensionReceiver?.let { getLiquidTypeForReceiverValue(it) }

            if (dispatchValue != null && extensionValue != null)
                throw NotImplementedError("No support for so complicated properties: ${expression.text}")
            if (getter.hasBody())
                throw NotImplementedError("Getters with body are not supported: ${expression.text}")

            val receiver = dispatchValue ?: extensionValue
            ?: throw IllegalArgumentException("Dispatch and extension are null for property getter: ${expression.text}")

            val fieldName = expression.getReferencedName()
            val fieldTerm = TermFactory.getField(
                    typeInfo.type.makeReference(),
                    receiver.variable,
                    TermFactory.getString(fieldName)
            )
            val fieldValue = TermFactory.getFieldLoad(typeInfo.type, fieldTerm)
            val constraint = PredicateFactory.getEquality(typeInfo.variable, fieldValue)

            typeInfo.addPredicate(constraint)
            typeInfo.dependsOn.add(receiver)

        } else {
            val targetTypeInfo = if (targetPsi is KtExpression)
                subExprAnalyzer(targetPsi).analyze()
            else
                NewLQTInfo.typeInfo[targetPsi]
                        ?: throw IllegalArgumentException("No liquid type for reference target: $targetPsi")
            val constraint = PredicateFactory.getEquality(typeInfo.variable, targetTypeInfo.variable)
            typeInfo.addPredicate(constraint)
            typeInfo.dependsOn.add(targetTypeInfo)
        }
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
        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.addAll(listOf(condTerm, thenBranch, elseBranch))
        return typeInfo
    }
}


class CallAnalyzer(expression: KtExpression) : ExpressionAnalyzer<KtExpression>(expression) {

    data class CallArgumentsInfo(
            val callExpr: FunctionLiquidType,
            val substitutionTerm: Term,
            val arguments: Map<String, LiquidType>,
            val dispatchReceiver: LiquidType?,
            val extensionReceiver: LiquidType?
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
        val element = resolvedCall.candidateDescriptor.findPsiWithProxy()
                ?: throw IllegalArgumentException("Function PSI not found")

        val typeInfo = (
                if (element is KtExpression) subExprAnalyzer(element).analyze() else NewLQTInfo.typeInfo[element]
                ).safeAs<FunctionLiquidType>()
                ?: throw IllegalStateException("Not functional call $resolvedCall")

        val callArgumentsExpr = resolvedCall.valueArguments.toList()
        val dispatch = resolvedCall.dispatchReceiver?.let { getLiquidTypeForReceiverValue(it) }
        val extension = resolvedCall.extensionReceiver?.let { getLiquidTypeForReceiverValue(it) }

        val arguments = callArgumentsExpr
                .map { it.first.name.asString() to it.second.arguments }
                .mapSecond { it.mapNotNull { it.getArgumentExpression() } }
                .mapSecond {
                    if (it.size == 1) it.first() else throw IllegalStateException("Error in arguments $it")
                }
                .mapSecond {
                    subExprAnalyzer(it).analyze()
                }.toList()

        val argumentsMap = arguments.toMap()
        val substitutedArguments = mutableSetOf<LiquidType>()

        val substitution = typeInfo.arguments.toList().mapIndexed { idx, (name, arg) ->
            val argByName = argumentsMap[name]
            val argByIndex = arguments[idx].second
            val argument = argByName ?: argByIndex ?: throw IllegalArgumentException(
                    "Argument is not provided for parameter: $name | ${typeInfo.expression.text}"
            )
            if (argument in substitutedArguments)
                throw IllegalStateException("Argument already substituted: $name | ${typeInfo.expression.text}")
            substitutedArguments.add(arg)
            TermFactory.equalityTerm(arg.variable, argument.variable)
        }

        val dispatchSubstitution = typeInfo.dispatchArgument?.let {
            val dispatchArgument = dispatch ?: throw IllegalArgumentException(
                    "Function require dispatch argument: ${typeInfo.expression.text} at call ${expression.text}"
            )
            TermFactory.equalityTerm(it.variable, dispatchArgument.variable)
        }

        val extensionSubstitution = typeInfo.extensionArgument?.let {
            val extensionArgument = dispatch ?: throw IllegalArgumentException(
                    "Function require extension argument: ${typeInfo.expression.text} at call ${expression.text}"
            )
            TermFactory.equalityTerm(typeInfo.extensionArgument.variable, extensionArgument.variable)
        }

        val substitutionTerm = (
                substitution + emptyListIfNull(dispatchSubstitution) + emptyListIfNull(extensionSubstitution)
                ).combineWithAnd()
        return CallArgumentsInfo(typeInfo, substitutionTerm, argumentsMap, dispatch, extension)
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
                expression,
                typeInfo.type,
                typeInfo.variable,
                callConstraints.dispatchReceiver,
                callConstraints.extensionReceiver,
                callConstraints.arguments,
                callConstraints.callExpr
        )
        val ps = BasicState(listOf(callResult, callWithArguments))
        callTypeInfo.addPredicate(ps)

        NewLQTInfo.typeInfo[expression] = callTypeInfo
        return callTypeInfo
    }

    private fun analyzeConstructorCall(resolvedCall: ResolvedCall<ConstructorDescriptor>): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val callConstraints = analyzeCallArguments(resolvedCall)

        val callTypeInfo = CallExpressionLiquidType(
                expression,
                typeInfo.type,
                typeInfo.variable,
                callConstraints.dispatchReceiver,
                callConstraints.extensionReceiver,
                callConstraints.arguments,
                callConstraints.callExpr
        )

        val objectPredicate = PredicateFactory.getNew(callTypeInfo.variable)
        val substitutionPredicate = PredicateFactory.getBool(callConstraints.substitutionTerm)
        val ps = BasicState(listOf(objectPredicate, substitutionPredicate))
        callTypeInfo.addPredicate(ps)

        val constructor = resolvedCall.candidateDescriptor
        val constructorParameters = constructor.valueParameters

        for (field in constructorParameters) {
            val psi = field.findPsiWithProxy() ?: throw IllegalArgumentException("No PSI for field")
            val parameterLqt = NewLQTInfo.getOrException(psi)
            val fieldTerm = TermFactory.getField(
                    parameterLqt.type.makeReference(),
                    callTypeInfo.variable,
                    TermFactory.getString(field.name.identifier)
            )
            val predicate = PredicateFactory.getFieldStore(fieldTerm, parameterLqt.variable)
            val trickyHack = parameterLqt.expression.copied()
            val lqt = LiquidType(trickyHack, KexBool, fieldTerm, arrayListOf(parameterLqt))
            lqt.addPredicate(predicate)
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

        val pseudocode = PseudocodeUtil.generatePseudocode(expression, bindingContext)

        val descriptor = expression.descriptor.safeAs<CallableDescriptor>()
                ?: throw IllegalStateException("WTF: Function is not callable: ${expression.text}")

        val dispatchReceiver = descriptor.dispatchReceiverParameter?.let { getLiquidTypeForReceiverDescriptor(it) }
        val extReceiver = descriptor.extensionReceiverParameter?.let { getLiquidTypeForReceiverDescriptor(it) }

        val parameters = expression.valueParameters.map {
            it.nameAsSafeName.asString() to functionSubExprAnalyzer(it, dispatchReceiver, extReceiver, pseudocode).analyze()
        }

        val stub = KotlinBuiltInStub.findFunction(expression.fqName!!)

        if (!expression.hasBody()) throw IllegalArgumentException("Function without body: ${expression.text}")
        val body = expression.bodyExpression ?: throw IllegalStateException("WTF?")

        val value = if (stub != null) null else {
            val bodyExpr = functionSubExprAnalyzer(body, dispatchReceiver, extReceiver, pseudocode).analyze()
            val returns = bodyExpr.collectDescendants(withSelf = true) {
                it is ReturnLiquidType && it.function == expression
            }.filterIsInstance<ReturnLiquidType>()
            if (returns.isNotEmpty()) {
                LiquidType.createWithoutExpression(expression, "Returns", typeInfo.type).apply {
                    dependsOn.addAll(returns)
                    val returnValue = returns.map {
                        listOf(it.conditionPath, TermFactory.equalityTerm(it.variable, variable))
                    }.map { it.combineWithAnd() }
                            .combineWithOr()
                    addPredicate(PredicateFactory.getBool(returnValue))
                    dependsOn.add(bodyExpr)
                }
            } else bodyExpr
        }

        val funTypeInfoInitial = FunctionLiquidType(
                expression,
                typeInfo.type,
                typeInfo.variable,
                dispatchReceiver,
                extReceiver,
                parameters.toMap(),
                value
        )

        val funTypeInfo = stub?.eval(funTypeInfoInitial) ?: funTypeInfoInitial.apply {
            val returnVariable = returnValue?.variable ?: return@apply
            addPredicate(PredicateFactory.getEquality(typeInfo.variable, returnVariable))
        }


        NewLQTInfo.typeInfo[expression] = funTypeInfo
        return funTypeInfo
    }
}


class ConstructorAnalyzer(expression: KtConstructor<*>) : ExpressionAnalyzer<KtConstructor<*>>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val parameters = expression.getValueParameters().map {
            it.nameAsSafeName.asString() to subExprAnalyzer(it).analyze()
        }

        val funTypeInfo = FunctionLiquidType(
                expression,
                typeInfo.type,
                typeInfo.variable,
                null, null,
                parameters.toMap(),
                null
        ).apply {
            addEmptyPredicate()
        }

        NewLQTInfo.typeInfo[expression] = funTypeInfo

        return funTypeInfo
    }

}

class DotQualifiedExpressionAnalyzer(expression: KtDotQualifiedExpression) : ExpressionAnalyzer<KtDotQualifiedExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val receiver = subExprAnalyzer(expression.receiverExpression).analyze()
        val selectorExpr = expression.selectorExpression
                ?: throw IllegalArgumentException("Selector expression expected")

        val analysisResult = subExprAnalyzer(selectorExpr).analyze()
        val constraint = PredicateFactory.getEquality(typeInfo.variable, analysisResult.variable)
        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.addAll(listOf(receiver, analysisResult))
        return typeInfo
    }
}


class UnaryExpressionAnalyzer(expression: KtUnaryExpression) : ExpressionAnalyzer<KtUnaryExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val expr = expression.baseExpression ?: throw IllegalArgumentException("No sub expression for $expression")
        val exprLqt = subExprAnalyzer(expr).analyze()

        val operation = expression.operationToken
        val opcode = UnaryOpcode.fromOperationToken(operation)
        val value = TermFactory.getUnaryTerm(exprLqt.variable, opcode)

        val constraint = PredicateFactory.getEquality(typeInfo.variable, value)
        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.add(exprLqt)

        return typeInfo
    }

}

class SafeQualifiedExpressionAnalyzer(expression: KtSafeQualifiedExpression) : ExpressionAnalyzer<KtSafeQualifiedExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val receiver = subExprAnalyzer(expression.receiverExpression).analyze()
        val check = TermFactory.equalityTerm(receiver.variable, TermFactory.getNull())
        val result = TermFactory.getIf(check, TermFactory.getNull(), receiver.variable)

        val constraint = PredicateFactory.getEquality(typeInfo.variable, result)
        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.add(receiver)

        return typeInfo
    }

}


class BlockExpressionAnalyzer(expression: KtBlockExpression) : ExpressionAnalyzer<KtBlockExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val expressions = expression.statements.map { subExprAnalyzer(it).analyze() }

        if (expressions.isEmpty()) {
            typeInfo.addEmptyPredicate()
            return typeInfo
        }

        //todo: maybe fix it
        val constraint = PredicateFactory.getEquality(typeInfo.variable, expressions.last().variable)

        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.addAll(expressions)

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

class ReturnAnalyzer(expression: KtReturnExpression) : ExpressionAnalyzer<KtReturnExpression>(expression) {

    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val targetFunction = expression.getTargetFunction(bindingContext)
                ?: throw IllegalArgumentException("No target function for return $expression")

        val pseudocode = cfg
                ?: throw IllegalStateException("No cfg for $expression | ${targetFunction.text}")
        val instruction = pseudocode.instructionForElement(expression)
                ?: throw IllegalArgumentException("No instruction for expression $expression")

        val path = LinkedHashSet<Instruction>()
        traverseFollowingInstructions(instruction, path, TraversalOrder.BACKWARD) {
            TraverseInstructionResult.CONTINUE
        }
        val conditions = path.filterIsInstance<ConditionalJumpInstruction>()
        val onTruePathLqt = arrayListOf<LiquidType>()
        val onFalsePathLqt = arrayListOf<LiquidType>()

        for (cond in conditions) {
            if (cond.nextOnTrue in path && cond.nextOnFalse in path)
                continue
            if (cond.nextOnTrue !in path && cond.nextOnFalse !in path)
                continue

            val conditionLqt = cond.inputValues
                    .mapNotNull { it.element.safeAs<KtExpression>() }
                    .map { subExprAnalyzer(it).analyze() }

            when {
                cond.nextOnTrue in path -> onTruePathLqt.addAll(conditionLqt)
                cond.nextOnFalse in path -> onFalsePathLqt.addAll(conditionLqt)
            }
        }

        val pathPredicates = onTruePathLqt.map { it.variable } + onFalsePathLqt.map { TermFactory.getNegTerm(it.variable) }
        val pathPredicate = pathPredicates.combineWithAnd()

        val expr = expression.returnedExpression

        val value = if (expr != null) {
            subExprAnalyzer(expr).analyze()
        } else {
            LiquidType.createWithoutExpression(expression, "Unit", KexVoid).apply {
                addEmptyPredicate()
            }
        }

        val lqt = ReturnLiquidType(
                expression,
                value.type,
                LiquidType.createVariable(value.type),
                arrayListOf(value),
                pathPredicate,
                targetFunction
        )

        lqt.addPredicate(PredicateFactory.getEquality(lqt.variable, value.variable))

        lqt.dependsOn.addAll(onTruePathLqt)
        lqt.dependsOn.addAll(onFalsePathLqt)

        typeInfo.addEmptyPredicate()
        typeInfo.dependsOn.add(lqt)

        return typeInfo
    }

}

class ParenthesizedAnalyzer(expression: KtParenthesizedExpression) : ExpressionAnalyzer<KtParenthesizedExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val subExpr = expression.expression

        if (subExpr == null) {
            typeInfo.addEmptyPredicate()
            return typeInfo
        }

        val value = subExprAnalyzer(subExpr).analyze()
        val constraint = PredicateFactory.getEquality(typeInfo.variable, value.variable)
        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.add(value)

        return typeInfo
    }

}

class SkipAnalyzer(expression: KtExpression) : ExpressionAnalyzer<KtExpression>(expression) {
    override fun analyze(): LiquidType {
        reportError("Skip analysis of $expression")
        return NewLQTInfo.getOrException(expression)
    }
}

fun getExpressionAnalyzer(expression: KtExpression, bindingContext: BindingContext, analysisTrace: AnalysisTrace) =
        ExpressionAnalyzer.analyzerForExpression(expression, bindingContext, analysisTrace)
                ?: throw NotImplementedError("Aalysis for $expression not implemented yet")


fun hasExpressionAnalyzer(expression: KtExpression) =
        ExpressionAnalyzer.analyzerForExpression(expression, BindingContext.EMPTY, AnalysisTrace()) != null

fun KtExpression.LqTAnalyzer(bindingContext: BindingContext, analysisTrace: AnalysisTrace = AnalysisTrace()) =
        getExpressionAnalyzer(this, bindingContext, analysisTrace)
