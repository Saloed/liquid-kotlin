import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileImpl
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.Fir2IrConverter
import org.jetbrains.kotlin.fir.builder.RawFirBuilder
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.java.FirJavaModuleBasedSession
import org.jetbrains.kotlin.fir.java.FirLibrarySession
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.renderWithType
import org.jetbrains.kotlin.fir.resolve.FirProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirProviderImpl
import org.jetbrains.kotlin.fir.resolve.transformers.FirTotalResolveTransformer
import org.jetbrains.kotlin.fir.service
import org.jetbrains.kotlin.idea.caches.resolve.IDEPackagePartProvider
import org.jetbrains.kotlin.idea.caches.resolve.KotlinCacheServiceImpl
import org.jetbrains.kotlin.idea.project.getLanguageVersionSettings
import org.jetbrains.kotlin.idea.project.getStableName
import org.jetbrains.kotlin.idea.search.allScope
import org.jetbrains.kotlin.idea.search.projectScope
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.idea.vfilefinder.KotlinJvmModuleAnnotationsIndex
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.TargetPlatform
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatform


object AnalyzeIr {

    class FirJvmModuleInfo(override val name: Name) : ModuleInfo {
        constructor(moduleName: String) : this(Name.identifier(moduleName))

        val dependencies: MutableList<ModuleInfo> = mutableListOf()

        override val platform: TargetPlatform
            get() = JvmPlatform

        override fun dependencies(): List<ModuleInfo> {
            return dependencies
        }
    }

    @JvmStatic
    fun analyze(project: Project) {

        val projectRootManager = ProjectRootManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        Extensions.getArea(project)
                .getExtensionPoint(PsiElementFinder.EP_NAME)
                .unregisterExtension(JavaElementFinder::class.java)

        for (module in project.allModules()) {
            val projectFilesAndDirectories = ArrayList<VirtualFile>()
            projectRootManager.fileIndex.iterateContent { projectFilesAndDirectories.add(it) }
            val allKtFilesPsi = projectFilesAndDirectories.asSequence()
                    .filterIsInstance(VirtualFileImpl::class.java)
                    .map(psiManager::findFile)
                    .filterIsInstance(KtFile::class.java)
                    .filter { it.name == "testJava.kt" }
                    .toList()

            val resolutionFacade = KotlinCacheServiceImpl(project).getResolutionFacade(allKtFilesPsi)


            val fileScope = GlobalSearchScope.filesScope(project, allKtFilesPsi.map { it.virtualFile })
            val scope = project.allScope().uniteWith(fileScope).uniteWith(module.getModuleWithDependenciesAndLibrariesScope(true))

            resolutionFacade.analyzeWithAllCompilerChecks(allKtFilesPsi)

            val provider = FirProjectSessionProvider(project)

            val moduleInfo = FirJvmModuleInfo(module.getStableName())

            val session: FirSession = FirJavaModuleBasedSession(moduleInfo, provider, scope).also {
                val dependenciesInfo = FirJvmModuleInfo(Name.special("<dependencies>"))
                moduleInfo.dependencies.add(dependenciesInfo)
                val librariesScope = ProjectScope.getLibrariesScope(project)
                val partProvider = IDEPackagePartProvider(librariesScope)

                FirLibrarySession.create(
                        dependenciesInfo, provider, librariesScope,
                        project, partProvider
                )

            }



            val builder = RawFirBuilder(session, stubMode = false)

            val resolveTransformer = FirTotalResolveTransformer()
            val firFiles = allKtFilesPsi.map {
                val firFile = builder.buildFirFile(it)
                (session.service<FirProvider>() as FirProviderImpl).recordFile(firFile)
                firFile
            }.also {
                try {
                    resolveTransformer.processFiles(it)
                } catch (e: Exception) {
                    throw e
                }
            }

            firFiles.forEach {
                println(it.renderWithType())
            }

            val moduleFragment =
                    Fir2IrConverter.createModuleFragment(session, firFiles, project.getLanguageVersionSettings())


            moduleFragment.files.forEach {
                println(it.dump())
            }
        }
    }
}
