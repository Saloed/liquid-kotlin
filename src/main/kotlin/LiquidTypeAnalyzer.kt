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
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.KotlinCacheServiceImpl
import org.jetbrains.kotlin.idea.findUsages.KotlinFunctionFindUsagesOptions
import org.jetbrains.kotlin.idea.findUsages.KotlinPropertyFindUsagesOptions
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCallWithAssert
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionResolvedCall
import org.jetbrains.liquidtype.LqT
import org.jetbrains.research.kex.config.FileConfig
import org.jetbrains.research.kex.config.GlobalConfig
import org.jetbrains.research.kex.config.RuntimeConfig
import org.jetbrains.research.kex.ktype.KexBool
import org.jetbrains.research.kex.smt.Result
import org.jetbrains.research.kex.smt.SMTProxySolver
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.type.TypeFactory
import kotlin.math.exp

object LiquidTypeAnalyzer {

    enum class AnalysisType {
        VALUE, TYPE
    }

    private val annotationFqName = FqName.fromSegments(LqT::class.qualifiedName!!.split("."))

    private lateinit var findUsagesManager: FindUsagesManager

    private lateinit var psiElementFactory: KtPsiFactory

    private lateinit var findFunctionUsagesOptions: FindUsagesOptions
    private lateinit var findPropertyUsagesOptions: FindUsagesOptions

    private lateinit var bindingContext: MyBindingContext

    @JvmStatic
    fun analyze(project: Project) {
        val projectRootManager = ProjectRootManager.getInstance(project)
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        psiElementFactory = KtPsiFactory(project)

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

        bindingContext = MyBindingContext(facade.analyzeWithAllCompilerChecks(allKtFilesPsi).bindingContext)

        for (file in allKtFilesPsi) {
            if (!file.name.endsWith("Mian.kt")) continue
            analyzeSingleFile(file)
            val a = 3
        }


    }

    private fun extractConditionFromAnnotatedDeclaration(declaration: KtDeclaration) =
            bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]?.let {
                it.annotations.findAnnotation(annotationFqName)
            }?.let {
                it.allValueArguments[Name.identifier("condition")]
            }?.let { it.value as? String }


    private fun substituteItReference(expression: KtExpression, reference: KtDeclaration): KtExpression {
        val referenceDescriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, reference]
        val itExprs = expression.collectDescendantsOfType<KtNameReferenceExpression> {
            it.getReferencedName() == "it"
        }
        for (it in itExprs) {
            bindingContext[BindingContext.REFERENCE_TARGET, it] = referenceDescriptor
        }
        return expression
    }

    private fun analyzeAnnotations(file: KtFile) =
            file.collectDescendantsOfType<KtDeclaration> { true }
                    .zipMap { extractConditionFromAnnotatedDeclaration(it) }
                    .pairNotNull()
                    .mapSecond { psiElementFactory.createExpression(it) } // todo: add context
                    .map { it.first to substituteItReference(it.second, it.first) }
                    .mapSecond { analyzeExpressionValue(it) }
                    .forEach { (element, type) -> LiquidTypeInfoStorage[element] = type }

    private fun analyzeExpression(expression: KtExpression): Term = when (expression) {
        is KtConstantExpression -> analyzeConstantExpression(expression)
        is KtBinaryExpression -> analyzeBinaryExpression(expression)
        is KtNameReferenceExpression -> analyzeNameReferenceExpression(expression)
        is KtIfExpression -> analyzeIfExpression(expression)
        is KtCallExpression -> {
            if (expression in LiquidTypeInfoStorage) {
                LiquidTypeInfoStorage[expression]!!.combineWithAnd()
            } else {
                LiquidTypeInfoStorage[expression] = TermFactory.getTrue()
                val result = analyzeCallExpression(expression)
                LiquidTypeInfoStorage[expression] = result
                result
            }
        }
        is KtParameter -> TermFactory.elementType(expression)
        is KtArrayAccessExpression -> analyzeArrayAccessExpression(expression)
        is KtDotQualifiedExpression -> analyzeDotQualifiedExpression(expression)
        else -> throw IllegalArgumentException("Unsupported expression $expression")
    }

    private fun analyzeExpressionValue(expression: KtExpression): Term = when (expression) {
        is KtConstantExpression -> analyzeConstantExpressionValue(expression)
        is KtBinaryExpression -> analyzeBinaryExpressionValue(expression)
        is KtNameReferenceExpression -> analyzeNameReferenceExpressionValue(expression)
        is KtIfExpression -> analyzeIfExpressionValue(expression)
        is KtCallExpression -> analyzeCallExpressionValue(expression)
        is KtDotQualifiedExpression -> analyzeDotQualifiedExpressionValue(expression)
        is KtArrayAccessExpression -> analyzeArrayAccessExpressionValue(expression)
        else -> throw IllegalArgumentException("Unsupported value expression $expression")
    }

    private fun analyzeDotQualifiedExpression(expression: KtDotQualifiedExpression): Term {
        val receiver = expression.receiverExpression
        val selector = expression.selectorExpression ?: return TermFactory.getTrue()

        // todo: add parameter analysis
        return TermFactory.getTrue()
    }

    private fun analyzeDotQualifiedExpressionValue(expression: KtDotQualifiedExpression): Term {
        val receiver = expression.receiverExpression
        val selector = expression.selectorExpression ?: throw IllegalArgumentException("No selector for $expression")

        val type = selector.getType(bindingContext) ?: throw IllegalArgumentException("No type for selector $selector")
        return TermFactory.elementValue(selector, type.toKexType())
    }

    private fun analyzeArrayAccessExpression(expression: KtArrayAccessExpression): Term {
        return TermFactory.getTrue()
    }

    private fun analyzeArrayAccessExpressionValue(expression: KtArrayAccessExpression): Term {
        val type = expression.getType(bindingContext)
                ?: throw IllegalArgumentException("No type for array access expression $expression")
        return TermFactory.elementValue(expression, type.toKexType())
    }

    private fun analyzeIfExpression(expression: KtIfExpression): Term {
        val cond = expression.condition ?: throw IllegalArgumentException("If without condition")

        val thenBranch = expression.getThen()?.let { analyzeExpression(it) }
        val elseBranch = expression.getElse()?.let { analyzeExpression(it) }
        val condTerm = analyzeExpressionValue(cond)

        if (thenBranch == null && elseBranch == null) throw IllegalArgumentException("If branches are null")

        val trueBranch = thenBranch?.let { listOf(condTerm, it).combineWithAnd() }
        val falseBranch = elseBranch?.let { listOf(TermFactory.getNegTerm(KexBool, condTerm), it).combineWithAnd() }

        if (trueBranch != null && falseBranch != null) return TermFactory.getBinary(KexBool, BinaryOpcode.Or(), trueBranch, falseBranch)
        if (trueBranch != null) return trueBranch
        if (falseBranch != null) return falseBranch

        return TermFactory.emptyTerm("EmptyIfExpr")
    }


    private fun analyzeIfExpressionValue(expression: KtIfExpression): Term {
        val cond = expression.condition ?: throw IllegalArgumentException("If without condition")

        val thenBranch = expression.getThen()?.let { analyzeExpressionValue(it) }
        val elseBranch = expression.getElse()?.let { analyzeExpressionValue(it) }
        val condTerm = analyzeExpressionValue(cond)

        if (thenBranch == null && elseBranch == null) throw IllegalArgumentException("If branches are null")
        if (thenBranch == null || elseBranch == null) throw NotImplementedError("Both branches of if must exists for now")

        return TermFactory.getIf(condTerm, thenBranch, elseBranch)
    }

    private fun analyzeNameReferenceExpression(expression: KtNameReferenceExpression): Term {
        val referenceTarget = bindingContext[BindingContext.REFERENCE_TARGET, expression]?.findPsi()
                ?: throw IllegalArgumentException("Unknown reference target")
        return TermFactory.elementType(referenceTarget)
    }

    private fun analyzeNameReferenceExpressionValue(expression: KtNameReferenceExpression): Term {
        val targetDescriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression]
                ?: throw IllegalArgumentException("Unknown value reference target descriptor")
        val targetPsi = targetDescriptor.findPsi()
                ?: throw IllegalArgumentException("Unknown value reference target")

        val targetValue = targetDescriptor as? ValueDescriptor
                ?: throw IllegalArgumentException("Unknown value reference target value")
        val kexType = targetValue.type.toKexType()

        return TermFactory.elementValue(targetPsi, kexType)
    }


    private fun analyzeBinaryExpression(expression: KtBinaryExpression): Term {
        val lOperand = expression.left ?: throw IllegalArgumentException("Left operand undefined")
        val rOperand = expression.right ?: throw IllegalArgumentException("Right operand undefined")
        val operation = expression.operationToken

        val leftType = analyzeExpression(lOperand)
        val rightType = analyzeExpression(rOperand)
        val leftValue = analyzeExpressionValue(lOperand)
        val rightValue = analyzeExpressionValue(rOperand)

        val operationValue = TermFactory.binaryTermForOpToken(operation, leftValue, rightValue)
        return if (operation.isCmpToken()) {
            listOf(leftType, rightType, operationValue).combineWithAnd()
        } else {
            val expressionType = TermFactory.elementType(expression)
            LiquidTypeInfoStorage.liquidTypeValues[expressionType] = TermFactory.equalityTerm(expressionType, operationValue)
            listOf(leftType, rightType, expressionType).combineWithAnd()
        }
    }

    private fun analyzeBinaryExpressionValue(expression: KtBinaryExpression): Term {
        val lOperand = expression.left ?: throw IllegalArgumentException("Left operand undefined")
        val rOperand = expression.right ?: throw IllegalArgumentException("Right operand undefined")
        val operation = expression.operationToken
        val leftValue = analyzeExpressionValue(lOperand)
        val rightValue = analyzeExpressionValue(rOperand)
        return TermFactory.binaryTermForOpToken(operation, leftValue, rightValue)
    }

    private fun analyzeConstantExpression(expression: KtConstantExpression): Term {
        return TermFactory.getTrue()
    }

    private fun analyzeConstantExpressionValue(expression: KtConstantExpression): Term {
        return TermFactory.fromConstant(expression.node.elementType, expression.text)
    }

    fun analyzeCallExpression(expression: KtCallExpression): Term {


        val resolvedCall = expression.getCall(bindingContext)?.getResolvedCallWithAssert(bindingContext)
                ?: throw IllegalArgumentException("Function not found")

        if (resolvedCall is VariableAsFunctionResolvedCall) {
            throw IllegalArgumentException("Function is variable")
        }

        val funElement = resolvedCall.candidateDescriptor.findPsi() as? KtNamedFunction
                ?: throw IllegalArgumentException("Function PSI not found")

        val arguments = resolvedCall.valueArguments.toList()
                .mapFirst { it.findPsi() ?: throw IllegalArgumentException("Function argument not found") }
                .mapSecond { it.arguments.mapNotNull { it.getArgumentExpression() } }
                .mapSecond { if (it.size == 1) it else throw IllegalStateException("Error in arguments ${funElement.text} $it") }
                .mapSecond { it.first() }

        val substitutions = arguments.toMap()

        val substitutionTerm = arguments
                .mapFirst { TermFactory.elementValue(it, bindingContext) }
                .mapSecond { analyzeExpressionValue(it) }
                .map { TermFactory.equalityTerm(it.second, it.first) }

        val constraints = arguments
                .mapFirst { TermFactory.elementConstraint(it, bindingContext) }
                .mapSecond { analyzeExpressionValue(it) }
                .mapSecond { it.collectDescendantsOfType<TypeValueTerm> { it is TypeValueTerm } }
                .mapSecond { it.map { TermFactory.elementConstraint(it.typeElement, bindingContext) } }
                .mapSecond { if (it.isNotEmpty()) it.combineWithAnd() else TermFactory.getTrue() }


        val argumentConstraints = substitutionTerm.zip(constraints) { a, b -> TermFactory.implication(listOf(b.second, a).combineWithAnd(), b.first) }


//        println("FUCK")
//        println("$substitutionTerm")
//
//        val constraints = arguments.mapNotNull { LiquidTypeInfoStorage[it.first] }.flatten()
//
//        println("FUCK ME: $constraints")
//
//        if (constraints.isNotEmpty()) {
//            LiquidTypeInfoStorage.addConstraint(expression, (substitutionTerm + constraints).combineWithAnd())
//        }

        LiquidTypeInfoStorage.addConstraint(expression, argumentConstraints.combineWithAnd())

//        println("${LiquidTypeInfoStorage.unsafeTypeInfo()}")
//        if (funElement.hasBlockBody()) throw NotImplementedError("Fun block body is not implemented ${funElement.text}")
//        val functionBody = funElement.bodyExpression ?: return TermFactory.getTrue()
//        val functionTerm = analyzeExpression(functionBody)
//        val nonRecursiveInfo = LiquidTypeInfoStorage.unsafeTypeInfo().removeRecursiveTypeConstraints()
//        val substitutor = DeepReferenceSubstitutionTransformer(substitutions, bindingContext, nonRecursiveInfo)
//        return functionTerm.deepAccept(substitutor)
        return TermFactory.emptyTerm("FUNCTION")
    }

    fun analyzeCallExpressionValue(expression: KtCallExpression): Term {
        val resolvedCall = expression.getCall(bindingContext)?.getResolvedCallWithAssert(bindingContext)
                ?: throw IllegalArgumentException("Function not found")

        val funElement = resolvedCall.candidateDescriptor.findPsi() as? KtNamedFunction
                ?: throw IllegalArgumentException("Function PSI not found")

        val type = resolvedCall.candidateDescriptor.returnType
                ?: throw IllegalArgumentException("Function return type is unknown")

        val funValue = TermFactory.elementValue(funElement, type.toKexType())

        if (funElement.hasBody() && !funElement.hasBlockBody()) {
            val funExpression = funElement.bodyExpression
                    ?: throw IllegalArgumentException("Function without body: ${funElement.text}")
            val expressionValue = analyzeExpressionValue(funExpression)
            LiquidTypeInfoStorage[funElement] = TermFactory.equalityTerm(funValue, expressionValue)
        }
        return funValue
    }

    fun removeRecursion(element: PsiElement, terms: List<Term>, typeConstraints: LiquidTypeInfo): Pair<PsiElement, List<Term>> {
        val remover = RemoveTypeReferenceRecursion(element, typeConstraints)
        return element to terms.map { remover.transform(it).accept(remover) }
    }

    fun LiquidTypeInfo.removeRecursiveTypeConstraints() = unsafeTypeConstraints()
            .map { removeRecursion(it.key, it.value, this) }
            .toLiquidTypeInfo()

    private fun addFakeTrueForFunctionArguments(file: KtFile) = file.collectDescendantsOfType<KtNamedFunction> { true }
            .flatMap { it.collectFirstDescendantsOfType<KtParameter> { true } }
            .forEach {
                if (LiquidTypeInfoStorage[it] == null) {
                    LiquidTypeInfoStorage[it] = TermFactory.getTrue()
                }
            }


    private fun analyzeSingleFile(file: KtFile) {
        val x = analyzeAnnotations(file)
        addFakeTrueForFunctionArguments(file)

        val functionCalls = file.collectDescendantsOfType<KtCallExpression> { true }
        for (funCall in functionCalls) {
            analyzeCallExpression(funCall)
        }


        val typeConstraints = LiquidTypeInfoStorage.unsafeTypeInfo().removeRecursiveTypeConstraints()


        val typeInliner = TypeReferenceInliner(typeConstraints)
        val binarySimplifier = BinaryExpressionSimplifier()

//        val inlinedTypeConstraints = typeConstraints.accept(typeInliner)

        println("$typeConstraints")
//        println("##################################################")
//        println("$inlinedTypeConstraints")

        println("##################################################")
        val typeReferenceInliner = DummyTypeReferenceValue(true)
//        val tmp = inlinedTypeConstraints.accept(typeReferenceInliner).accept(binarySimplifier)
//        println("$tmp")
//        println("##################################################")


        val config = GlobalConfig
        config.initialize(RuntimeConfig, FileConfig("kex/kex.ini"))

        val initialConstraints = LiquidTypeInfoStorage.liquidTypeConstraints
                .unsafeTypeConstraints()
                .map { it.toPair() }
                .mapFirst { listOf(it) }
                .mapSecond { constraints ->
                    constraints
                            .combineWithAnd()
                            .deepAccept(binarySimplifier)
                            .deepAccept(typeReferenceInliner)
                            .deepAccept(binarySimplifier)
                }


        var nextConstraints = initialConstraints
//        var iteration = 0
//        while (nextConstraints.isNotEmpty()) {
//            println("$iteration ".repeat(20))
        val unsatisfiable = ArrayList<Pair<List<PsiElement>, Term>>()
        for ((key, constraints) in nextConstraints) {
            if (constraints == TermFactory.getTrue()) continue

            val predicate = TermFactory.getNegTerm(constraints).toPredicateState()
            println(predicate)
            try {
                val result = SMTProxySolver().isPathPossible(predicate, TermFactory.getTrue().toPredicateState())
                println("$result ${key.map { it.text }}")
                if (result is Result.SatResult) {
                    println("${result.model}")
                } else {
                    unsatisfiable.add(key to constraints)
                }
            } catch (ex: Exception) {
                System.err.println("$ex")
                System.err.println("${ex.stackTrace.map { "$it \n" }}")
                System.err.println("${key.map { it.text }}")
                System.err.println("$constraints")
            }
            println("-".repeat(100))
        }
//            nextConstraints = if (unsatisfiable.size <= 1) {
//                emptyList()
//
//            } else {
//                val keys = unsatisfiable.map { it.first }
//                val terms = unsatisfiable.map { it.second }
//                mergeConstraints(keys, terms)
//            }
//            iteration++
//        }


    }


    fun mergeConstraints(keys: List<List<PsiElement>>, constraints: List<Term>): List<Pair<List<PsiElement>, Term>> {
        return listOf(keys.flatten() to constraints.combineWithAnd())
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

inline fun <reified T : PsiElement> PsiElement.collectFirstDescendantsOfType(crossinline predicate: (T) -> Boolean): List<T> {
    val result = ArrayList<T>()
    this.accept(object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement?) {
            if (element == null) return
            if ((element is T) && predicate(element)) {
                result.add(element)
                return
            }
            element.acceptChildren(this)
        }
    })
    return result
}
