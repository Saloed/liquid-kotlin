import com.intellij.psi.PsiDirectory
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import kotlin.properties.Delegates


open class FolderProjectTest : LightCodeInsightFixtureTestCase() {

    val testFolder = "testData/"

    var baseDirectoryPsi by Delegates.notNull<PsiDirectory>()

    override fun getTestDataPath() = testFolder

    override fun getProjectDescriptor() = KotlinProjectWithLqTDescriptor.INSTANCE

    override fun setUp() {
        super.setUp()
        val directory = myFixture.copyDirectoryToProject("/", "")
        baseDirectoryPsi = myFixture.psiManager.findDirectory(directory)!!
    }

    fun testSimple() = LiquidTypeAnalyzer.analyze(project)

}
