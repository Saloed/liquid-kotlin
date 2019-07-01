import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.cfg.pseudocode.Pseudocode
import org.jetbrains.kotlin.cfg.pseudocode.PseudocodeUtil
import org.jetbrains.kotlin.cfg.pseudocode.instructions.Instruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.jumps.ConditionalJumpInstruction
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.isConstant
import org.jetbrains.kotlin.idea.search.usagesSearch.descriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.getTargetFunction
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCallWithAssert
import org.jetbrains.kotlin.resolve.calls.components.isVararg
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.research.kex.ktype.KexArray
import org.jetbrains.research.kex.ktype.KexVoid
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.ChainState
import org.jetbrains.research.kex.state.ChoiceState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.predicate.PredicateType
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kfg.ir.value.instruction.UnaryOpcode


typealias AnalysisTrace = Trace<PsiElement>

abstract class ExpressionAnalyzer<T : KtExpression>(val expression: T) {

    val pf = PredicateFactory
    val tf = TermFactory

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
                ?: getIfReceiverIsObject(receiver)
                ?: throw IllegalArgumentException("Implicit receiver is not present")
        else -> throw NotImplementedError("Receiver is not supported: $receiver")
    }

    private fun getIfReceiverIsObject(receiver: ImplicitReceiver): LiquidType? {
        val classReceiver = receiver.safeAs<ImplicitClassReceiver>() ?: return null
        val descriptor = classReceiver.classDescriptor
        if (descriptor.kind != ClassKind.OBJECT) return null
        val lqt = objectInstances[descriptor]
        if (lqt != null) return lqt
        val psi = descriptor.findPsiWithProxy() ?: return null
        val newLqt = LiquidType.createWithoutExpression(psi, "${descriptor.name}", descriptor.defaultType.toKexType()).apply {
            addPredicate(PredicateFactory.getNew(variable, PredicateType.State()))
        }
        objectInstances[descriptor] = newLqt
        return newLqt
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


                    is KtConstructorCalleeExpression -> CallAnalyzer(expression)
                    is KtCallExpression -> CallAnalyzer(expression)
                    is KtDotQualifiedExpression -> DotQualifiedExpressionAnalyzer(expression)
                    is KtSafeQualifiedExpression -> SafeQualifiedExpressionAnalyzer(expression)
                    is KtArrayAccessExpression -> ArrayAccessExpressionAnalyzer(expression)
                    is KtConstructor<*> -> ConstructorAnalyzer(expression)
                    is KtFunction -> FunctionAnalyzer(expression)
                    is KtProperty -> PropertyAnalyzer(expression)

                    is KtStringTemplateExpression -> StringTemplateAnalyzer(expression)
                    is KtUnaryExpression -> UnaryExpressionAnalyzer(expression)
                    is KtBlockExpression -> BlockExpressionAnalyzer(expression)
                    is KtReturnExpression -> ReturnAnalyzer(expression)

                    is KtWhileExpression -> SkipAnalyzer(expression)
                    is KtDoWhileExpression -> SkipAnalyzer(expression)
                    is KtForExpression -> SkipAnalyzer(expression)
                    is KtBreakExpression -> SkipAnalyzer(expression)
                    is KtContinueExpression -> SkipAnalyzer(expression)

                    is KtDeclaration -> SkipAnalyzer(expression)

                    else -> null
                }).apply {
            this?.bindingContext = bindingContext
            this?.analysisTrace = analysisTrace
        }

        val objectInstances = HashMap<ClassDescriptor, LiquidType>()
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

        val constraint = PredicateFactory.getIf(condTerm.variable, thenBranch.variable, elseBranch.variable, typeInfo.variable)
        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.addAll(listOf(condTerm, thenBranch, elseBranch))
        return typeInfo
    }
}


class CallAnalyzer(expression: KtExpression) : ExpressionAnalyzer<KtExpression>(expression) {

    data class CallArgumentsInfo(
            val callExpr: FunctionLiquidType,
            val substitution: PredicateState,
            val arguments: Map<String, LiquidType>,
            val dispatchReceiver: LiquidType?,
            val extensionReceiver: LiquidType?
    )

    sealed class CallArgument {
        class NormalArgument(val expression: KtExpression) : CallArgument()
        class VarargArgument(val expressions: List<KtExpression>, val type: KotlinType, val elementType: KotlinType) : CallArgument()
    }

    data class CallInfo(
            val descriptor: DeclarationDescriptor,
            val arguments: List<Pair<String, CallArgument>>,
            val dispatchReceiver: ReceiverValue?,
            val extensionReceiver: ReceiverValue?
    )

    private fun getArgument(parameter: ValueParameterDescriptor, args: List<ValueArgument>): CallArgument {
        val expressions = args.mapNotNull { it.getArgumentExpression() }
        return when {
            expressions.size == 1 -> CallArgument.NormalArgument(expressions.first())
            parameter.isVararg -> CallArgument.VarargArgument(expressions, parameter.type, parameter.varargElementType!!)
            else -> throw IllegalStateException("Error in arguments $args")
        }
    }

    fun analyzeCallArgument(argument: CallArgument): LiquidType = when (argument) {
        is CallArgument.NormalArgument -> subExprAnalyzer(argument.expression).analyze()
        is CallArgument.VarargArgument -> {
            val expressions = argument.expressions.map { subExprAnalyzer(it).analyze() }
            val a = 3
            TODO()
        }
    }

    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val resolvedCall = expression.getCall(bindingContext)?.getResolvedCallWithAssert(bindingContext)
        val callExpr = expression.safeAs<KtCallExpression>()
        val reference = callExpr?.let { bindingContext[BindingContext.REFERENCE_TARGET, it] }
        val callInfo = when {
            resolvedCall != null -> {
                val arguments = resolvedCall.valueArguments.toList()
                        .map { it.first.name.asString() to getArgument(it.first, it.second.arguments) }
                CallInfo(resolvedCall.candidateDescriptor, arguments,
                        resolvedCall.dispatchReceiver, resolvedCall.extensionReceiver)
            }
            reference != null -> {
                val arguments = callExpr.valueArguments.mapIndexed { index, it ->
                    (it.getArgumentName()?.name ?: "$index") to it.getArgumentExpression()
                }
                        .mapSecond {
                            it?.let(CallArgument::NormalArgument)
                                    ?: throw IllegalArgumentException("No expression for argument")
                        }
                CallInfo(reference, arguments, null, null)
            }
            else -> throw IllegalArgumentException("Function not found")
        }


        return when (callInfo.descriptor) {
            is SimpleFunctionDescriptor -> analyzeFunctionCall(callInfo)
            is ConstructorDescriptor -> analyzeConstructorCall(callInfo)
            else -> throw  NotImplementedError("Unknown call expression type $resolvedCall")
        }

    }

    private fun analyzeCallArguments(resolvedCall: CallInfo): CallArgumentsInfo {
        val element = resolvedCall.descriptor.findPsiWithProxy()
                ?: throw IllegalArgumentException("Function PSI not found")

        val typeInfo = (
                if (element is KtExpression) subExprAnalyzer(element).analyze() else NewLQTInfo.typeInfo[element]
                ).safeAs<FunctionLiquidType>()
                ?: throw IllegalStateException("Not functional call $resolvedCall")

        val callArgumentsExpr = resolvedCall.arguments.toList()
        val dispatch = resolvedCall.dispatchReceiver?.let { getLiquidTypeForReceiverValue(it) }
        val extension = resolvedCall.extensionReceiver?.let { getLiquidTypeForReceiverValue(it) }

        val arguments = callArgumentsExpr.mapSecond { analyzeCallArgument(it) }.toList()

        val argumentsMap = arguments.toMap()
        val substitutedParameters = mutableSetOf<LiquidType>()

        val substitution = typeInfo.arguments.toList().mapIndexed { idx, (parameterName, parameter) ->
            val argByName = argumentsMap[parameterName]
            val argByIndex = arguments[idx].second
            val argument = argByName ?: argByIndex ?: throw IllegalArgumentException(
                    "Argument is not provided for parameter: $parameterName | ${typeInfo.expression.text}"
            )
            if (argument in substitutedParameters)
                throw IllegalStateException("Argument already substituted: $parameterName | ${typeInfo.expression.text}")
            substitutedParameters.add(parameter)

            val argumentVariable = if (argument.variable.type != parameter.variable.type)
                tf.getCast(parameter.variable.type, argument.variable)
            else argument.variable

            pf.getEquality(parameter.variable, argumentVariable)
        }

        val dispatchSubstitution = typeInfo.dispatchArgument?.let {
            val dispatchArgument = dispatch ?: throw IllegalArgumentException(
                    "Function require dispatch argument: ${typeInfo.expression.text} at call ${expression.text}"
            )
            pf.getEquality(it.variable, dispatchArgument.variable)
        }

        val extensionSubstitution = typeInfo.extensionArgument?.let {
            val extensionArgument = dispatch ?: throw IllegalArgumentException(
                    "Function require extension argument: ${typeInfo.expression.text} at call ${expression.text}"
            )
            pf.getEquality(typeInfo.extensionArgument.variable, extensionArgument.variable)
        }

        val substitutionPs = BasicState(
                substitution + listOfNotNull(dispatchSubstitution, extensionSubstitution)
        )
        return CallArgumentsInfo(typeInfo, substitutionPs, argumentsMap, dispatch, extension)
    }

    private fun analyzeFunctionCall(resolvedCall: CallInfo): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        if (expression in analysisTrace) {
            return typeInfo
        }

        val callConstraints = analyzeCallArguments(resolvedCall)

        val callResult = PredicateFactory.getEquality(typeInfo.variable, callConstraints.callExpr.variable)

        val callTypeInfo = CallExpressionLiquidType(
                expression,
                typeInfo.type,
                typeInfo.variable,
                callConstraints.dispatchReceiver,
                callConstraints.extensionReceiver,
                callConstraints.arguments,
                callConstraints.callExpr
        )
        val callResultPs = BasicState(listOf(callResult))
        val ps = ChainState(callConstraints.substitution, callResultPs)
        callTypeInfo.addPredicate(ps)

        NewLQTInfo.typeInfo[expression] = callTypeInfo
        return callTypeInfo
    }

    private fun analyzeConstructorCall(resolvedCall: CallInfo): LiquidType {
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


        val objectPredicate = pf.getEquality(callTypeInfo.variable, callConstraints.callExpr.variable)
        val objectPs = BasicState(listOf(objectPredicate))
        val ps = ChainState(objectPs, callConstraints.substitution)
        callTypeInfo.addPredicate(ps)
        callTypeInfo.dependsOn.add(callConstraints.callExpr)

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

        val value = if (stub != null) null else {
            if (!expression.hasBody()) throw IllegalArgumentException("Function without body: ${expression.text}")
            val body = expression.bodyExpression ?: throw IllegalStateException("WTF?")
            val bodyExpr = functionSubExprAnalyzer(body, dispatchReceiver, extReceiver, pseudocode).analyze()
            val returns = bodyExpr.collectDescendants(withSelf = true) {
                it is ReturnLiquidType && it.function == expression
            }.filterIsInstance<ReturnLiquidType>()
            if (returns.isNotEmpty()) {
                LiquidType.createWithoutExpression(expression, "${descriptor.fqNameSafe.asString()}.returns", typeInfo.type).apply {
                    val returnValue = returns.map {
                        it.conditionPath + PredicateFactory.getEquality(variable, it.variable)
                    }
                    addPredicate(ChoiceState(returnValue))

                    dependsOn.add(bodyExpr)
                    dependsOn.addAll(returns)
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
        val newObject = pf.getNew(typeInfo.variable)
        val fieldStorePredicates = parameters.map { (name, paramLqt) ->
            val fieldTerm = TermFactory.getField(
                    paramLqt.type.makeReference(),
                    typeInfo.variable,
                    TermFactory.getString(name)
            )
            PredicateFactory.getFieldStore(fieldTerm, paramLqt.variable)
        }
        val ps = BasicState(fieldStorePredicates.plus(newObject))

        val funTypeInfo = FunctionLiquidType(
                expression,
                typeInfo.type,
                typeInfo.variable,
                null, null,
                parameters.toMap(),
                null
        ).apply {
            addPredicate(ps)
        }

        NewLQTInfo.typeInfo[expression] = funTypeInfo

        return funTypeInfo
    }

}

class DotQualifiedExpressionAnalyzer(expression: KtDotQualifiedExpression) : ExpressionAnalyzer<KtDotQualifiedExpression>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        val receiver = try {
            subExprAnalyzer(expression.receiverExpression).analyze()
        } catch (ex: NoInfoException) {
            println("$ex")
            null
        }
        val selectorExpr = expression.selectorExpression
                ?: throw IllegalArgumentException("Selector expression expected")

        val analysisResult = subExprAnalyzer(selectorExpr).analyze()
        val constraint = PredicateFactory.getEquality(typeInfo.variable, analysisResult.variable)
        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.addAll(listOfNotNull(receiver, analysisResult))
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
        val constraint = PredicateFactory.getIf(check, TermFactory.getNull(), receiver.variable, typeInfo.variable)
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

        val lqt = BlockLiquidType(
                typeInfo.expression,
                typeInfo.type,
                typeInfo.variable,
                mutableListOf()
        )
        lqt.dependsOn.addAll(expressions)
        lqt.addPredicate(constraint)

        NewLQTInfo.typeInfo[expression] = lqt
        return lqt
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

class PropertyAnalyzer(expression: KtProperty) : ExpressionAnalyzer<KtProperty>(expression) {
    override fun analyze(): LiquidType {
        val typeInfo = NewLQTInfo.getOrException(expression)
        if (typeInfo.hasConstraints) return typeInfo

        if (!expression.isLocal) {
            reportError("Only local properties are supported for now: ${expression.text}")
            typeInfo.addEmptyPredicate()
            return typeInfo
        }

        val initializer = expression.initializer
        if (initializer == null) {
            typeInfo.addEmptyPredicate()
            return typeInfo
        }

        val value = subExprAnalyzer(initializer).analyze()
        val constraint = pf.getEquality(typeInfo.variable, value.variable)

        typeInfo.addPredicate(constraint)
        typeInfo.dependsOn.add(value)

        return typeInfo
    }

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

        val pathPredicatesTrue = onTruePathLqt.map { pf.getEquality(it.variable, tf.getTrue(), PredicateType.Path()) }
        val pathPredicatesFalse = onFalsePathLqt.map { pf.getEquality(it.variable, tf.getFalse(), PredicateType.Path()) }
        val pathPredicates = pathPredicatesTrue + pathPredicatesFalse
        val pathPredicateState = BasicState(pathPredicates)

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
                mutableListOf(value),
                pathPredicateState,
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

fun getExpressionAnalyzer(expression: KtExpression, bindingContext: BindingContext, analysisTrace: AnalysisTrace): ExpressionAnalyzer<out KtExpression> {
    println("$expression | ${expression.text.replace('\n', ' ')} | $analysisTrace")
    return ExpressionAnalyzer.analyzerForExpression(expression, bindingContext, analysisTrace)
            ?: throw NotImplementedError("Aalysis for $expression not implemented yet")
}


fun hasExpressionAnalyzer(expression: KtExpression) =
        ExpressionAnalyzer.analyzerForExpression(expression, BindingContext.EMPTY, AnalysisTrace()) != null

fun KtExpression.LqTAnalyzer(bindingContext: BindingContext, analysisTrace: AnalysisTrace = AnalysisTrace()) =
        getExpressionAnalyzer(this, bindingContext, analysisTrace)
