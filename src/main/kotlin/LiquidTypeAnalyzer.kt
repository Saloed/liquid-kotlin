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
        //        is KtIfExpression -> analyzeIfExpressionValue(expression)
        is KtCallExpression -> analyzeCallExpressionValue(expression)
        is KtDotQualifiedExpression -> analyzeDotQualifiedExpressionValue(expression)
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

        return TermFactory.elementValue(funElement, type.toKexType())
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
        val typeReferenceInliner = DummyTypeReferenceValue(false)
//        val tmp = inlinedTypeConstraints.accept(typeReferenceInliner).accept(binarySimplifier)
//        println("$tmp")
//        println("##################################################")


        val config = GlobalConfig
        config.initialize(RuntimeConfig, FileConfig("kex/kex.ini"))

        for ((key, constraints) in LiquidTypeInfoStorage.liquidTypeConstraints.unsafeTypeConstraints()) {
            val combinedConstraints = constraints
//                    .also {
//                        println("$".repeat(20))
//                        println(it)
//                    }
                    .combineWithAnd().deepAccept(binarySimplifier)
//                    .also {
//                        println("$".repeat(20))
//                        println(it)
//                    }
                    .deepAccept(typeReferenceInliner)
//                    .also {
//                        println("$".repeat(20))
//                        println(it)
//                    }
                    .deepAccept(binarySimplifier)
//                    .also {
//                        println("$".repeat(20))
//                        println(it)
//                    }
            if (combinedConstraints == TermFactory.getTrue()) continue
            val predicate = TermFactory.getNegTerm(combinedConstraints).toPredicateState()
            println(predicate)
            try {
                val result = SMTProxySolver().isPathPossible(predicate, TermFactory.getTrue().toPredicateState())
//                    if (key is KtNamedFunction) {
                println("$result ${key.text}")
                println("${(result as? Result.SatResult)?.model}")
//                    }
            } catch (ex: Exception) {
                System.err.println(ex)
                System.err.println(key.text)
                System.err.println("$combinedConstraints")
            }
            println("-".repeat(100))
        }


        val b = 4

//        val functions = file.collectDescendantsOfType<KtNamedFunction> { true }
//        for (func in functions) {
//            analyzeFunction(func)
//        }
//
//        println("$LiquidTypeInfoStorage")
//
//        println("#####################################")
//
//        val nameReferenceTransformer = TransformReferences(bindingContext, true)
//        val typeTermInliner = DummyTypeReferenceValue(true)
//        val liquidTypeInfo = LiquidTypeInfoStorage.unsafeTypeInfo().removeRecursiveTypeConstraints()
//        println("$liquidTypeInfo")
//
//
//


//        val typeInliner = TypeReferenceInliner()
//        var localLiquidTypeInfo = liquidTypeInfo
//        for (i in 0..3) {
//            println("$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$i$")
//            val transformedTypeInfo = localLiquidTypeInfo
//                    .accept(nameReferenceTransformer)
//                    .accept(typeTermInliner)
//                    .unsafeTypeInfo()


//        for ((key, terms) in tmp.unsafeTypeConstraints()) {
//
//            val constraints = LiquidTypeInfoStorage.getConstraints(key) ?: continue
//            val combinedConstraints = constraints
//                    .combineWithAnd()
//                    .deepAccept(typeInliner)
//                    .deepAccept(typeReferenceInliner)
//                    .deepAccept(binarySimplifier)
//            if (combinedConstraints == TermFactory.getTrue()) continue
//            println(combinedConstraints)
//            val inverseCondition = PredicateFactory.getInequality(combinedConstraints, TermFactory.getTrue())
//            println(LiquidTypeInfoStorage.liquidTypeValues[key])
//            val sample = terms.toPredicateState().addPredicate(inverseCondition)
//            try {
//                val result = SMTProxySolver().isViolated(sample, BasicState())
////                    if (key is KtNamedFunction) {
//                println(key.context?.context?.context?.text)
//                println("$result $sample")
////                    }
//            } catch (ex: Exception) {
//                System.err.println(ex)
//                System.err.println(key.text)
//                System.err.println(sample)
//            }
//        }
//            localLiquidTypeInfo = localLiquidTypeInfo.accept(typeInliner).removeRecursiveTypeConstraints()
//
//        }

//        val sample = LiquidTypeInfoStorage.unsafeTypeInfo().toList()[0].second.current
//        val result = SMTProxySolver().isViolated(sample, BasicState())
//        println("${(result as Result.SatResult).model}")

        val a = 3
//        val visitedElements = HashSet<PsiElement>()
//
//        do {
//            val elementsToAnalyze = LiquidTypeInfoStorage.unsafeTypeInfo().keys
//                    .filter { it !in visitedElements }
//                    .also { visitedElements.addAll(it) }
//            elementsToAnalyze.filter { it is KtParameter || it is KtFunction }
//                    .zipMap { findUsages(it) }
//                    .map { (element, usages) -> usages.map { analyzeUsage(element, it) } }
//
//        } while (elementsToAnalyze.isNotEmpty())

    }


//    private fun analyzeFunction(function: KtNamedFunction) = LiquidTypeInfoStorage.unsafeTypeInfo()
//            .computeIfAbsent(function) {
//
//                if (function.hasBody() && !function.hasBlockBody()) {
//                    val funExpr = function.bodyExpression
//                            ?: throw IllegalArgumentException("Unknown body for expression function")
//                    val arguments = function.collectFirstDescendantsOfType<KtParameter> { true }
//                    val funType = analyzeExpressionForReferences(funExpr, arguments)
//                    return@computeIfAbsent funType
//                }
//
//                // todo: analyze function body
//
//                val ifExpressions = function.collectFirstDescendantsOfType<KtIfExpression> { true }
//                for (expr in ifExpressions) {
//                    analyzeExpressionForReferences(expr)
//                }
//
//                TermFactory.emptyTerm("Function(${function.fqName})")
//            }
//
//
//
//    private fun analyzeExpressionForReferences(expression: KtExpression, references: List<PsiElement>,
//                                               type: AnalysisType = AnalysisType.TYPE): Term {
//        val analysisResult = analyzeCallExpression(expression)
//        for (ref in references) {
//            LiquidTypeInfoStorage[ref] = analysisResult
//        }
//        return analysisResult
//    }
//
//    private fun analyzeExpressionForReferences(expression: KtExpression, type: AnalysisType = AnalysisType.TYPE): Term {
//        val references = expression.collectDescendantsOfType<KtNameReferenceExpression> { true }
//        return analyzeExpressionForReferences(expression, references, type)
//    }


//    fun analyzeUsage(element: PsiElement, usage: PsiElement) = when (usage) {
//        is KtNameReferenceExpression -> analyzeUsage(element, usage)
//        else -> Unit
//    }
//
//    fun expressionAssignee(expr: KtExpression): PsiElement? {
//        val parent = expr.parent ?: return null
//        val isAssignee = parent.allChildren.toList()
//                .filterIsInstance<LeafPsiElement>()
//                .map { it.elementType }
//                .contains(KtTokens.EQ)
//        return if (isAssignee) parent else null
//    }
//
//
//    fun analyzeUsage(element: PsiElement, usage: KtNameReferenceExpression) {
//        val parent = usage.parent
//        if (parent is KtCallExpression) {
//            return analyzeCallExpression(element as KtFunction, parent)
//        }
//
//        val ifParent = usage.parentOfType<KtIfExpression>()
//        if (ifParent != null) {
//            return analyzeIfExpression(ifParent)
//        }
//    }


    //    fun analyzeCallExpression(funElement: KtFunction, expr: KtCallExpression) {
//        val assignee = expressionAssignee(expr)
//        if (assignee != null) {
//            LiquidTypeInfoStorage.putIfNotExists(funElement, Term.TRUE)
//            LiquidTypeInfoStorage[assignee] = Term.Variable(funElement)
//        }
//
//        val arguments = expr.getCall(bindingContext)?.valueArguments ?: return
//        if (arguments.any { it.isNamed() }) return
//        val funArguments = arguments.map { it.getArgumentExpression() }
//
//        val funParameters = bindingContext[BindingContext.FUNCTION, funElement]?.valueParameters ?: return
//        val funParameterElements = funParameters.map { it.findPsi()!! }
//        funParameterElements.forEach { LiquidTypeInfoStorage.putIfNotExists(it, Term.TRUE) }
//
//        for ((arg, funParameterElement) in funArguments.zip(funParameterElements)) {
//            if (arg is KtCallExpression) {
//                val callFunElement = bindingContext[BindingContext.RESOLVED_CALL, arg.getCall(bindingContext)]
//                        ?.candidateDescriptor
//                        ?.findPsi()
//                        ?: continue
//                LiquidTypeInfoStorage[callFunElement] = Term.Variable(funParameterElement)
//            } else if (arg is KtNameReferenceExpression) {
//                val reference = bindingContext[BindingContext.REFERENCE_TARGET, arg]
//                        ?.findPsi()
//                        ?: continue
//                LiquidTypeInfoStorage[reference] = Term.Variable(funParameterElement)
//            }
//        }
//    }
//
//    fun analyzeIfExpression(expr: KtIfExpression) {
//        val condition = expr.condition ?: return
//        val analyzedCond = analyzeCallExpression(condition) ?: return
//        val thenExpr = expr.then?.let { analyzeIfBranch(analyzedCond, it) }
//        val elseExpr = expr.getElse()?.let { analyzeIfBranch(Term.Not(analyzedCond), it) }
//
//        if (thenExpr == null && elseExpr == null) return
//
//        val assignee = expressionAssignee(expr) ?: return
//        LiquidTypeInfoStorage[assignee] = Term.Or(thenExpr ?: Term.TRUE, elseExpr ?: Term.TRUE)
//    }
//
//    fun analyzeIfBranch(condition: Term, branch: KtExpression): Term? {
//        val result = analyzeCallExpression(branch) ?: return null
//        return Term.And(condition, result)
//    }
//
//    fun analyzeCallExpression(expr: KtExpression): Term? {
//        when (expr) {
//            is KtNameReferenceExpression -> {
//                val reference = bindingContext[BindingContext.REFERENCE_TARGET, expr]?.findPsi()
//                        ?: throw IllegalArgumentException("Unknown reference")
//                LiquidTypeInfoStorage.putIfNotExists(reference, Term.TRUE)
//                return Term.Variable(reference)
//            }
//            is KtBinaryExpression -> {
//                val left = analyzeCallExpression(expr.left!!)
//                val right = analyzeCallExpression(expr.right!!)
//                if (left == null || right == null) return null
//                val opToken = expr.operationToken as? KtSingleValueToken ?: return null
//                return Term.BinaryExpression(opToken.value, Term.BinaryTerm(left, right))
//            }
//            else -> return null
//        }
//    }
//
//

//    private fun analyzeReferenceTarget(element: PsiElement): Term {
//        if (element !in LiquidTypeInfoStorage) LiquidTypeInfoStorage[element] = when (element) {
//            is KtParameter -> findUsages(element)
//            is KtProperty -> findUsages(element)
//            else -> throw IllegalArgumentException("Unknown usage reference type: $element")
//        }.filterIsInstance<KtExpression>()
//                .map { analyzeCallExpression(it) }
//                .combineWithAnd()
//        return TermFactory.elementType(element)
//    }
//
//
//    private fun findLocalUsages(element: PsiElement): List<KtNameReferenceExpression> {
//        val searchContext = element.parents.first { it is KtFunction || it is KtFile }
//        return searchContext.collectDescendantsOfType {
//            bindingContext[BindingContext.REFERENCE_TARGET, it]?.findPsi() == element
//        }
//    }
}


//    private fun findUsages(element: KtParameter) = runBlocking {
//        findUsages(element, findPropertyUsagesOptions)
//    }
//
//    private fun findUsages(element: KtProperty) = runBlocking {
//        findUsages(element, findPropertyUsagesOptions)
//    }
//
//
//    private suspend fun findUsages(element: PsiElement, options: FindUsagesOptions) = suspendCoroutine<List<PsiElement>> { cont ->
//        val findHandler = findUsagesManager.getFindUsagesHandler(element, false)
//                ?: throw IllegalArgumentException("Cant find usages of $element")
//        val result = ArrayList<PsiElement>()
//        FindUsagesManager.startProcessUsages(
//                findHandler,
//                findHandler.primaryElements,
//                findHandler.secondaryElements,
//                { it ->
//                    if (it is UsageInfo2UsageAdapter) {
//                        result.add(it.element)
//                    }
//                    println("$it")
//                    true
//                },
//                options,
//                {
//                    cont.resume(result)
//                    println("OnComplete")
//                }
//        ).start()
//    }
//
//}


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
