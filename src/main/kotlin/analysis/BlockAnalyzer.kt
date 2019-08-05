package analysis

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

class BlockAnalyzer(val context: AnalysisContext) : IrElementVisitorVoid {
    override fun visitElement(element: IrElement) {
        throw IllegalArgumentException("Block body expected, but $element")
    }


    val result: SomeDummyAnalysisResult
        get() = TODO("No analysis result")


    private fun analyzeStatement(statement: IrStatement) = when (statement) {
        is IrExpression -> ExpressionAnalyzer.analyze(statement, context)
        else -> TODO("Statement is not expression: $statement")
    }

    override fun visitBlockBody(body: IrBlockBody) {
        body.statements.map(this::analyzeStatement)
    }

    companion object {
        fun analyze(expression: IrBlockBody, context: AnalysisContext): SomeDummyAnalysisResult {
            val analyzer = BlockAnalyzer(context)
            expression.accept(analyzer, null)
            return SomeDummyAnalysisResult()
        }
    }
}
