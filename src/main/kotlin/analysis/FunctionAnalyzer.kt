package analysis

import annotation.ElementConstraint
import fixpoint.*
import fixpoint.predicate.*
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import zipMap

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
        val functionConstraints = mutableMapOf<FunctionDescriptor, FunctionConstraint>()
        for (function in functions) {
            functionConstraints[function.descriptor] = analyzeFunction(function, functionConstraints)
        }
    }


    private fun createFunctionParameters(parameters: List<IrValueParameter>, context: AnalysisContext): ParameterConstraints {
        val binds = parameters.map {
            context.createBind(it.name.asString(), it.type, emptyList())
        }
        parameters.zip(binds).forEach { (parameter, bind) ->
            context[parameter] = bind
        }
        val parameterConstraints = parameters.map {
            val analyzer = ExpressionAnalyzer(context)
            constraints.get(it).map { constraintExpr ->
                constraintExpr.accept(analyzer, null)
            }.let {
                simplifyConstraints(it)
            }
        }
        val bindsWithConstrains = binds.zip(parameterConstraints)
                .map { (bind, constrains) ->
                    val predicate = bind.predicate.copy(constraint = constrains)
                    bind.copy(predicate = predicate)
                }
        parameters.zip(bindsWithConstrains).forEach { (parameter, bind) ->
            context[parameter] = bind
        }
        val paramsToConstraints = parameters.zip(parameterConstraints).toMap()
        val dependencyCollector = TermDependencyCollector(context)
        val dependencies = parameterConstraints.flatten()
                .map { dependencyCollector.collect(it) }
                .fold(TermDependencyCollector.TermDependency.EMPTY) { deps, current -> deps.merge(current) }
        return ParameterConstraints(paramsToConstraints, dependencies)
    }

    private fun createFunctionConstraint(function: IrSimpleFunction, context: AnalysisContext) {
        val dummyValue = when {
            function.returnType.isInt() -> NumericValueTerm(17)
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
        context[function] = returnBind

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

    private fun analyzeFunction(
            function: IrSimpleFunction,
            functionConstraints: MutableMap<FunctionDescriptor, FunctionConstraint>
    ): FunctionConstraint {
        val parameters = function.valueParameters + listOfNotNull(
                function.dispatchReceiverParameter, function.extensionReceiverParameter
        )

        val context = AnalysisContext(functionConstraints)

        val parameterConstraints = createFunctionParameters(parameters, context)

        val body = function.body
        when (body) {
            is IrExpressionBody -> ExpressionAnalyzer.analyze(body.expression, context)
            is IrBlockBody -> BlockAnalyzer.analyze(body, context)
            else -> TODO()
        }

        createFunctionConstraint(function, context)

        simplifyBinds(context)

        val query = buildQueryForContext(context)

        val solution = Solver.solve(query)

        showDebugInfo(function, query, solution)

        return getFunctionConstraint(function, solution, parameters, parameterConstraints)
    }


    private class DummyUnusedConstraintDetector(val usedNames: Set<String>) : TermTransformer {
        private var useUnusedNames: Boolean = false

        override fun transformVariableTerm(term: VariableTerm): Term {
            if (term.name !in usedNames) useUnusedNames = true
            return super.transformVariableTerm(term)
        }

        fun check(term: Term): Boolean {
            term.transform(this)
            return useUnusedNames
        }
    }

    private fun getFunctionConstraint(
            function: IrSimpleFunction,
            solutions: List<Solution>,
            parameters: List<IrValueParameter>,
            parameterConstraints: ParameterConstraints
    ): FunctionConstraint {
        val returnConstraint = solutions.find { it.name == "return_value_fn" }
                ?: return FunctionConstraintEmpty(parameterConstraints)
        val arguments = parameters.zipMap { it.name.asString() }.toMap()
        val parameterNames = arguments.values.toSet()
        val okNames = parameterNames + "return"
        val constraint = returnConstraint.predicates
                .filterNot { DummyUnusedConstraintDetector(okNames).check(it) }
        return FunctionConstraintImpl(function.descriptor, constraint, arguments, "return", parameterConstraints)
    }


    private fun showDebugInfo(function: IrSimpleFunction, query: Query, solutions: List<Solution>) {

        println(function.name)
        println(query.print())
        solutions.forEach {
            println(it.print())
        }

    }

    private fun simplifyConstraints(constraints: List<Term>): List<Term> {
        if (constraints.isEmpty()) return constraints
        return constraints.filterNot {
            it is BooleanValueTerm && it.value
        }
    }

    private fun simplifyBinds(context: AnalysisContext) = with(context) {
        for ((key, bind) in binds) {
            val constraints = bind.predicate.constraint
            if (constraints.isEmpty()) continue
            val simplified = simplifyConstraints(constraints)
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
        } + cmpOperators.map {
            val lhsVariable = VariableTerm("v")
            val type = Type.NamedType("int")
            val lhsArg = QualifierArgument(lhsVariable.name, type)
            val term = BinaryTerm(lhsVariable, it, NumericValueTerm(0))
            Qualifier("CmpZ", listOf(lhsArg), term)
        }

        val boolPredicate = Constant("Prop", Type.Function(1, listOf(Type.IndexedType(0), Type.NamedType("bool"))))
        val constants = listOf(boolPredicate)

        return with(context) {
            Query(qualifiers, constants, binds.values.sortedBy { it.id }, constraints, wfConstraints)
        }
    }

}
