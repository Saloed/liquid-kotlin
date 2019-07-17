package fixpoint

import fixpoint.predicate.*
import org.jetbrains.research.fixpoint.LiquidFixpointParser
import org.jetbrains.research.fixpoint.LiquidFixpointParserBaseVisitor

class SolutionFileVisitor : LiquidFixpointParserBaseVisitor<List<Solution>>() {
    override fun visitSolution_statement(ctx: LiquidFixpointParser.Solution_statementContext?): List<Solution> {
        if (ctx == null) return emptyList()
        val predicates = ctx.solution_clauses().expression().map { parseExpression(it) }
        val name = ctx.IDENTIFIER().text.trim().trimStart('$')
        return listOf(Solution(name, predicates))
    }

    override fun aggregateResult(aggregate: List<Solution>?, nextResult: List<Solution>?) = when {
        aggregate == null && nextResult == null -> emptyList()
        aggregate != null && nextResult != null -> aggregate + nextResult
        aggregate == null && nextResult != null -> nextResult
        aggregate != null && nextResult == null -> aggregate
        else -> throw IllegalArgumentException("impossible")
    }

    private fun parseExpression(clause: LiquidFixpointParser.ExpressionContext): Term {
        val identifiers = clause.IDENTIFIER()
        val number = clause.DECIMAL()
        val operation = clause.clause_elements_combinator()
        val expressions = clause.expression()
        return when {
            expressions.size == 2 && operation != null && identifiers.size == 0 -> {
                val (lhs, rhs) = expressions.map { parseExpression(it) }
                BinaryTerm(lhs, operation.binaryOperation, rhs)
            }
            expressions.size == 1 && operation != null && identifiers.size == 0 -> {
                val expr = expressions.map { parseExpression(it) }.first()
                UnaryTerm(operation.unaryOperation, expr)
            }
            expressions.size == 1 && operation != null && identifiers.size == 1 -> {
                val rhs = expressions.map { parseExpression(it) }.first()
                val lhs = VariableTerm(identifiers.first().text)
                BinaryTerm(lhs, operation.binaryOperation, rhs)
            }
            expressions.size == 0 && operation == null && identifiers.size == 0 && number != null -> {
                NumericValueTerm(Integer.valueOf(number.text))
            }
            expressions.size == 0 && operation == null && identifiers.size == 1 -> {
                VariableTerm(identifiers.first().text)
            }
            expressions.size == 0 && operation == null && identifiers.size == 2 -> {
                val (predicate, variable) = identifiers.map { it.text }
                PredicateTerm(predicate, VariableTerm(variable))
            }
            else -> throw IllegalArgumentException("Bad expresison")
        }
    }
}

val LiquidFixpointParser.Clause_elements_combinatorContext.binaryOperation: BinaryOperation
    get() = when {
        AND_AND() != null -> BinaryOperation.AND
        GTE() != null -> BinaryOperation.GTE
        LTE() != null -> BinaryOperation.LTE
        EQUALITY() != null -> BinaryOperation.EQUAL
        NONEQUALITY() != null -> BinaryOperation.NOT_EQUAL
        EQUAL() != null -> BinaryOperation.EQUAL
        GREATER() != null -> BinaryOperation.GREATER
        LESS() != null -> BinaryOperation.LOWER
        PLUS() != null -> BinaryOperation.PLUS
        MINUS() != null -> BinaryOperation.MINUS
        PREDICATE_EQUALITY() != null -> BinaryOperation.PREDICATE_EQUAL
        DIV_ASSIGN() != null -> BinaryOperation.NOT_EQUAL
        else -> throw IllegalArgumentException("Unknown binary operation ${this}")
    }


val LiquidFixpointParser.Clause_elements_combinatorContext.unaryOperation: UnaryOperation
    get() = when {
        BIT_NOT() != null -> UnaryOperation.NOT
        else -> throw IllegalArgumentException("Unknown unary operation ${this}")
    }
