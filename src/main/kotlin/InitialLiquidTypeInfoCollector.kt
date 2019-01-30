import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.idea.search.usagesSearch.constructor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.types.KotlinType

class InitialLiquidTypeInfoCollector(val bindingContext: BindingContext) {

    private fun getReferenceExpressionType(expression: KtReferenceExpression): KotlinType? {
        val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression] ?: return null
        return when (targetDescriptor) {
            is PropertyDescriptor -> targetDescriptor.returnType
            is FunctionDescriptor -> targetDescriptor.returnType
            else -> targetDescriptor.findPsi().safeAs<ValueDescriptor>()?.type
        }
    }

    private fun getFunctionExpressionType(expression: KtFunction) =
            bindingContext[BindingContext.FUNCTION, expression]?.returnType

    private fun getDeclarationExpressionType(expression: KtDeclaration) =
            bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, expression].safeAs<ValueDescriptor>()?.type


    private fun getExpressionType(expression: KtExpression): KotlinType? = expression.getType(bindingContext)
            ?: when (expression) {
                is KtReferenceExpression -> getReferenceExpressionType(expression)
                is KtConstructor<*> -> getConstructorExpressionType(expression)
                is KtFunction -> getFunctionExpressionType(expression)
                is KtDeclaration -> getDeclarationExpressionType(expression)
                is KtQualifiedExpression -> getQualifiedExpressionType(expression)
                is KtConstructorCalleeExpression -> getConstructorType(expression)
                else -> null
            } ?: expression.let {
                val context = it.context?.context?.getElementTextWithTypes()
                reportError("No type info for $expression: ${expression.text}")
                null
            }

    private fun getConstructorExpressionType(expression: KtConstructor<*>) =
            expression.constructor?.returnType

    private fun getConstructorType(expression: KtConstructorCalleeExpression) = expression.typeReference?.let {
        bindingContext[BindingContext.TYPE, it]
    }

    private fun getQualifiedExpressionType(expression: KtQualifiedExpression) = when (expression) {
        is KtDotQualifiedExpression ->
            expression.selectorExpression?.let {
                getExpressionType(it)
            }
        is KtSafeQualifiedExpression -> {
            val a = expression
            null //todo
        }
        else -> null
    }


    public fun collect(root: PsiElement, typeInfo: HashMap<PsiElement, LiquidType>) = root.collectDescendantsOfType<KtExpression> { true }
            .zipMap { getExpressionType(it) }
            .pairNotNull()
            .mapSecond { it.unwrap() }
            .map { LiquidType.create(it.first, it.second) }
            .forEach {
                typeInfo[it.expression] = it
            }
}