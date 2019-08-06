package analysis

import annotation.ElementConstraint
import fixpoint.*
import fixpoint.predicate.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

class FunctionAnalyzer(val constraints: ElementConstraint) {


    class FunctionCollector : IrElementVisitorVoid {

        val functions = mutableListOf<IrSimpleFunction>()

        override fun visitElement(element: IrElement) {
            element.acceptChildren(this, null)
        }


        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            functions.add(declaration)
            super.visitFunction(declaration)
        }

        companion object {
            fun collect(element: IrElement): List<IrSimpleFunction> {
                val collector = FunctionCollector()
                element.accept(collector, null)
                return collector.functions
            }
        }

    }

    fun analyze(element: IrElement) {
        val functions = FunctionCollector.collect(element)
        for (function in functions) {
            analyzeFunction(function)
        }
    }


    private fun createFunctionParameters(parameters: List<IrValueParameter>, context: AnalysisContext) {
        val binds = parameters.map {
            context.createBind(it.name.asString(), it.type, emptyList())
        }
        parameters.zip(binds).forEach { (parameter, bind) ->
            context.binds[parameter] = bind
        }
        val parameterConstraints = parameters.map {
            val analyzer = ExpressionAnalyzer(context)
            constraints.get(it).map { constraintExpr ->
                constraintExpr.accept(analyzer, null)
            }
        }
        val bindsWithConstrains = binds.zip(parameterConstraints)
                .map { (bind, constrains) ->
                    val predicate = bind.predicate.copy(constraint = constrains)
                    bind.copy(predicate = predicate)
                }
        parameters.zip(bindsWithConstrains).forEach { (parameter, bind) ->
            context.binds[parameter] = bind
        }
    }

    private fun createFunctionConstraint(function: IrSimpleFunction, context: AnalysisContext) {
        val dummyValue = when {
            function.returnType.isInt() -> NumericValueTerm(0)
            function.returnType.isBoolean() -> BooleanValueTerm(false)
            function.returnType.isString() -> StringValueTerm("dummy")
            else -> TODO("Return type ${function.returnType} is not supported")
        }
        val returnValue = VariableTerm("return_value")
        val returnTerm = VariableTerm("return")
        val returnResultVar = VariableTerm("return_result_var")
        val returnName = "return_value_fn"

        val returnBind = run {
            val assigmentTerm = AssigmentTerm(returnTerm, returnResultVar)
            val substitutionTerm = SubstitutionTerm(returnName, listOf(assigmentTerm))
            context.createBind(returnResultVar.name, function.returnType, listOf(substitutionTerm))
        }
        context.binds[function] = returnBind

        run {
            // todo: make environment not full
            val environment = context.createEnvironmentForBinds(context.binds.values.toList(), except = listOf(returnBind))
            val itTerm = VariableTerm("it")
            val lhs = run {
                val term = context.createEquality(itTerm, returnValue)
                context.createPredicate("it", function.returnType, listOf(term))
            }
            val rhs = run {
                val assigmentTerm = AssigmentTerm(returnTerm, itTerm)
                val substitutionTerm = SubstitutionTerm(returnName, listOf(assigmentTerm))
                context.createPredicate("it", function.returnType, listOf(substitutionTerm))
            }
            val constraint = context.createConstraint(environment, lhs, rhs)
            context.constraints.add(constraint)
        }

        run {
            // todo: make environment not full
            val environment = context.createEnvironmentForBinds(context.binds.values.toList())

            val itTerm = VariableTerm("it")
            val lhs = run {
                val assigmentTerm = AssigmentTerm(returnTerm, itTerm)
                val substitutionTerm = SubstitutionTerm(returnName, listOf(assigmentTerm))
                context.createPredicate("it", function.returnType, listOf(substitutionTerm))
            }
            val rhs = run {
                val term = context.createEquality(itTerm, dummyValue)
                context.createPredicate("it", function.returnType, listOf(term))
            }

            val constraint = context.createConstraint(environment, lhs, rhs)
            context.constraints.add(constraint)

        }

        run {
            // todo: make environment not full
            val environment = context.createEnvironmentForBinds(context.binds.values.toList())
            val reftSubstitution = SubstitutionTerm(returnName, emptyList())
            val reft = context.createPredicate("return", function.returnType, listOf(reftSubstitution))
            val wf = context.createWf(environment, reft)
            context.wfConstraints.add(wf)
        }
    }

    fun analyzeFunction(function: IrSimpleFunction): SomeDummyAnalysisResult {
        val parameters = function.valueParameters + listOfNotNull(
                function.dispatchReceiverParameter, function.extensionReceiverParameter
        )

        val context = AnalysisContext()

        createFunctionParameters(parameters, context)

        val body = function.body
        val bodyAnalysisResult = when (body) {
            is IrExpressionBody -> ExpressionAnalyzer.analyze(body.expression, context)
            is IrBlockBody -> BlockAnalyzer.analyze(body, context)
            else -> TODO()
        }

        createFunctionConstraint(function, context)

        simplifyBinds(context)

        val query = buildQueryForContext(context)

        val solution = Solver.solve(query)

        showDebugInfo(function, query, solution)

        return SomeDummyAnalysisResult()
    }


    private fun showDebugInfo(function: IrSimpleFunction, query: Query, solutions: List<Solution>) {

        println(function.name)
        println(query.print())
        solutions.forEach {
            println(it.print())
        }

    }

    fun simplifyBinds(context: AnalysisContext) = with(context) {
        for ((key, bind) in binds) {
            val constraints = bind.predicate.constraint
            if (constraints.isEmpty()) continue
            val simplified = constraints.filterNot {
                it is BooleanValueTerm && it.value
            }
            if (simplified.size == constraints.size) continue
            val predicate = bind.predicate.copy(constraint = simplified)
            binds[key] = bind.copy(predicate = predicate)
        }
    }

    private fun buildQueryForContext(context: AnalysisContext): Query {
        val cmpOperators = listOf(
                BinaryOperation.EQUAL,
                BinaryOperation.NOT_EQUAL,
                BinaryOperation.GTE,
                BinaryOperation.LTE,
                BinaryOperation.GREATER,
                BinaryOperation.LOWER
        )
        val qualifiers = cmpOperators.map {
            val lhsVariable = VariableTerm("v")
            val rhsVariable = VariableTerm("x")
            val type = Type.IndexedType(0)
            val lhsArg = QualifierArgument(lhsVariable.name, type)
            val rhsArg = QualifierArgument(rhsVariable.name, type)
            val term = BinaryTerm(lhsVariable, it, rhsVariable)
            Qualifier("Cmp", listOf(lhsArg, rhsArg), term)
        }

        val boolPredicate = Constant("Prop", Type.Function(1, listOf(Type.IndexedType(0), Type.NamedType("bool"))))
        val constants = listOf(boolPredicate)

        return with(context) {
            Query(qualifiers, constants, binds.values.sortedBy { it.id }, constraints, wfConstraints)
        }
    }

}
