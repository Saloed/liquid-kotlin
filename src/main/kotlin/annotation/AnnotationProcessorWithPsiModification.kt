package annotation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.getOrCreateBody
import org.jetbrains.kotlin.idea.intentions.ConvertToBlockBodyIntention
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.research.liquidtype.LqT
import pairNotNull
import zipMap

object AnnotationProcessorWithPsiModification {

    val lqtAnnotationFqName = FqName.fromSegments(LqT::class.qualifiedName!!.split("."))
    val lqtConditionEvaluatorFunctionName = "org.jetbrains.research.liquidtype.evaluateCondition"

    private fun addConditionsToFunction(function: KtFunction, conditions: List<KtExpression>) {
        if (conditions.isEmpty()) return
        if (!function.hasBody()) {
            if (function is KtConstructor<*>) return addConditionsToConstructor(function, conditions)
            TODO("Function without body: $function: ${function.text}")
        }

        if (!function.hasBlockBody()) {
            ConvertToBlockBodyIntention.convert(function)
        }

        val body = function.bodyBlockExpression
                ?: throw IllegalStateException("Function has body, but body is null: ${function.text}")
        for (cond in conditions) {
            body.addAfter(cond, body.lBrace)
        }
    }

    private fun addConditionsToConstructor(constructor: KtConstructor<*>, conditions: List<KtExpression>) {
        when (constructor) {
            is KtPrimaryConstructor -> {
                val classDeclaration = constructor.getContainingClassOrObject()
                val body = classDeclaration.getOrCreateBody()
                val initializer = KtPsiFactory(body).createAnonymousInitializer()
                val initializerBody = initializer.body.safeAs<KtBlockExpression>()
                        ?: throw IllegalStateException("Generated anonymous initializer has no body")
                for (cond in conditions) {
                    initializerBody.addAfter(cond, initializerBody.lBrace)
                }
                body.addAfter(initializer, body.lBrace)
                val newLine = KtPsiFactory(body).createNewLine()
                body.addAfter(newLine, body.lBrace)
            }
            is KtSecondaryConstructor -> {
                val body = constructor.getOrCreateBody()
                for (cond in conditions) {
                    body.addAfter(cond, body.lBrace)
                }
            }
            else -> throw IllegalArgumentException("Unexpected constructor type $constructor")
        }
    }


    private fun getLqtConditionIfExists(declaration: KtDeclaration) = declaration
            .let {
                val bindingContext = declaration.analyze()
                bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
            }?.let {
                it.annotations.findAnnotation(lqtAnnotationFqName)
            }?.let {
                it.allValueArguments[Name.identifier("condition")]
            }?.let {
                it.value as? String
            }

    private fun processFunction(project: Project, function: KtFunction) {
        val conditions = function.valueParameters
                .zipMap { getLqtConditionIfExists(it) }
                .pairNotNull()
                .mapIndexed { idx, (parameter, condition) ->
                    "fun condition_$idx(): Boolean =  $lqtConditionEvaluatorFunctionName(${parameter.name.orEmpty()}){ $condition }"
                }.map {
                    val conditionPsi = KtPsiFactory(project).createExpressionCodeFragment(it, null)
                    conditionPsi.children.toList().first().cast<KtNamedFunction>()
                }
        WriteCommandAction.runWriteCommandAction(project) {
            addConditionsToFunction(function, conditions)
        }
    }

    fun process(project: Project, file: KtFile): KtFile {
        val functions = file.collectDescendantsOfType<KtFunction> { true }
        for (function in functions) {
            processFunction(project, function)
        }
        return file
    }
}
