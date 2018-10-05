import com.intellij.psi.PsiDirectory
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import kotlin.properties.Delegates


open class FolderProjectTest : LightCodeInsightFixtureTestCase() {

    val testFolder = "testData/"

    var baseDirectoryPsi by Delegates.notNull<PsiDirectory>()

    override fun getTestDataPath() = testFolder

    override fun setUp() {
        super.setUp()
//        addAnnotationLibrary()
        val directory = myFixture.copyDirectoryToProject("/", "")
        baseDirectoryPsi = myFixture.psiManager.findDirectory(directory)!!
    }

    private fun addAnnotationLibrary() = PsiTestUtil.addLibrary(myModule, "annotation/build/libs/annotation.jar")

    fun testSimple() = LiquidTypeAnalyzer.analyze(myModule.project)

}
