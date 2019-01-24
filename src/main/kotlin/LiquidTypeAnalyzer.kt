import com.intellij.find.FindManager
import com.intellij.find.findUsages.FindUsagesManager
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.find.impl.FindManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileImpl
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.caches.resolve.KotlinCacheServiceImpl
import org.jetbrains.kotlin.idea.findUsages.KotlinFunctionFindUsagesOptions
import org.jetbrains.kotlin.idea.findUsages.KotlinPropertyFindUsagesOptions
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCallWithAssert
import org.jetbrains.liquidtype.LqT
import org.jetbrains.research.kex.config.FileConfig
import org.jetbrains.research.kex.config.GlobalConfig
import org.jetbrains.research.kex.config.RuntimeConfig
import org.jetbrains.research.kex.smt.Result
import org.jetbrains.research.kex.smt.SMTProxySolver
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.TermFactory
import java.util.*

object LiquidTypeAnalyzer {

    private lateinit var findUsagesManager: FindUsagesManager

    private lateinit var psiElementFactory: KtPsiFactory

    private lateinit var findFunctionUsagesOptions: FindUsagesOptions
    private lateinit var findPropertyUsagesOptions: FindUsagesOptions

    lateinit var bindingContext: MyBindingContext

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
        val processor = LqtAnnotationProcessor(bindingContext, psiElementFactory, facade)

        for (file in allKtFilesPsi) {
            if (!file.name.endsWith("Mian.kt")) continue
            val lqtAnnotations = processor.processLqtAnnotations(file)
            analyzeSingleFile(file, lqtAnnotations)
        }
    }

    private fun analyzeLqTConstraintsFunLevel() =
            NewLQTInfo.typeInfo.keys
                    .filterIsInstance<KtExpression>()
                    .forEach {
                        try {
                            it.LqTAnalyzer(bindingContext).analyze()
                        } catch (ex: Exception) {
                            reportError("$ex")
                        }
                    }

    private fun addLqTAnnotationsInfo(lqtAnnotations: List<AnnotationInfo>) = lqtAnnotations.forEach {
        InitialLiquidTypeInfoCollector(it.bindingContext).collect(it.expression, NewLQTInfo.typeInfo)
        val constraint = it.expression.LqTAnalyzer(it.bindingContext).analyze()
        val declarationInfo = NewLQTInfo.getOrException(it.declaration)
        declarationInfo.predicate = PredicateFactory.getBool(constraint.variable)
        declarationInfo.dependsOn.add(constraint)
    }

    private fun cleanupLqTInfo() = NewLQTInfo.typeInfo.keys
            .filterIsInstance<KtExpression>()
            .filterNot { hasExpressionAnalyzer(it) }
            .forEach {
                NewLQTInfo.typeInfo.remove(it)
            }

    private fun initializeSolver(): SMTProxySolver {
        val config = GlobalConfig
        config.initialize(RuntimeConfig, FileConfig("kex/kex.ini"))
        return SMTProxySolver()
    }


    private fun collectTypeDependencies(lqt: LiquidType): List<LiquidType> {
        val result = arrayListOf<LiquidType>()
        val toVisit = LinkedList<LiquidType>()
        toVisit.add(lqt)
        while (toVisit.isNotEmpty()) {
            val current = toVisit.pop()
            if (current in result) continue
            result.add(current)
            toVisit.addAll(current.dependsOn)
        }
        return result
    }

    private fun collectPredicates(lqt: LiquidType, includeSelf: Boolean): List<Predicate> =
            collectTypeDependencies(lqt)
                    .filter { includeSelf || it != lqt }
                    .map { it.finalConstraint() }

    private fun checkFunctionCalls(file: KtFile) = file
            .collectDescendantsOfType<KtCallExpression> { it in NewLQTInfo.typeInfo }
            .zipMap { checkCallExpressionArguments(it) }
            .pairNotNull()

    private fun checkCallExpressionArguments(expression: KtCallExpression): Pair<PredicateState, PredicateState>? {
        val callExprInfo = NewLQTInfo.getOrException(expression)
        val funElement = callExprInfo.dependsOn.first().expression

        val resolvedCall = expression.getCall(bindingContext)?.getResolvedCallWithAssert(bindingContext)
                ?: throw IllegalArgumentException("Function not found")


        val arguments = resolvedCall.valueArguments.toList()
                .mapFirst {
                    it.findPsi() as? KtExpression ?: throw IllegalArgumentException("Function argument not found")
                }
                .mapSecond { it.arguments.mapNotNull { it.getArgumentExpression() } }
                .mapSecond { if (it.size == 1) it else throw IllegalStateException("Error in arguments ${funElement.text} $it") }
                .mapSecond { it.first() }
                .mapFirst { it.LqTAnalyzer(bindingContext).analyze() }
                .mapSecond { it.LqTAnalyzer(bindingContext).analyze() }

        val parameters = arguments.map { it.first }

        if (parameters.all { !it.hasConstraints }) return null

        val parametersConstraints = parameters
                .flatMap { collectPredicates(it, includeSelf = false) }
                .map { it.asTerm() }
                .combineWithAnd()

        val substitutedArgumentsConstraints = arguments
                .map { it.second }
                .flatMap { collectPredicates(it, includeSelf = true) }
                .map { it.asTerm() }
                .combineWithAnd()


        val substitutionTerms = arguments
                .mapFirst { it.variable }
                .mapSecond { it.variable }
                .map { TermFactory.equalityTerm(it.second, it.first) }
                .combineWithAnd()

        val safePropertyLhs = listOf(
                substitutedArgumentsConstraints,
                substitutionTerms,
                parametersConstraints
        )
                .map {
                    PredicateFactory.getBool(it)
                }
                .collectToPredicateState()
                .map(SimplifyPredicates::transform)
//                .map(ConstantPropagator::transform)

        val safePropertyRhs = parameters
                .map { it.finalConstraint() }
                .map { it.asTerm() }
                .map { TermFactory.getNegTerm(it) }
                .combineWithOr()
                .toPredicateState()
                .map(SimplifyPredicates::transform)
//                .map(ConstantPropagator::transform)

        return safePropertyLhs to safePropertyRhs
    }


    private fun analyzeSingleFile(file: KtFile, lqtAnnotations: List<AnnotationInfo>) {
        InitialLiquidTypeInfoCollector(bindingContext).collect(file, NewLQTInfo.typeInfo)
        addLqTAnnotationsInfo(lqtAnnotations)
        cleanupLqTInfo()
        analyzeLqTConstraintsFunLevel()
        val solver = initializeSolver()
        val safeProperties = checkFunctionCalls(file)

        for ((expr, safeProperty) in safeProperties) {

            val (lhs, rhs) = safeProperty

            println("${expr.context?.text}")

//            psToGraphView("TestL", lhs)
//            psToGraphView("TestR", rhs)

            try {
                val result = solver.isPathPossible(lhs, rhs)
                println("$result ${expr.context?.text}")
                if (result is Result.SatResult) {
                    println("${result.model}")
                } else {
//                    unsatisfiable.add(key to constraints)
                }
            } catch (ex: Exception) {
                System.err.println("$ex")
                System.err.println("${ex.stackTrace.map { "$it \n" }}")
                System.err.println(expr.text)
                System.err.println("$safeProperty")
            }
            println("-".repeat(100))
        }
    }
}
