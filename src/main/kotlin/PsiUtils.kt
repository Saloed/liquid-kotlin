import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.decompiler.navigation.findDecompiledDeclaration
import org.jetbrains.kotlin.idea.inspections.findExistingEditor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import java.util.*

inline fun <reified T : PsiElement> PsiElement.findChildWithType(predicate: (T) -> Boolean = { true }) =
        this.children.filterIsInstance(T::class.java)
                .filter(predicate)
                .also { if (it.size > 1) throw IllegalStateException("Too many children with same type") }
                .firstOrNull()


fun PsiElement.getElementTextWithTypes() = getElementText { "| ${it::class.java.name} ${it.text}\n-----------\n" }

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


fun PsiElement.findOrCreateEditor(): Editor {
    val existingEditor = findExistingEditor()
    if (existingEditor != null) return existingEditor

    val file = containingFile?.virtualFile
            ?: throw IllegalArgumentException("No virtual file for $this")
    val document = FileDocumentManager.getInstance().getDocument(file)
            ?: throw IllegalArgumentException("No document for virtual file $file")

    return EditorFactory.getInstance().createEditor(document, project, file, true, EditorKind.MAIN_EDITOR)
}

fun DeclarationDescriptor.findSource(expression: KtExpression): PsiElement? {
    val element = DescriptorToSourceUtils.getSourceFromDescriptor(this)
            ?: findDecompiledDeclaration(expression.project, this, expression.resolveScope)

    val navElement = element?.navigationElement ?: return null
    return TargetElementUtil.getInstance().getGotoDeclarationTarget(element, navElement)
}

fun PsiFile.findContainingJarPath(): String? {
    var directory: PsiDirectory = containingDirectory ?: return null
    while (true) directory = directory.parentDirectory ?: break
    val possibleJarFile = directory.virtualFile
    if(possibleJarFile.extension != "jar") return null
    return possibleJarFile.presentableUrl
}
