import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import java.util.*

object PsiProxy {
    val storage = IdentityHashMap<DeclarationDescriptor, PsiElement>()

}

fun DeclarationDescriptor.findPsiWithProxy(): PsiElement? = findPsi() ?: PsiProxy.storage[this]