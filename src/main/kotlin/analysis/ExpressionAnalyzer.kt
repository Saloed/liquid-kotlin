package analysis

import fixpoint.predicate.*
import mapSecond
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.descriptors.IrBuiltinOperatorDescriptor
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.resolve.constants.BooleanValue
import org.jetbrains.kotlin.resolve.constants.IntValue
import pairNotNull
import zipMap

class ExpressionAnalyzer(val context: AnalysisContext) : IrElementVisitor<Term, Nothing?> {

    val result: SomeDummyAnalysisResult
        get() = TODO("No analysis result")


    override fun visitElement(element: IrElement, data: Nothing?): Term {
        throw IllegalArgumentException("Expression expected, but $element")
    }

    override fun visitExpression(expression: IrExpression, data: Nothing?): Term {
        TODO("Analysis for $expression is not implemented")
    }


    override fun visitBlockBody(body: IrBlockBody, data: Nothing?): Term {
        BlockAnalyzer.analyze(body, context)
        TODO("Aaaaaa: block body")
    }

    override fun visitGetValue(expression: IrGetValue, data: Nothing?): Term {
        val bind = context.binds[expression.symbol.owner]
                ?: throw IllegalArgumentException("Unknown declaration: $expression")
        return context.createVariable(bind.name, expression.type)
    }

    override fun <T> visitConst(expression: IrConst<T>, data: Nothing?): Term {
        val kind = expression.kind
        return when (kind) {
            is IrConstKind.Int -> NumericValueTerm(kind.valueOf(expression))
            is IrConstKind.Null -> TODO()
            is IrConstKind.Boolean -> BooleanValueTerm(kind.valueOf(expression))
            is IrConstKind.Char -> TODO()
            is IrConstKind.Byte -> TODO()
            is IrConstKind.Short -> TODO()
            is IrConstKind.Long -> TODO()
            is IrConstKind.String -> StringValueTerm(kind.valueOf(expression))
            is IrConstKind.Float -> TODO()
            is IrConstKind.Double -> TODO()
        }
    }

    private fun getBuiltinFunctionResult(arguments: Map<ValueParameterDescriptor, Term>, descriptor: IrBuiltinOperatorDescriptor): Term {
        return when (descriptor.name.asString()) {
            "greater" -> {
                val (lhs, rhs) = arguments.values.toList()
                BinaryTerm(lhs, BinaryOperation.GREATER, rhs)
            }
            "less" -> {
                val (lhs, rhs) = arguments.values.toList()
                BinaryTerm(lhs, BinaryOperation.LOWER, rhs)
            }
            "greaterOrEqual" -> {
                val (lhs, rhs) = arguments.values.toList()
                BinaryTerm(lhs, BinaryOperation.GTE, rhs)
            }
            "lessOrEqual" -> {
                val (lhs, rhs) = arguments.values.toList()
                BinaryTerm(lhs, BinaryOperation.LTE, rhs)
            }
            else -> TODO("Unknown builtin ${descriptor.name}")
        }
    }

    private fun callArgumentValues(expression: IrCall): Map<ValueParameterDescriptor, Term> {
        val parameters = expression.descriptor.valueParameters
        val arguments = parameters.zipMap { expression.getValueArgument(it) }
        val notNullArguments = arguments.pairNotNull()
        if (notNullArguments.size != parameters.size) {
            TODO("understand null arguments")
        }
        val argumentValues = notNullArguments.mapSecond { it.accept(this, null) }
        return argumentValues.toMap()
    }

    override fun visitCall(expression: IrCall, data: Nothing?): Term {
        val descriptor = expression.descriptor
        val arguments = callArgumentValues(expression)
        if (descriptor is IrBuiltinOperatorDescriptor) {
            val result = getBuiltinFunctionResult(arguments, descriptor)
            val resultName = context.generateUniqueName()
            val resultVar = context.createVariable(resultName, expression.type)
            val resultTerm = context.createEquality(resultVar, result)
            context.binds[expression] = context.createBind(resultName, expression.type, resultTerm)
            return resultVar
        }
        //todo: function constraint
        val resultName = context.generateUniqueName()
        context.binds[expression] = context.createBind(resultName, expression.type, emptyList())
        return context.createVariable(resultName, expression.type)
    }

    override fun visitWhen(expression: IrWhen, data: Nothing?): Term {

        data class Branch(val condition: Term, val value: Term, val condExpression: IrExpression, val valueExpression: IrExpression)

        val conditionBranches = expression.branches
                .filter { it !is IrElseBranch }
                .map {
                    val cond = it.condition.accept(this, null)
                    val value = it.result.accept(this, null)
                    Branch(cond, value, it.condition, it.result)
                }

        val elseCondition = if (conditionBranches.isEmpty())
            listOf(BooleanValueTerm(true))
        else
            conditionBranches
                    .map { it.condition }
                    .map { UnaryTerm(UnaryOperation.NOT, it) }
        val elseBranches = expression.branches.filterIsInstance<IrElseBranch>()


        val resultName = context.generateUniqueName()
        val resultVarName = context.generateUniqueName()
        val resultVar = context.createVariable(resultVarName, expression.type)
        val itTerm = context.createVariable("it", expression.type)

        val resultAssign = AssigmentTerm(itTerm, resultVar)
        val substitution = SubstitutionTerm(resultName, listOf(resultAssign))
        val resultBind = context.createBind(resultVarName, expression.type, substitution)
        context.binds[expression] = resultBind


        fun createBranch(condition: List<Term>, value: Term, condExpr: IrExpression?, valueExpr: IrExpression) {
            //todo: take only usefull binds
            val environment = context.createEnvironmentForBinds(context.binds.values.toList(), except = listOf(resultBind))
            val branchValueName = "branch_value"
            val branchValueTerm = context.createVariable(branchValueName, valueExpr.type)
            val lhsTerm = context.createEquality(branchValueTerm, value)
            val lhs = context.createPredicate(branchValueName, valueExpr.type, condition + lhsTerm)
            val rhsAssigment = AssigmentTerm(itTerm, branchValueTerm)
            val rhsTerm = SubstitutionTerm(resultName, listOf(rhsAssigment))
            val rhs = context.createPredicate(branchValueName, valueExpr.type, listOf(rhsTerm))
            val constraint = context.createConstraint(environment, lhs, rhs)
            context.constraints.add(constraint)
        }

        conditionBranches.forEach { (condition, value, condExpr, valueExpr) ->
            createBranch(listOf(condition), value, condExpr, valueExpr)
        }

        if (elseBranches.isNotEmpty()) {
            val elseBranch = elseBranches.first()
            val elseValue = elseBranch.result.accept(this, null)
            createBranch(elseCondition, elseValue, null, elseBranch.result)
        }

        run {
            //todo: take only usefull binds
            val environment = context.createEnvironmentForBinds(context.binds.values.toList())
            val substitutionTerm = SubstitutionTerm(resultName, emptyList())
            val reft = context.createPredicate("it", expression.type, listOf(substitutionTerm))
            val wf = context.createWf(environment, reft)
            context.wfConstraints.add(wf)
        }

        return resultVar
    }

    override fun visitGetField(expression: IrGetField, data: Nothing?): Term {
        if (expression.descriptor.isConst) {
            val initializer = expression.descriptor.compileTimeInitializer ?: TODO("Initializer without value")
            return when (initializer) {
                is BooleanValue -> BooleanValueTerm(initializer.value)
                is IntValue -> NumericValueTerm(initializer.value)
                else -> StringValueTerm(initializer.stringTemplateValue())
            }
        }
        //todo
        return super.visitGetField(expression, data)
    }

    override fun visitReturn(expression: IrReturn, data: Nothing?): Term {
        val returnValueTerm = expression.value.accept(this, null)
        val returnTerm = context.createVariable("return_value", expression.value.type)
        val equality = context.createEquality(returnTerm, returnValueTerm)
        context.binds[expression] = context.createBind("return_value", expression.value.type, equality)
        return returnValueTerm
    }

    companion object {
        fun analyze(expression: IrExpression, context: AnalysisContext): SomeDummyAnalysisResult {
            val analyzer = ExpressionAnalyzer(context)
            expression.accept(analyzer, null)
            return SomeDummyAnalysisResult()
        }
    }

    fun List<Term>.combineWith(operation: BinaryOperation) = when (size) {
        0 -> throw IllegalArgumentException("Can't combine an empty list")
        1 -> first()
        else -> drop(1).fold(first()) { result, current -> BinaryTerm(result, operation, current) }
    }

}
