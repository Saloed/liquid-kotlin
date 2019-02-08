import com.intellij.debugger.ui.impl.watch.NodeDescriptorImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.ui.tree.ValueMarkup
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.analysis.analyzeInContext
import org.jetbrains.kotlin.idea.caches.resolve.analyzeAndGetResult
import org.jetbrains.kotlin.idea.caches.resolve.analyzeFully
import org.jetbrains.kotlin.idea.caches.resolve.analyzeWithAllCompilerChecks
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinCodeFragmentFactory
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.liquidtype.LqT

val annotationFqName = FqName.fromSegments(LqT::class.qualifiedName!!.split("."))

data class AnnotationInfo(val declaration: KtDeclaration, val expression: KtExpression, val bindingContext: BindingContext) {
    override fun hashCode() = declaration.hashCode()
    override fun equals(other: Any?) = this === other || other is AnnotationInfo && declaration == other.declaration
}

class LqtAnnotationProcessor(
        val bindingContext: BindingContext,
        val psiElementFactory: KtPsiFactory,
        val resolutionFacade: ResolutionFacade
) {

    private fun extractConditionFromAnnotatedDeclaration(declaration: KtDeclaration) =
            bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]?.let {
                it.annotations.findAnnotation(annotationFqName)
            }?.let {
                it.allValueArguments[Name.identifier("condition")]
            }?.let { it.value as? String }


    fun lqtExprForDeclaration(declaration: KtDeclaration, constraint: String): AnnotationInfo {
        val desc = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]!!
        val outerSpace = desc.containingDeclaration

        if (declaration !is KtParameter)
            throw NotImplementedError("Annotations support implemented only for function arguments")
        if (outerSpace !is FunctionDescriptor)
            throw NotImplementedError("Annotations support implemented only for function arguments")

        val funPsi = outerSpace.findPsiWithProxy() ?: throw IllegalStateException("PSI for declaration context not found")

        val newExprFragment = psiElementFactory.createExpressionCodeFragment(constraint, funPsi)
        WriteCommandAction.runWriteCommandAction(newExprFragment.project) {
            newExprFragment.collectDescendantsOfType<KtNameReferenceExpression> {
                it.getReferencedName() == "it"
            }.mapNotNull {
                it.getIdentifier()
            }.forEach {
                val newIdentifier = psiElementFactory.createIdentifier(desc.name.identifier)
                it.replace(newIdentifier)
            }
        }

        val newExprAnalysisResult = resolutionFacade.analyzeWithAllCompilerChecks(listOf(newExprFragment))
        val newExprBindingContext = newExprAnalysisResult.bindingContext

        val newExpr = newExprFragment.allChildren.first as? KtExpression
                ?: throw IllegalStateException("Error in constraint expression generation")



        return AnnotationInfo(declaration, newExpr, newExprBindingContext)
    }

    fun processLqtAnnotations(file: KtFile) =
            file.collectDescendantsOfType<KtDeclaration> { true }
                    .zipMap { extractConditionFromAnnotatedDeclaration(it) }
                    .pairNotNull()
                    .map { lqtExprForDeclaration(it.first, it.second) }

}