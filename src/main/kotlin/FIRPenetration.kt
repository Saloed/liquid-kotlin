import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileImpl
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.backend.common.ir.Ir
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLower
import org.jetbrains.kotlin.backend.jvm.jvmPhases
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.backend.Fir2IrConverter
import org.jetbrains.kotlin.fir.backend.Fir2IrDeclarationStorage
import org.jetbrains.kotlin.fir.backend.Fir2IrResult
import org.jetbrains.kotlin.fir.builder.RawFirBuilder
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.impl.FirMemberFunctionImpl
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.impl.FirBlockImpl
import org.jetbrains.kotlin.fir.expressions.resolvedFqName
import org.jetbrains.kotlin.fir.java.FirJavaModuleBasedSession
import org.jetbrains.kotlin.fir.java.FirLibrarySession
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.resolve.FirProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirProviderImpl
import org.jetbrains.kotlin.fir.resolve.transformers.FirTotalResolveTransformer
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.caches.project.IdeaModuleInfo
import org.jetbrains.kotlin.idea.caches.project.isLibraryClasses
import org.jetbrains.kotlin.idea.caches.project.productionSourceInfo
import org.jetbrains.kotlin.idea.caches.resolve.IDEPackagePartProvider
import org.jetbrains.kotlin.idea.project.getLanguageVersionSettings
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.research.liquidtype.LqT


object IRPenetration {

    private fun prepareProjectExtensions(project: Project) {
        Extensions.getArea(project)
                .getExtensionPoint(PsiElementFinder.EP_NAME)
                .unregisterExtension(JavaElementFinder::class.java)
    }

    private fun createSession(module: Module, provider: FirProjectSessionProvider): FirJavaModuleBasedSession {
        val moduleInfo = module.productionSourceInfo()!!
        return FirJavaModuleBasedSession(moduleInfo, provider, moduleInfo.contentScope())
    }

    private fun createLibrarySession(project: Project, moduleInfo: IdeaModuleInfo, provider: FirProjectSessionProvider): FirLibrarySession {
        val contentScope = moduleInfo.contentScope()
        return FirLibrarySession.create(moduleInfo, provider, contentScope, project, IDEPackagePartProvider(contentScope))
    }

    private fun getFilesToAnalyze(project: Project): List<KtFile> {

        fun match(name: String): Boolean{
            return name == "irTest.kt"
//            return name in listOf("LqT.kt", "xxx.kt"  )
        }

        val projectRootManager = ProjectRootManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        val projectFilesAndDirectories = ArrayList<VirtualFile>()
        projectRootManager.fileIndex.iterateContent { projectFilesAndDirectories.add(it) }
        val allKtFilesPsi = projectFilesAndDirectories.asSequence()
                .filterIsInstance(VirtualFileImpl::class.java)
                .map(psiManager::findFile)
                .filterIsInstance(KtFile::class.java)
                .filter { match(it.name)}
                .toList()
        return allKtFilesPsi
    }

    private fun createFirSession(project: Project, module: Module): FirSession {
        val provider = FirProjectSessionProvider(project)
        val session = createSession(module, provider)
        val ideaModuleInfo = session.moduleInfo.cast<IdeaModuleInfo>()

        ideaModuleInfo.dependenciesWithoutSelf().forEach {
            if (it is IdeaModuleInfo && it.isLibraryClasses()) {
                createLibrarySession(project, it, provider)
            }
        }
        return session
    }

    private fun buildFir(session: FirSession, files: List<KtFile>): List<FirFile> {
        val builder = RawFirBuilder(session, stubMode = false)
        val firFiles = files.map {
            val firFile = builder.buildFirFile(it)
            (session.service<FirProvider>() as FirProviderImpl).recordFile(firFile)
            firFile
        }

        val resolveTransformer = FirTotalResolveTransformer()
        resolveTransformer.processFiles(firFiles)

        firFiles.forEach {
            println(it.renderWithType())
        }

        return firFiles
    }

    private fun buildIr(project: Project, session: FirSession, files: List<FirFile>): Fir2IrResult {
        val lvs = project.getLanguageVersionSettings()
        val ir = Fir2IrConverter.createModuleFragment(session, files, lvs)
        ir.irModuleFragment.files.forEach {
            println(it.dump())
        }
        return ir
    }

    private fun resolveLqtAnnotations(project: Project, session: FirSession, files: List<FirFile>): List<FirFile> {
        val lqtVisitor = LqtAnnotationVisitor(project, session)
        files.forEach {
            it.accept(lqtVisitor)
        }
        val resolveTransformer = FirTotalResolveTransformer()
        resolveTransformer.processFiles(files)

        files.forEach {
            println(it.renderWithType())
        }
        return files
    }

    @JvmStatic
    fun penetrate(project: Project) {
        prepareProjectExtensions(project)
        val allKtFiles = getFilesToAnalyze(project)
        val module = project.allModules().also {
            require(it.size == 1) { "Project with single module expected" }
        }.first()

        val session = createFirSession(project, module)
        val firFiles = buildFir(session, allKtFiles)
//    resolveLqtAnnotations(project, session, firFiles)
        val ir = buildIr(project, session, firFiles)



        val a = 3

    }
}


class LqtAnnotationVisitor(val project: Project, val session: FirSession) : FirVisitorVoid() {

    val lqtAnnotationFqName = FqName.fromSegments(LqT::class.qualifiedName!!.split("."))
    val lqtConditionEvaluatorFunctionName = "org.jetbrains.research.liquidtype.evaluateCondition"

    override fun visitElement(element: FirElement) {
        element.acceptChildren(this)
    }

    private fun isLqtAnnotation(annotationCall: FirAnnotationCall): Boolean {
        val annotationName = annotationCall.resolvedFqName ?: return false
        return annotationName == lqtAnnotationFqName
    }

    private fun getLqtCondition(annotationCall: FirAnnotationCall) = annotationCall.arguments
            .firstOrNull()
            ?.safeAs<FirConstExpression<String>>()
            ?.value
            ?: throw IllegalArgumentException("Lqt annotation is malformed")


    override fun visitFunction(function: FirFunction) {
        super.visitFunction(function)
        val annotatedParameters = function.valueParameters.mapIndexed { idx: Int, parameter: FirValueParameter ->
            analyzeValueParameter(idx, parameter)
        }.filterNotNull()
        if (annotatedParameters.isEmpty()) return

        addParameterConstraintsToFunctionBody(function, annotatedParameters)
    }


    private fun addParameterConstraintsToFunctionBody(function: FirFunction, constraints: List<FirStatement>) {
        if (function !is FirMemberFunctionImpl) {
            TODO("Only FirMemberFunctionImpl is supported")
        }

        val newBody = when (val currentBody = function.body) {
            null -> FirBlockImpl(session, null).apply { statements.addAll(constraints) }
            else -> FirBlockImpl(session, currentBody.psi).apply {
                statements.addAll(constraints)
                statements.addAll(currentBody.statements)
            }
        }
        function.body = newBody
    }

    private fun analyzeValueParameter(idx: Int, parameter: FirValueParameter): FirStatement? {
        val lqtAnnotation = parameter.annotations.find { isLqtAnnotation(it) } ?: return null
        val condition = getLqtCondition(lqtAnnotation)
        val conditionExpression = "fun condition_$idx(): Boolean =  $lqtConditionEvaluatorFunctionName(${parameter.name.asString()}){ $condition }"
        val conditionPsi = KtPsiFactory(project).createExpressionCodeFragment(conditionExpression, null)
        val fir = RawFirBuilder(session, stubMode = false).buildFirFile(conditionPsi)
        return fir.declarations.first().cast()
    }

}
