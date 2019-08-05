package annotation

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrSymbolOwner
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.utils.addToStdlib.safeAs


class RemoveAnnotationsFromIrTransformer : IrElementTransformerVoid() {

    val constraints = ElementConstraint()

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        val newStatements = arrayListOf<IrStatement>()
        for (statement in body.statements) {
            if (statement !is IrSimpleFunction) {
                newStatements.add(statement)
                continue
            }
            val lqtConstraint = getConstraintIfLqtConditionOrNull(statement)
            if (lqtConstraint == null) {
                newStatements.add(statement)
                continue
            }
            constraints.add(lqtConstraint.element, lqtConstraint.constraint)
        }
        if (newStatements.size == body.statements.size) {
            return super.visitBlockBody(body)
        }

        val newBody = IrBlockBodyImpl(body.startOffset, body.endOffset, newStatements)
        return super.visitBlockBody(newBody)
    }


    internal class ConstraintReferenceTransformer(val replaceFrom: IrElement, val replaceTo: IrExpression) : IrElementTransformerVoid() {
        override fun visitDeclarationReference(expression: IrDeclarationReference): IrExpression {
            if (replaceFrom == expression.symbol.owner) return replaceTo
            return super.visitDeclarationReference(expression)
        }
    }

    internal data class Constraint(val element:IrSymbolOwner, val constraint: IrExpression)

    private fun getConstraintIfLqtConditionOrNull(declaration: IrSimpleFunction): Constraint? {
        val body = declaration.body ?: return null
        val expression = body.getSingleExpressionOrNull().safeAs<IrCall>() ?: return null
        val functionName = expression.descriptor.fqNameSafe.asString()
        val isLqtConstraint = functionName == AnnotationProcessorWithPsiModification.lqtConditionEvaluatorFunctionName
        if (!isLqtConstraint) return null

        val constraintOnExpression = expression.getValueArgument(0)
                ?: throw IllegalArgumentException("Incorrect constraint")
        val constraintFunction = expression.getValueArgument(1)
                .safeAs<IrFunctionExpression>()
                ?.function
                ?: throw IllegalArgumentException("Incorrect constraint")
        val constraintItArgument = constraintFunction.valueParameters.first()
        val constraintExpression = constraintFunction.body
                ?.getSingleExpressionOrNull()
                ?: throw IllegalArgumentException("Incorrect constraint")

        val transformer = ConstraintReferenceTransformer(constraintItArgument, constraintOnExpression)
        val constraint = constraintExpression.transform(transformer, null)

        val constraintOnElement = when (constraintOnExpression) {
            is IrDeclarationReference -> constraintOnExpression.symbol.owner
            is IrSymbolOwner -> constraintOnExpression
            else -> TODO("constraint on element")
        }

        return Constraint(constraintOnElement, constraint)
    }

    private fun IrBody.getSingleExpressionOrNull(): IrExpression? {
        return when {
            this is IrExpressionBody -> this.expression
            this is IrBlockBody && statements.size == 1 -> {
                val statement = statements.first().safeAs<IrReturn>() ?: return null
                statement.value
            }
            else -> return null
        }
    }

}
