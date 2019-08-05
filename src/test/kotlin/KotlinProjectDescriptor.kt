import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ui.configuration.libraryEditor.NewLibraryEditor
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.LightProjectDescriptor
import java.io.File

open class KotlinLightProjectDescriptor protected constructor() : LightProjectDescriptor() {

    override fun getModuleType(): ModuleType<*> = StdModuleTypes.JAVA
    override fun getSdk() = TestJarUtil.mockJdk()

    override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) =
            configureModule(module, model)

    open fun configureModule(module: Module, model: ModifiableRootModel) {}

    companion object {
        val INSTANCE = KotlinLightProjectDescriptor()
    }
}


open class KotlinJdkAndLibraryProjectDescriptor(private val libraryFiles: List<File>) : KotlinLightProjectDescriptor() {

    constructor(libraryFile: File) : this(listOf(libraryFile))

    init {
        for (libraryFile in libraryFiles) {
            assert(libraryFile.exists()) { "Library file doesn't exist: " + libraryFile.absolutePath }
        }
    }

    override fun getModuleType(): ModuleType<*> = StdModuleTypes.JAVA

    override fun configureModule(module: Module, model: ModifiableRootModel) {
        val editor = NewLibraryEditor()
        editor.name = LIBRARY_NAME
        for (libraryFile in libraryFiles) {
            editor.addRoot(VfsUtil.getUrlForLibraryRoot(libraryFile), OrderRootType.CLASSES)
        }
        TestLibUtil.addLibrary(editor, model)
    }


    companion object {
        const val LIBRARY_NAME = "myLibrary"
    }
}


open class KotlinWithJdkAndRuntimeLightProjectDescriptor : KotlinJdkAndLibraryProjectDescriptor {
    protected constructor() : super(TestJarUtil.runtimeJarForTests)
}


open class KotlinProjectDescriptorWithStdlibSources : KotlinWithJdkAndRuntimeLightProjectDescriptor() {

    override fun configureModule(module: Module, model: ModifiableRootModel) {
        super.configureModule(module, model)
        val library = model.moduleLibraryTable.getLibraryByName(LIBRARY_NAME)!!
        val modifiableModel = library.modifiableModel
        modifiableModel.addRoot(VfsUtil.getUrlForLibraryRoot(TestJarUtil.runtimeSourcesJarForTests), OrderRootType.SOURCES)
        modifiableModel.commit()
    }

    companion object {
        val INSTANCE = KotlinProjectDescriptorWithStdlibSources()
    }
}

class KotlinProjectWithLqTDescriptor : KotlinProjectDescriptorWithStdlibSources() {
    override fun configureModule(module: Module, model: ModifiableRootModel) {
        super.configureModule(module, model)
        val library = model.moduleLibraryTable.createLibrary("LqT")
        val modifiableModel = library.modifiableModel
        modifiableModel.addRoot(VfsUtil.getUrlForLibraryRoot(TestJarUtil.lqtAnnotationJar), OrderRootType.CLASSES)
        modifiableModel.commit()
    }

    companion object {
        val INSTANCE = KotlinProjectWithLqTDescriptor()
    }
}
