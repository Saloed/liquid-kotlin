import com.intellij.psi.PsiElement
import com.intellij.util.containers.toMutableSmartList
import fixpoint.*
import fixpoint.predicate.*
import fixpoint.predicate.BinaryTerm
import fixpoint.predicate.Term
import org.jetbrains.kotlin.idea.refactoring.fqName.getKotlinFqName
import org.jetbrains.research.kex.ktype.KexBool
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.ChainState
import org.jetbrains.research.kex.state.ChoiceState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.EqualityPredicate
import org.jetbrains.research.kex.state.predicate.PredicateType
import org.jetbrains.research.kex.state.term.*
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.CmpOpcode

typealias KexTerm = org.jetbrains.research.kex.state.term.Term
typealias KexPredicate = org.jetbrains.research.kex.state.predicate.Predicate
typealias KexBinaryTerm = org.jetbrains.research.kex.state.term.BinaryTerm

object FixpointAnalyzer {


    fun analyze(data: Map<PsiElement, LiquidType>) {
        val functions = data.values.filterIsInstance<FunctionLiquidType>()
        val dependencies = functions.zipMap { function ->
            function.collectDescendants(
                    withSelf = false,
                    predicate = { it is CallExpressionLiquidType },
                    needVisit = { it !is CallExpressionLiquidType }
            ).map { it as CallExpressionLiquidType }.map { it.function }
        }.toMap()

        val unresolvedFunctions = functions.toMutableList()
        val unprocessedFunctions = mutableListOf<FunctionLiquidType>()
        val results = HashMap<FunctionLiquidType, List<Solution>>()

        while (true) {
            val current = unresolvedFunctions.filter { dependencies[it]?.all { it in results } ?: false }
            unresolvedFunctions.removeAll(current)
            if (current.isEmpty()) break

            for (function in current) {
                println()
                println(function.expression.getKotlinFqName())
                val converter = FunctionLiquidTypeConverter()
                converter.convert(function)
                val query = converter.makeQuery()
                println(query.print())
                val solution = Solver.solve(query)
                println(solution)
                if (solution.isEmpty()) {
                    unprocessedFunctions.add(function)
                    continue
                }
                results[function] = solution
            }
        }

        println(unresolvedFunctions.map { it.expression.getKotlinFqName() })
        println(unprocessedFunctions.map { it.expression.getKotlinFqName() })
        println(results)

    }
}

class FunctionLiquidTypeConverter() {
    val qualifiers = mutableListOf<Qualifier>()
    val constants = mutableListOf<Constant>()
    val binds = mutableListOf<Bind>()
    val constraints = mutableListOf<Constraint>()
    val wf = mutableListOf<WFConstraint>()

    val visited = mutableSetOf<LiquidType>()

    init {
        val boolPredicate = Constant("Prop", Type.Function(1, listOf(Type.IndexedType(0), Type.NamedType("bool"))))
        constants.add(boolPredicate)
        generateQualifiers()
    }


    fun addBind(bind: Bind) {
        binds.add(bind.copy(id = binds.size))
    }

    fun generateQualifiers() {
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
        this.qualifiers.addAll(qualifiers)
    }

    fun makeQuery() = Query(qualifiers, constants, binds, constraints, wf)


    val LiquidType.fixpointVarName
        get() = "var_${variable.name}"

    val LiquidType.fixpointType
        get() = Type.NamedType(type.name)


    fun convertFunctionArgument(arg: LiquidType) {
        val dependency = arg.collectDescendants(withSelf = true) { true }
        val binds = dependency.map {
            val pred = Predicate(it.fixpointVarName, it.fixpointType, convertPredicateState(it.getPredicate().simplify(), it))
            Bind(-1, it.fixpointVarName, pred)
        }
        visited.addAll(dependency)
        binds.forEach { addBind(it) }
    }

    fun getFunctionName(lqt: FunctionLiquidType): String {
        val functionName = lqt.expression.getKotlinFqName() ?: TODO("No name for function")
        return "function_$functionName"
    }

    fun goDeeper(lqt: LiquidType) {
        if (lqt in visited) return
        visited.add(lqt)
        if (lqt is CallExpressionLiquidType) {
            val functionName = getFunctionName(lqt.function)
            //todo: get function analysis results
            val analysisResults = emptyList<Term>()
            val pred = Predicate(lqt.fixpointVarName, lqt.fixpointType, analysisResults)
            val bind = Bind(-1, lqt.fixpointVarName, pred)
            addBind(bind)
            return
        }
        lqt.dependsOn.forEach { goDeeper(it) }
        val pred = Predicate(lqt.fixpointVarName, lqt.fixpointType, convertPredicateState(lqt.getPredicate().simplify(), lqt))
        val bind = Bind(-1, lqt.fixpointVarName, pred)
        addBind(bind)
    }

    fun createWf(varName: String, type: Type, functionName: String, env: Environment) {
        val kvarName = "kvar_$varName"
        val wfPredicate = Predicate(kvarName, type, listOf(VariableTerm("$$functionName")))
        val wfConstraint = WFConstraint(env, wfPredicate, 0)
        wf.add(wfConstraint)
    }

    fun convertFunctionBody(returnValue: LiquidType) {
        goDeeper(returnValue)
    }

    fun VariableTerm.assignTo(term: Term): AssigmentTerm {
        val newVariable = VariableTerm("kvar_$name")
        return AssigmentTerm(newVariable, term)
    }

    fun convert(lqt: FunctionLiquidType) {
        val functionName = getFunctionName(lqt)
        val resultName = "${functionName}_result"
        val resultVariable = VariableTerm(resultName)

        val returnValue = lqt.returnValue ?: return //TODO: Functions without return are not supported


        lqt.arguments.mapValues { (_, it) -> convertFunctionArgument(it) }
        lqt.dispatchArgument?.let { convertFunctionArgument(it) }
        lqt.extensionArgument?.let { convertFunctionArgument(it) }

        val argumentsEnvironment = collectEnvironment()

        convertFunctionBody(returnValue)
//        lqt.dependsOn
//                .filterNot { it in lqt.allArguments }
//                .filterNot { it == lqt.returnValue }
//                .map { it.getPredicate() }

        run {
            val returnValueVariable = VariableTerm(returnValue.fixpointVarName)
            val constraintName = "result_assigment"
            val constraintVar = VariableTerm(constraintName)
            val lhs = Predicate(constraintName, lqt.fixpointType, listOf(BinaryTerm(constraintVar, BinaryOperation.EQUAL, returnValueVariable)))
            val assigment = resultVariable.assignTo(constraintVar)
            val rhs = Predicate(constraintName, lqt.fixpointType, listOf(SubstitutionTerm(functionName, listOf(assigment))))
            val constraint = Constraint(collectEnvironment(), lhs, rhs, -1)
            constraints.add(constraint)
        }

//        run {
//            val assigment = resultVariable.assignTo(resultVariable)
//            val substitutionTerm = SubstitutionTerm(functionName, listOf(assigment))
//            val predicate = Predicate(resultName, lqt.fixpointType, listOf(substitutionTerm))
//            val bind = Bind(-1, resultName, predicate)
//            addBind(bind)
//        }

        // fixme: hack constraint
        run {
            val hackName = "hack_var"
            val hackVariable = VariableTerm(hackName)
            val intType = Type.NamedType("int")
            val hackAssigment = resultVariable.assignTo(hackVariable)
            val lhs = Predicate(hackName, intType, listOf(SubstitutionTerm(functionName, listOf(hackAssigment))))
            val rhs = Predicate(hackName, intType, listOf(BinaryTerm(hackVariable, BinaryOperation.EQUAL, NumericValueTerm(0))))
            val constraint = Constraint(argumentsEnvironment, lhs, rhs, -1)
            constraints.add(constraint)
        }

        enumerateConstraints()

        createWf(resultName, lqt.fixpointType, functionName, argumentsEnvironment)
    }

    fun collectEnvironment() = Environment(binds.map { it.id }.toSet().toList())

    fun enumerateConstraints() {
        var idx = 0
        constraints.replaceAll { it.copy(id = idx++) }
    }

    fun convertPredicateState(ps: PredicateState, lqt: LiquidType): List<Term> = when (ps) {
        is BasicState -> ps.predicates.map { convertPredicate(it) }
        is ChainState -> convertPredicateState(ps.base, lqt) + convertPredicateState(ps.curr, lqt)
        is ChoiceState -> {
            val me = VariableTerm(lqt.fixpointVarName)
            val name = "choice_${lqt.fixpointVarName}"
            val assigment = me.assignTo(me)
            val term = SubstitutionTerm(name, listOf(assigment))

            ps.choices.forEach {
                val path = it.filter { it.type is PredicateType.Path }.simplify()
                val state = it.filter { it.type is PredicateType.State }.simplify()
                val pathTerms = convertPredicateState(path, lqt)
                val stateTerms = convertPredicateState(state, lqt)

                val lhs = Predicate(lqt.fixpointVarName, lqt.fixpointType, pathTerms + stateTerms)
                val rhs = Predicate(lqt.fixpointVarName, lqt.fixpointType, listOf(term))
                val constraint = Constraint(collectEnvironment(), lhs, rhs, -1)
                constraints.add(constraint)
            }

            createWf(me.name, lqt.fixpointType, name, collectEnvironment())

            listOf(term)
        }
        else -> TODO("convert for $ps is not implemented")
    }

    fun booleanEqualityTrickyHack(lhv: KexTerm, rhv: KexTerm): Term? {
        val lhs = convertTerm(lhv)
        val rhs = convertTerm(rhv)
        if (lhs !is VariableTerm) return null
        if (rhs is VariableTerm) return BinaryTerm(lhs.boolPredicate, BinaryOperation.PREDICATE_EQUAL, rhs.boolPredicate)
        if (rhs is BooleanValueTerm) return if (rhs.value) lhs.boolPredicate else UnaryTerm(UnaryOperation.NOT, lhs.boolPredicate)
        return BinaryTerm(lhs.boolPredicate, BinaryOperation.PREDICATE_EQUAL, rhs)
    }

    fun trickyHackWithCmpTerm(term: CmpTerm): Term {
        if (term.lhv.type is KexBool && term.opcode is CmpOpcode.Eq) {
            val hackedResult = booleanEqualityTrickyHack(term.lhv, term.rhv)
            if (hackedResult != null) return hackedResult
        }
        return BinaryTerm(convertTerm(term.lhv), convertCmpOpcode(term.opcode), convertTerm(term.rhv))
    }

    fun trickyHackWithEqualityPredicate(predicate: EqualityPredicate): Term {
        if (predicate.lhv.type is KexBool) {
            val hackedResult = booleanEqualityTrickyHack(predicate.lhv, predicate.rhv)
            if (hackedResult != null) return hackedResult
        }
        return BinaryTerm(convertTerm(predicate.lhv), BinaryOperation.EQUAL, convertTerm(predicate.rhv))
    }

    fun convertPredicate(predicate: KexPredicate): Term = when (predicate) {
        is EqualityPredicate -> trickyHackWithEqualityPredicate(predicate)
        else -> TODO("Unsupported predicate: $predicate")
    }


    val Term.boolPredicate
        get() = PredicateTerm("Prop", this)

    fun convertTerm(term: KexTerm): Term = when (term) {
        is ValueTerm -> VariableTerm("var_${term.name}")
        is NegTerm -> UnaryTerm(UnaryOperation.NOT, convertTerm(term.operand))
        is KexBinaryTerm -> BinaryTerm(convertTerm(term.lhv), convertBinaryOpcode(term.opcode), convertTerm(term.rhv))
        is CmpTerm -> trickyHackWithCmpTerm(term)
        is ConstIntTerm -> NumericValueTerm(term.value)
        is ConstBoolTerm -> BooleanValueTerm(term.value)
        else -> TODO("Unsupported term: $term")
    }

    fun convertCmpOpcode(op: CmpOpcode) = when (op) {
        is CmpOpcode.Eq -> BinaryOperation.EQUAL
        is CmpOpcode.Neq -> BinaryOperation.NOT_EQUAL
        is CmpOpcode.Lt -> BinaryOperation.LOWER
        is CmpOpcode.Gt -> BinaryOperation.GREATER
        is CmpOpcode.Le -> BinaryOperation.LTE
        is CmpOpcode.Ge -> BinaryOperation.GTE
        is CmpOpcode.Cmp -> TODO()
        is CmpOpcode.Cmpg -> TODO()
        is CmpOpcode.Cmpl -> TODO()
    }

    fun convertBinaryOpcode(opcode: BinaryOpcode) = when (opcode) {
        is BinaryOpcode.Add -> BinaryOperation.PLUS
        is BinaryOpcode.Sub -> BinaryOperation.MINUS
        is BinaryOpcode.Mul -> TODO()
        is BinaryOpcode.Div -> TODO()
        is BinaryOpcode.Rem -> TODO()
        is BinaryOpcode.Shl -> TODO()
        is BinaryOpcode.Shr -> TODO()
        is BinaryOpcode.Ushr -> TODO()
        is BinaryOpcode.And -> TODO()
        is BinaryOpcode.Or -> TODO()
        is BinaryOpcode.Xor -> TODO()
        is BinaryOpcode.Implies -> TODO()
    }

}
