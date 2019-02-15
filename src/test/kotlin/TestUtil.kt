import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.PersistentLibraryKind
import com.intellij.openapi.roots.ui.configuration.libraryEditor.NewLibraryEditor
import java.io.File

object TestJarUtil {
    private const val TEST_JARS_DIR = "testJars"

    private fun getSdk(sdkHome: String, name: String): Sdk {
        val table = ProjectJdkTable.getInstance()
        val existing = table.findJdk(name)
        return existing ?: JavaSdk.getInstance().createJdk(name, sdkHome, true)
    }

    fun mockJdk() = getSdk("$TEST_JARS_DIR/mockJDK/jre", "Mock JDK")

    fun fullJdk(): Sdk {
        val javaHome = System.getProperty("java.home")
        assert(File(javaHome).isDirectory)
        return getSdk(javaHome, "Full JDK")
    }

    private fun assertExists(file: File): File {
        if (!file.exists()) {
            throw IllegalStateException("$file does not exist")
        }
        return file
    }

    val runtimeJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-stdlib.jar"))

    val runtimeJarForKotlinScript: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-script-runtime.jar"))

    val kotlinTestJar: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-test.jar"))

    val minimalRuntimeJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-stdlib-minimal-for-test.jar"))

    val kotlinTestJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlinc/lib/kotlin-test.jar"))

    val kotlinTestJUnitJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-test-junit.jar"))

    val kotlinTestJsJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-test-js.jar"))

    val reflectJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-reflect.jar"))

    val scriptRuntimeJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-script-runtime.jar"))

    val runtimeSourcesJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-stdlib-sources.jar"))

    val stdlibMavenSourcesJarForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-stdlib-sources.jar"))

    val stdlibCommonForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-stdlib-common.jar"))

    val stdlibCommonSourcesForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-stdlib-common-sources.jar"))

    val stdlibJsForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-stdlib-js.jar"))

    val jetbrainsAnnotationsForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/annotations-13.0.jar"))

    val jvmAnnotationsForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-annotations-jvm.jar"))

    val androidAnnotationsForTests: File
        get() = assertExists(File("$TEST_JARS_DIR/kotlin-annotations-android.jar"))

    val lqtAnnotationJar: File
        get() = assertExists(File("annotation/build/libs/annotation.jar"))

}

object TestLibUtil {
    fun addLibrary(editor: NewLibraryEditor, model: ModifiableRootModel, kind: PersistentLibraryKind<*>? = null): Library {
        val libraryTableModifiableModel = model.moduleLibraryTable.modifiableModel
        val library = libraryTableModifiableModel.createLibrary(editor.name, kind)

        val libModel = library.modifiableModel
        editor.applyTo(libModel as LibraryEx.ModifiableModelEx)

        libModel.commit()
        libraryTableModifiableModel.commit()

        return library
    }
}