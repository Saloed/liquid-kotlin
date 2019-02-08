import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.builtins.BuiltInsPackageFragment
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.search.usagesSearch.constructor
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinder
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.makeNotNullable
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class InitialLiquidTypeInfoCollector(
        val bindingContext: BindingContext,
        val psiElementFactory: KtPsiFactory,
        val fileFinder: VirtualFileFinder
) {


    private fun findPackageDescriptor(declaration: DeclarationDescriptor): PackageFragmentDescriptor {
        var parent = declaration
        while (true) {
            if (parent is PackageFragmentDescriptor) return parent
            if (parent is PackageViewDescriptor) return parent.fragments.first()
            parent = parent.containingDeclaration ?: break
        }
        if (parent is PackageFragmentDescriptor) return parent
        throw IllegalArgumentException("No package found for declaration $declaration")
    }

    private fun analyzeUnknownDeclaration(declaration: DeclarationDescriptor, expr: KtExpression): LiquidType? {
        val packageDescriptor = findPackageDescriptor(declaration)
        if (packageDescriptor is BuiltInsPackageFragment) {
            return KotlinBuiltInStub().analyzeUnknownBuiltInDeclaration(declaration, packageDescriptor, expr)
        } else {
            throw NotImplementedError("Non builtins classes are not implemented yet")
        }
    }

    private fun getReferenceExpressionType(expression: KtReferenceExpression): KotlinType? {
        val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression] ?: return null
        return when (targetDescriptor) {
            is PropertyDescriptor -> targetDescriptor.returnType
            is FunctionDescriptor -> targetDescriptor.returnType
            else -> targetDescriptor.findPsiWithProxy().safeAs<ValueDescriptor>()?.type
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
            getExpressionType(expression.receiverExpression)?.makeNotNullable()
        }
        else -> null
    }

    public fun collect(root: PsiElement, typeInfo: HashMap<PsiElement, LiquidType>) {
        val visitor = ExpressionTypingVisitor(typeInfo)
        root.accept(visitor)
    }


    inner class ExpressionTypingVisitor(val typeInfo: HashMap<PsiElement, LiquidType>) : KtTreeVisitorVoid() {

        override fun visitExpression(expression: KtExpression) {
            if (typeInfo[expression] != null) return
            val type = getExpressionType(expression)
            if (type != null) {
                val lqt = LiquidType.create(expression, type)
                typeInfo[expression] = lqt
            }
            super.visitExpression(expression)
        }

        override fun visitReferenceExpression(expression: KtReferenceExpression) {
            super.visitReferenceExpression(expression)
            val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression]
            if (targetDescriptor == null) {
                reportError("No target for reference ${expression.text}")
                return
            }
            val targetExpr = targetDescriptor.findPsiWithProxy().safeAs<KtExpression>()
            if (targetExpr != null) {
                return targetExpr.accept(this)
            }
            reportError("Declaration from external lib: $targetDescriptor")

            val text = DescriptorRenderer.COMPACT_WITH_MODIFIERS.render(targetDescriptor)
            val proxyExpr = psiElementFactory.createStringTemplate(text)

            val type = analyzeUnknownDeclaration(targetDescriptor, proxyExpr)

            if (type != null) {
                PsiProxy.storage[targetDescriptor] = proxyExpr
                typeInfo[proxyExpr] = type
            }
        }

        override fun visitPackageDirective(directive: KtPackageDirective) {
            return
        }

        override fun visitImportList(importList: KtImportList) {
            return
        }

    }
}