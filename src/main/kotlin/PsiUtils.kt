import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import java.util.ArrayList

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
