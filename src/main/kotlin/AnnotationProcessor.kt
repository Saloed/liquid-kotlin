import com.intellij.openapi.command.WriteCommandAction
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.liquidtype.LqT

val annotationFqName = FqName.fromSegments(LqT::class.qualifiedName!!.split("."))

data class AnnotationInfo(val declaration: KtDeclaration, val expression: KtExpression, val bindingContext: BindingContext) {
    override fun hashCode() = declaration.hashCode()
    override fun equals(other: Any?) = this === other || other is AnnotationInfo && declaration == other.declaration
}

class LqtAnnotationProcessor(
        val bindingContext: MyBindingContext,
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
        if (outerSpace !is SimpleFunctionDescriptor)
            throw NotImplementedError("Annotations support implemented only for function arguments")

        val funPsi = outerSpace.findPsi().safeAs<KtNamedFunction>()
                ?: throw IllegalStateException("Function without PSI")

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