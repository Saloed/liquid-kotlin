import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.caches.resolve.analyzeAndGetResult
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.ErrorType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.research.kex.ktype.KexClass

object ClassInfo {
    private val classTypeInfo = hashMapOf<String, KotlinType>()
    lateinit var psiElementFactory: KtPsiFactory
    lateinit var ktTypeContext: PsiElement

    var KexClass.typeInfo: KotlinType
        get() = classTypeInfo.getOrPut(name) { createKotlinType(name) }
        set(value) {
            classTypeInfo[name] = value
        }

    private fun createProperty(text: String): KtProperty {
        val file = psiElementFactory.createAnalyzableFile("dummy.kt", text, ktTypeContext)
        val declarations = file.declarations
        assert(declarations.size == 1) { "${declarations.size} declarations in $text" }
        return declarations.first().cast()
    }

    private fun createType(type: String): KtTypeReference {
        val createdTypeReference = createProperty("val x : $type").typeReference
        val typeReference = if (createdTypeReference?.text == type) createdTypeReference else null
        if (typeReference == null || typeReference.text != type) {
            throw IllegalArgumentException("Incorrect type: $type")
        }
        return typeReference
    }

    private fun createKotlinTypeWithArguments(type: String, argumentsCount: Int): KotlinType? {
        val typeWithArguments = when (argumentsCount) {
            0 -> type
            else -> {
                val stars = Array(argumentsCount) { "*" }
                val starsStr = stars.joinToString(",")
                "$type<$starsStr>"
            }
        }
        val typeReference = createType(typeWithArguments)
        val bindingContext = typeReference.analyzeAndGetResult().bindingContext
        return bindingContext[BindingContext.TYPE, typeReference]
    }

    fun createKotlinType(typeName: String): KotlinType {
        val dottedTypeName = typeName.replace('/', '.')
        var argumentsCountHack = 0
        while (argumentsCountHack <= 3) {
            val ktType = createKotlinTypeWithArguments(dottedTypeName, argumentsCountHack)
            if (ktType != null && ktType !is ErrorType) return ktType
            argumentsCountHack++
        }
        throw IllegalArgumentException("Can't create KotlinType for $typeName")
    }


    fun isSubtypeOf(left: KexClass, right: KexClass) = left.typeInfo.isSubtypeOf(right.typeInfo)

}