import com.intellij.find.FindManager
import com.intellij.find.findUsages.FindUsagesManager
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.find.impl.FindManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileImpl
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.usages.UsageInfo2UsageAdapter
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.kotlin.idea.caches.resolve.KotlinCacheServiceImpl
import org.jetbrains.kotlin.idea.findUsages.KotlinFunctionFindUsagesOptions
import org.jetbrains.kotlin.idea.findUsages.KotlinPropertyFindUsagesOptions
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.liquidtype.LqT
import java.util.*
import kotlin.collections.HashSet
import kotlin.coroutines.experimental.suspendCoroutine


object LiquidTypeAnalyzer {

    val annotationFqName = FqName.fromSegments(LqT::class.qualifiedName!!.split("."))

    lateinit var findUsagesManager: FindUsagesManager
    lateinit var findFunctionUsagesOptions: FindUsagesOptions
    lateinit var findPropertyUsagesOptions: FindUsagesOptions

    lateinit var bindingContext: BindingContext

    @JvmStatic
    fun analyze(project: Project) {
        val projectRootManager = ProjectRootManager.getInstance(project)
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        findUsagesManager = (FindManager.getInstance(project) as FindManagerImpl).findUsagesManager
        findPropertyUsagesOptions = KotlinPropertyFindUsagesOptions(project)
        findFunctionUsagesOptions = KotlinFunctionFindUsagesOptions(project)

        val projectFilesAndDirectories = ArrayList<VirtualFile>()
        projectRootManager.fileIndex.iterateContent { projectFilesAndDirectories.add(it) }
        val allKtFilesPsi = projectFilesAndDirectories.asSequence()
                .filterIsInstance(VirtualFileImpl::class.java)
                .map(psiManager::findFile)
                .filterIsInstance(KtFile::class.java)
                .toList()

        val facade = KotlinCacheServiceImpl(project).getResolutionFacade(allKtFilesPsi)
        bindingContext = facade.analyzeWithAllCompilerChecks(allKtFilesPsi).bindingContext

        for (file in allKtFilesPsi) {
            if (!file.name.endsWith("Mian.kt")) continue
            analyzeSingleFile(file)
            val a = 3
        }


    }

    fun extractConditionFromAnnotatedDeclaration(declaration: KtDeclaration) =
            bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]?.let {
                it.annotations.findAnnotation(annotationFqName)
            }?.let {
                it.allValueArguments[Name.identifier("condition")]
            }?.let { it.value as? String }


    fun analyzeAnnotations(file: KtFile) =
            file.collectDescendantsOfType<KtDeclaration> { true }
                    .zipMap { extractConditionFromAnnotatedDeclaration(it) }
                    .pairNotNull()
                    .forEach { LiquidTypeInfoStorage[it.first] = Term.Value(it.second) }

    fun analyzeSingleFile(file: KtFile) {
        analyzeAnnotations(file)
        val visitedElements = HashSet<PsiElement>()

        do {
            val elementsToAnalyze = LiquidTypeInfoStorage.unsafeTypeConstraints().keys
                    .filter { it !in visitedElements }
                    .also { visitedElements.addAll(it) }
            elementsToAnalyze.filter { it is KtParameter || it is KtFunction }
                    .zipMap { findUsages(it) }
                    .map { (element, usages) -> usages.map { analyzeUsage(element, it) } }

        } while (elementsToAnalyze.isNotEmpty())
    }

    fun analyzeUsage(element: PsiElement, usage: PsiElement) = when (usage) {
        is KtNameReferenceExpression -> analyzeUsage(element, usage)
        else -> Unit
    }

    fun expressionAssignee(expr: KtExpression): PsiElement? {
        val parent = expr.parent ?: return null
        val isAssignee = parent.allChildren.toList()
                .filterIsInstance<LeafPsiElement>()
                .map { it.elementType }
                .contains(KtTokens.EQ)
        return if (isAssignee) parent else null
    }


    fun analyzeUsage(element: PsiElement, usage: KtNameReferenceExpression) {
        val parent = usage.parent
        if (parent is KtCallExpression) {
            return analyzeCallExpression(element as KtFunction, parent)
        }

        val ifParent = usage.parentOfType<KtIfExpression>()
        if (ifParent != null) {
            return analyzeIfExpression(ifParent)
        }
    }


    fun analyzeCallExpression(funElement: KtFunction, expr: KtCallExpression) {
        val assignee = expressionAssignee(expr)
        if (assignee != null) {
            LiquidTypeInfoStorage.putIfNotExists(funElement, Term.TRUE)
            LiquidTypeInfoStorage[assignee] = Term.Variable(funElement)
        }

        val arguments = expr.getCall(bindingContext)?.valueArguments ?: return
        if (arguments.any { it.isNamed() }) return
        val funArguments = arguments.map { it.getArgumentExpression() }

        val funParameters = bindingContext[BindingContext.FUNCTION, funElement]?.valueParameters ?: return
        val funParameterElements = funParameters.map { it.findPsi()!! }
        funParameterElements.forEach { LiquidTypeInfoStorage.putIfNotExists(it, Term.TRUE) }

        for ((arg, funParameterElement) in funArguments.zip(funParameterElements)) {
            if (arg is KtCallExpression) {
                val callFunElement = bindingContext[BindingContext.RESOLVED_CALL, arg.getCall(bindingContext)]
                        ?.candidateDescriptor
                        ?.findPsi()
                        ?: continue
                LiquidTypeInfoStorage[callFunElement] = Term.Variable(funParameterElement)
            } else if (arg is KtNameReferenceExpression) {
                val reference = bindingContext[BindingContext.REFERENCE_TARGET, arg]
                        ?.findPsi()
                        ?: continue
                LiquidTypeInfoStorage[reference] = Term.Variable(funParameterElement)
            }
        }
    }

    fun analyzeIfExpression(expr: KtIfExpression) {
        val condition = expr.condition ?: return
        val analyzedCond = analyzeExpression(condition) ?: return
        val thenExpr = expr.then?.let { analyzeIfBranch(analyzedCond, it) }
        val elseExpr = expr.getElse()?.let { analyzeIfBranch(Term.Not(analyzedCond), it) }

        if (thenExpr == null && elseExpr == null) return

        val assignee = expressionAssignee(expr) ?: return
        LiquidTypeInfoStorage[assignee] = Term.Or(thenExpr ?: Term.TRUE, elseExpr ?: Term.TRUE)
    }

    fun analyzeIfBranch(condition: Term, branch: KtExpression): Term? {
        val result = analyzeExpression(branch) ?: return null
        return Term.And(condition, result)
    }

    fun analyzeExpression(expr: KtExpression): Term? {
        when (expr) {
            is KtNameReferenceExpression -> {
                val reference = bindingContext[BindingContext.REFERENCE_TARGET, expr]?.findPsi()
                        ?: throw IllegalArgumentException("Unknown reference")
                LiquidTypeInfoStorage.putIfNotExists(reference, Term.TRUE)
                return Term.Variable(reference)
            }
            is KtBinaryExpression -> {
                val left = analyzeExpression(expr.left!!)
                val right = analyzeExpression(expr.right!!)
                if (left == null || right == null) return null
                val opToken = expr.operationToken as? KtSingleValueToken ?: return null
                return Term.BinaryExpression(opToken.value, Term.BinaryTerm(left, right))
            }
            else -> return null
        }
    }


    fun findUsages(element: PsiElement) = runBlocking {
        if (element is KtParameter) findUsages(element, findPropertyUsagesOptions)
        else findUsages(element, findFunctionUsagesOptions)
    }

    suspend fun findUsages(element: PsiElement, options: FindUsagesOptions) = suspendCoroutine<List<PsiElement>> { cont ->
        val findHandler = findUsagesManager.getFindUsagesHandler(element, false)
                ?: throw IllegalArgumentException("Cant find usages of $element")
        val result = ArrayList<PsiElement>()
        FindUsagesManager.startProcessUsages(
                findHandler,
                findHandler.primaryElements,
                findHandler.secondaryElements,
                { it ->
                    if (it is UsageInfo2UsageAdapter) {
                        result.add(it.element)
                    }
                    println("$it")
                    true
                },
                options,
                {
                    cont.resume(result)
                    println("OnComplete")
                }
        ).start()
    }

}


inline fun <reified T : PsiElement> PsiElement.findChildWithType(predicate: (T) -> Boolean = { true }) =
        this.children.filterIsInstance(T::class.java)
                .filter(predicate)
                .also { if (it.size > 1) throw IllegalStateException("Too many children with same type") }
                .firstOrNull()


fun PsiElement.getElementTextWithTypes() = getElementText { "| ${it::class.java.name} ${it.text}\n-----------\n" }

fun PsiElement.annotateWithLiquidTypeInfo() = getElementText { it ->
    "| ${it::class.java.name} ${LiquidTypeInfoStorage[it] ?: ""} ${it.text} \n-----------\n"
}

fun PsiElement.getElementText(textGetter: (PsiElement) -> String): String {
    val text = StringBuilder()
    this.accept(object : PsiElementVisitor() {
        var offset = ""
        override fun visitElement(element: PsiElement?) {
            if (element == null) {
                text.append("NULL\n")
                return
            }
            text.append(offset)
            text.append(textGetter(element))
            offset += "   "
            element.acceptChildren(this)
            offset = offset.removeSuffix("   ")
        }
    })
    return text.toString()
}