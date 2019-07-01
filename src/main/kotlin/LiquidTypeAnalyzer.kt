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
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.caches.resolve.KotlinCacheServiceImpl
import org.jetbrains.kotlin.idea.findUsages.KotlinFunctionFindUsagesOptions
import org.jetbrains.kotlin.idea.findUsages.KotlinPropertyFindUsagesOptions
import org.jetbrains.kotlin.idea.internal.KotlinBytecodeToolWindow
import org.jetbrains.kotlin.idea.project.languageVersionSettings
import org.jetbrains.kotlin.idea.refactoring.getLineNumber
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinder
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.research.kex.config.FileConfig
import org.jetbrains.research.kex.config.GlobalConfig
import org.jetbrains.research.kex.config.RuntimeConfig
import org.jetbrains.research.kex.smt.SMTProxySolver
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.transformer.BoolTypeAdapter
import org.jetbrains.research.kex.state.transformer.Optimizer
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kex.smt.Result
import org.jetbrains.research.kex.state.transformer.ConstantPropagator
import java.util.*

object LiquidTypeAnalyzer {

    private lateinit var findUsagesManager: FindUsagesManager
    private lateinit var ideFileFinder: VirtualFileFinder

    private lateinit var psiElementFactory: KtPsiFactory
    private lateinit var psiDocumentManager: PsiDocumentManager
    private lateinit var resolutionFacade: ResolutionFacade

    private lateinit var findFunctionUsagesOptions: FindUsagesOptions
    private lateinit var findPropertyUsagesOptions: FindUsagesOptions

    lateinit var bindingContext: BindingContext

    @JvmStatic
    fun analyze(project: Project) {

        val projectRootManager = ProjectRootManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        psiDocumentManager = PsiDocumentManager.getInstance(project)
        psiElementFactory = KtPsiFactory(project)
        ideFileFinder = VirtualFileFinder.getInstance(project)

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

        resolutionFacade = KotlinCacheServiceImpl(project).getResolutionFacade(allKtFilesPsi)

        bindingContext = resolutionFacade.analyzeWithAllCompilerChecks(allKtFilesPsi).bindingContext
        val processor = LqtAnnotationProcessor(bindingContext, psiElementFactory, resolutionFacade)

        ClassInfo.psiElementFactory = psiElementFactory
        ClassInfo.ktTypeContext = allKtFilesPsi.first()

        for (file in allKtFilesPsi) {
//            if (!file.name.endsWith("xxx.kt")) continue
            if (!file.name.endsWith("testJava.kt")) continue
//            if (!file.name.endsWith("Test1.kt")) continue
//            if (!file.name.endsWith("Mian.kt")) continue

            val lqtAnnotations = processor.processLqtAnnotations(file)

            bindingContext = lqtAnnotations.map { it.bindingContext }.fold(bindingContext) { acc, bindingContext ->
                acc.mergeWith(bindingContext)
            }
            analyzeSingleFile(file, lqtAnnotations)
        }
    }

    fun getKtFileBytecode(file: KtFile): List<ByteArray>? {
        val configuration = CompilerConfiguration()
        configuration.put(CommonConfigurationKeys.DISABLE_INLINE, true)
//            if (!enableAssertions.isSelected) {
//                configuration.put(JVMConfigurationKeys.DISABLE_CALL_ASSERTIONS, true)
//                configuration.put(JVMConfigurationKeys.DISABLE_PARAM_ASSERTIONS, true)
//            }
//            if (!enableOptimization.isSelected) {
        configuration.put(JVMConfigurationKeys.DISABLE_OPTIMIZATION, true)
//            }

//            if (jvm8Target.isSelected) {
        configuration.put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_1_8)
//            }
//
//            if (ir.isSelected) {
//                configuration.put(JVMConfigurationKeys.IR, true)
//            }

        configuration.languageVersionSettings = file.languageVersionSettings

        val generationState = KotlinBytecodeToolWindow.compileSingleFile(file, configuration) ?: return null
        val classFiles = generationState.factory.asList()

        return classFiles.map { it.asByteArray() }
    }

    private fun analyzeLqTConstraintsFunLevel() =
            NewLQTInfo.typeInfo.keys
                    .filterIsInstance<KtExpression>()
                    .sortedBy {
                        when (it) {
                            is KtFunction -> 0
                            else -> 1
                        }
                    }
                    .forEach {
                        try {
                            it.LqTAnalyzer(bindingContext).analyze()
                        } catch (ex: Exception) {
                            reportError("$ex")
                        }
                    }

    private fun addLqTAnnotationsInfo(lqtAnnotations: List<AnnotationInfo>) = lqtAnnotations.forEach {
        bindingContext = InitialLiquidTypeInfoCollector.collect(
                bindingContext, psiElementFactory, ideFileFinder,
                it.expression, NewLQTInfo.typeInfo
        )
        val constraint = it.expression.LqTAnalyzer(bindingContext).apply { annotationInfo = it }.analyze()
        val declarationInfo = NewLQTInfo.getOrException(it.declaration)
        declarationInfo.addPredicate(PredicateFactory.getBool(constraint.variable))
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
        return SMTProxySolver(ClassManager().type)
    }


    private fun checkFunctionCalls(file: KtFile) = file
            .collectDescendantsOfType<KtCallExpression> { it in NewLQTInfo.typeInfo }
            .zipMap { checkCallExpressionArguments(it) }
            .pairNotNull()

    private fun checkCallExpressionArguments(expression: KtCallExpression): Pair<PredicateState, PredicateState>? {
        val callExprInfo = NewLQTInfo.getOrException(expression).safeAs<CallExpressionLiquidType>()
                ?: return null

        if (callExprInfo.function.arguments.values.all { !it.hasConstraints })
            return null

        val versioned = callExprInfo.withVersions().cast<VersionedCallLiquidType>()

        val parametersConstraints = versioned.function.arguments
                .map { it.collectPredicates(includeSelf = false) }
                .chain()

        val substitutedArgumentsConstraints = versioned.arguments
                .map { it.collectPredicates(includeSelf = true) }
                .chain()

        val callPredicateState = versioned.getPredicate()

        val safePropertyLhs = listOf(
                substitutedArgumentsConstraints,
                parametersConstraints,
                callPredicateState
        )
                .chain()

        val safePropertyRhs = versioned.function.arguments
                .map { it.getPredicate() }
                .chain()

        return safePropertyLhs to safePropertyRhs
    }

    private fun preparePredicateState(ps: PredicateState) = listOf(
            RemoveVoid,
            JavaTypeConverter.JavaTypeTransformer,
            BoolTypeAdapter(ClassManager().type),
            SimplifyPredicates,
            OptimizeEqualityChains,
                ConstantPropagator,
            Optimizer
    ).apply(ps).simplify()

    private fun preparePredicateState(psPair: Pair<PredicateState, PredicateState>) =
            preparePredicateState(psPair.first) to preparePredicateState(psPair.second)

    private fun analyzeSingleFile(file: KtFile, lqtAnnotations: List<AnnotationInfo>) {
        bindingContext = InitialLiquidTypeInfoCollector.collect(
                bindingContext, psiElementFactory, ideFileFinder,
                file, NewLQTInfo.typeInfo
        )
        addLqTAnnotationsInfo(lqtAnnotations)
        cleanupLqTInfo()
        analyzeLqTConstraintsFunLevel()
        val solver = initializeSolver()
        val safeProperties = checkFunctionCalls(file)

//        viewLiquidTypes("XXX", NewLQTInfo.typeInfo.values)


        for ((expr, safeProperty) in safeProperties) {
            val (lhs, rhs) = preparePredicateState(safeProperty)

            println("[lines ${expr.getLineNumber(true) + 1}:${expr.getLineNumber(false) + 1}]  ${expr.context?.text}")

            println("lhs")
            println(lhs)

            println("rhs")
            println(rhs)

//            psToGraphView("TestL", lhs)
//            psToGraphView("TestR", rhs)

            try {
                val result = solver.isViolated(lhs, rhs, negateQuery = true)
                println("$result ${expr.context?.text}")
                when (result) {
                    is Result.SatResult -> {
                        println("${result.model}")
//                        println("FUCK")
//                        println(PsModelInliner.inline(lhs, result.model))
                    }
                    is Result.UnsatResult -> {
                        println("${result.unsatCore}")
                    }
                    is Result.UnknownResult -> {
                        println(result.reason)
                    }
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
