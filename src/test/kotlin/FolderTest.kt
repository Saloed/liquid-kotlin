import com.intellij.psi.PsiDirectory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlin.properties.Delegates


open class FolderProjectTest : LightJavaCodeInsightFixtureTestCase() {

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

    fun testPenetrateIr() = IRPenetration.penetrate(project)

}
