import org.jetbrains.kotlin.psi.*

open class KtTreeVisitorVoidWithTypeElement : KtTreeVisitorVoid() {

    open fun visitTypeElement(type: KtTypeElement) = visitKtElement(type)

    override fun visitUserType(type: KtUserType) = visitTypeElement(type)

    override fun visitDynamicType(type: KtDynamicType) = visitTypeElement(type)

    override fun visitSelfType(type: KtSelfType) = visitTypeElement(type)

    override fun visitFunctionType(type: KtFunctionType) = visitTypeElement(type)

    override fun visitNullableType(nullableType: KtNullableType) = visitTypeElement(nullableType)
}
