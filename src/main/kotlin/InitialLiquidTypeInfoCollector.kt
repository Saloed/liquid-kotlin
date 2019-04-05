import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyzeAndGetResult
import org.jetbrains.kotlin.idea.caches.resolve.util.getJavaOrKotlinMemberDescriptor
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.isConstant
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.getTypeParameters
import org.jetbrains.kotlin.idea.search.usagesSearch.constructor
import org.jetbrains.kotlin.idea.search.usagesSearch.descriptor
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinder
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.constants.IntegerValueTypeConstant
import org.jetbrains.kotlin.resolve.constants.TypedCompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.types.typeUtil.makeNotNullable
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlinx.serialization.compiler.resolve.toSimpleType
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class InitialLiquidTypeInfoCollector private constructor(
        val outerBindingContext: BindingContext,
        val psiElementFactory: KtPsiFactory,
        val fileFinder: VirtualFileFinder
) {
    private val localBindingContext = MyBindingContext(outerBindingContext)
    var bindingContext = localBindingContext.asMergeable()


    private fun getReferenceExpressionType(expression: KtReferenceExpression): KotlinType? {
        val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression] ?: return null
        return when (targetDescriptor) {
            is CallableDescriptor -> targetDescriptor.returnType
            is ClassDescriptor -> targetDescriptor.toSimpleType(false)
            else -> targetDescriptor.findPsiWithProxy().safeAs<CallableDescriptor>()?.returnType
        }
    }

    private fun getFunctionExpressionType(expression: KtFunction) =
            bindingContext[BindingContext.FUNCTION, expression]?.returnType

    private fun getDeclarationExpressionType(expression: KtDeclaration): KotlinType? {
        val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, expression]
        return when (descriptor) {
            is CallableDescriptor -> descriptor.returnType
            is ClassDescriptor -> descriptor.toSimpleType(nullable = false)
            else -> null
        }
    }

    private fun selectType(myType: KotlinType, bcType: KotlinType): KotlinType {
        val constructorsEqual = myType.constructor == bcType.constructor
        val myParams = myType.getTypeParameters().toList()
        val bcParams = bcType.getTypeParameters().toList()

        return when {
            bcType.isSubtypeOf(myType) -> bcType
            myType.isSubtypeOf(bcType) -> myType
            constructorsEqual && myParams.size < bcParams.size -> myType
            constructorsEqual && myParams.size > bcParams.size -> bcType

            //fixme: tricky hack with Unit
            bcType.isUnit() -> myType
            myType.isUnit() -> bcType

            else -> {
                reportError("Inconsistent types: BC: $bcType  My: $myType")
                myType
            }
        }
    }

    private fun getExpressionType(expression: KtExpression): KotlinType? {
        val myType = when (expression) {
            is KtReferenceExpression -> getReferenceExpressionType(expression)
            is KtConstructor<*> -> getConstructorExpressionType(expression)
            is KtFunction -> getFunctionExpressionType(expression)
            is KtDeclaration -> getDeclarationExpressionType(expression)
            is KtQualifiedExpression -> getQualifiedExpressionType(expression)
            is KtConstructorCalleeExpression -> getConstructorType(expression)
            else -> null
        }
        val bcType = expression.getType(bindingContext)

        return when {
            myType == null -> bcType
            bcType == null -> myType
            myType == bcType -> bcType
            else -> selectType(myType, bcType)
        }
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


    inner class ReferenceExpressionResolver : KtTreeVisitorVoidWithTypeElement() {

        private val visited = HashSet<PsiElement>()
        val nonKotlinElements = ArrayList<PsiElement>()

        private fun analyzeExpressionForBindingContext(expression: KtExpression) {
            val exprContext = expression.analyzeAndGetResult().apply { throwIfError() }.bindingContext
            bindingContext = bindingContext.mergeWith(exprContext)
        }


        private fun findSourceForReference(expression: KtReferenceExpression): PsiElement? {
            val flags = TargetElementUtil.getInstance().allAccepted and TargetElementUtil.ELEMENT_NAME_ACCEPTED.inv()
            val editor = expression.findOrCreateEditor()
            val offset = expression.startOffset
            editor.caretModel.moveToOffset(offset)
            val element = TargetElementUtil.getInstance().findTargetElement(editor, flags, offset)
            EditorFactory.getInstance().releaseEditor(editor)
            return element
        }

        private fun searchForReferenceTarget(expression: KtReferenceExpression): KtDeclaration? {
            val element = findSourceForReference(expression) ?: return null
            if (element !is KtElement) {
                nonKotlinElements.add(element)
                if (element !is PsiMember)
                    return null
                val descriptor = element.getJavaOrKotlinMemberDescriptor()
                if (descriptor == null) {
                    reportError("No descriptor for element $element")
                    return null
                }
                localBindingContext[BindingContext.REFERENCE_TARGET, expression] = descriptor
                PsiProxy.storage[descriptor] = element
                return null
            }
            val declaration = element.safeAs<KtDeclaration>()
            val descriptor = declaration?.descriptor
            if (descriptor != null) {
                localBindingContext[BindingContext.REFERENCE_TARGET, expression] = descriptor
            }
            return declaration
        }

        private fun getReferenceTargetSource(target: DeclarationDescriptor, expression: KtReferenceExpression): KtExpression? {
            val element = target.findPsiWithProxy()
                    ?: target.findSource(expression)
                    ?: findSourceForReference(expression)
                    ?: return null
            PsiProxy.storage[target] = element
            if (element is KtExpression) return element
            if (element !is KtElement) nonKotlinElements.add(element)
            return null
        }

        override fun visitElement(element: PsiElement) {
            if (element !in visited) {
                visited.add(element)
                super.visitElement(element)
            }
        }

        override fun visitReferenceExpression(expression: KtReferenceExpression) {
            super.visitReferenceExpression(expression)

            val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression]

            val targetExpr: KtExpression? = if (targetDescriptor != null) {
                getReferenceTargetSource(targetDescriptor, expression)
            } else {
                searchForReferenceTarget(expression)
            }

            if (targetExpr == null) {
                val text = expression.text
                reportError("No target for reference $text")
                return
            }

            analyzeExpressionForBindingContext(targetExpr)
            PsiProxy.storage[targetDescriptor] = targetExpr
            targetExpr.accept(this)

        }

        override fun visitPackageDirective(directive: KtPackageDirective) {
            return
        }

        override fun visitImportList(importList: KtImportList) {
            return
        }

        override fun visitTypeElement(type: KtTypeElement) {
            return
        }

    }

    inner class ExpressionTypingVisitor(
            val typeInfo: HashMap<PsiElement, Optional<LiquidType>>
    ) : KtTreeVisitorVoidWithTypeElement() {

        override fun visitExpression(expression: KtExpression) {
            if (typeInfo[expression] != null) return
            typeInfo[expression] = Optional.empty()

            val constantType = checkConstantType(expression)
            if (constantType != null) {
                val lqt = ConstantLiquidType.create(expression, constantType)
                typeInfo[expression] = Optional.of(lqt)
                return
            }

            super.visitExpression(expression)

            val type = getExpressionType(expression)
            if (type != null) {
                val lqt = LiquidType.create(expression, type)
                typeInfo[expression] = Optional.of(lqt)
            } else {
                val context = expression.context?.context
                val ctxText = context?.text
                val ctxPsi = context?.getElementTextWithTypes()
                val text = expression.text
                reportError("No type info for $expression : ${expression.text}")
            }
        }


        override fun visitReferenceExpression(expression: KtReferenceExpression) {
            super.visitReferenceExpression(expression)

            val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression]
            if (targetDescriptor == null) {
                reportError("No target for reference ${expression.text}")
                return
            }

            val targetExprPsi = targetDescriptor.findPsiWithProxy()
            val targetExpr = targetExprPsi.safeAs<KtExpression>()
            if (targetExpr == null) {
                when (targetExprPsi) {
                    null -> reportError("Target descriptor without source ${expression.text}")
                    else -> reportError("Target expression is not KtExpression ${expression.text}")
                }
                return
            }

            targetExpr.accept(this)
        }

        override fun visitPackageDirective(directive: KtPackageDirective) {
            return
        }

        override fun visitImportList(importList: KtImportList) {
            return
        }

        override fun visitTypeElement(type: KtTypeElement) {
            return
        }


        private fun checkConstantType(expression: KtExpression): KotlinType? {
            if (!expression.isConstant()) return null
            val value = ConstantExpressionEvaluator.getConstant(expression, bindingContext) ?: return null
            return when (value) {
                is TypedCompileTimeConstant -> value.type
                is IntegerValueTypeConstant -> getExpressionType(expression)?.let { value.getType(it) }
                else -> null
            }
        }

    }

    companion object {
        fun collect(
                bindingContext: BindingContext,
                psiElementFactory: KtPsiFactory,
                fileFinder: VirtualFileFinder,
                root: PsiElement, typeInfo:
                HashMap<PsiElement, LiquidType>
        ): MergedBindingContext {
            val collector = InitialLiquidTypeInfoCollector(bindingContext, psiElementFactory, fileFinder)
            val optionalTypeInfo = HashMap<PsiElement, Optional<LiquidType>>()
            optionalTypeInfo.putAll(typeInfo.map { it.key to Optional.of(it.value) })
            val referenceResolver = collector.ReferenceExpressionResolver()
            val visitor = collector.ExpressionTypingVisitor(optionalTypeInfo)

            root.accept(referenceResolver)
            val nonKotlinElements = referenceResolver.nonKotlinElements
            val resolvedNonKtElements = ClassFileElementResolver.resolve(nonKotlinElements)
            for ((elem, lqt) in resolvedNonKtElements) {
                optionalTypeInfo[elem] = Optional.of(lqt)
            }
            root.accept(visitor)
            val collectedInfo = optionalTypeInfo
                    .filterNot { it.key in typeInfo }
                    .filter { it.value.isPresent }
                    .map { it.key to it.value.get() }
            typeInfo.putAll(collectedInfo)
            return collector.bindingContext
        }

    }
}
