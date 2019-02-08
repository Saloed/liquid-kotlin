import org.jetbrains.kotlin.builtins.BuiltInsPackageFragment
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor
import stubs.BaseStub
import stubs.IntCompanionStub
import stubs.IntStub

class KotlinBuiltInStub {

    private val knownStubs = mapOf(
            IntStub.classId to IntStub,
            IntCompanionStub.classId to IntCompanionStub
    )

    private fun rollUpToClassOrNull(declaration: DeclarationDescriptor): DeserializedClassDescriptor? {
        var parent = declaration
        while (parent !is DeserializedClassDescriptor) {
            parent = parent.containingDeclaration ?: break
        }
        return parent as? DeserializedClassDescriptor
    }

    fun analyzeUnknownBuiltInClass(
            typeStub: BaseStub,
            declaration: DeserializedClassDescriptor,
            `package`: BuiltInsPackageFragment,
            expr: KtExpression
    ): LiquidType? = LiquidType.create(expr, declaration.defaultType)

    fun analyzeUnknownBuiltInProperty(
            typeStub: BaseStub,
            declaration: DeserializedPropertyDescriptor,
            `package`: BuiltInsPackageFragment,
            expr: KtExpression
    ): LiquidType? {
        val propertyType = typeStub.properties[declaration.name]
        if (propertyType == null) {
            reportError("Unknown property ${declaration.name} for class $typeStub")
            return null
        }
        val lqt = LiquidType.create(expr, declaration.returnType)
        val lqtWithConstraint = propertyType(lqt)
        return lqtWithConstraint
    }

//    fun analyzeUnknownBuiltInFunction(
//            typeStub: BaseStub,
//            declaration: DeserializedSimpleFunctionDescriptor,
//            `package`: BuiltInsPackageFragment,
//            expr: KtExpression
//    ): LiquidType? {
//        val propertyType = typeStub.functions[declaration.name]
//        if (propertyType == null) {
//            reportError("Unknown function ${declaration.name} for class $typeStub")
//            return null
//        }
//        val type = declaration.returnTypeOrNothing
//        val variable =
//        val lqt = CallExpressionLiquidType(expr, type.toKexType(), )
//        val lqtWithConstraint = propertyType(lqt)
//        return lqtWithConstraint
//    }

    fun analyzeUnknownBuiltInDeclaration(declaration: DeclarationDescriptor, `package`: BuiltInsPackageFragment, expr: KtExpression): LiquidType? {
        val declarationClass = rollUpToClassOrNull(declaration)
        if (declarationClass == null) {
            reportError("BuiltIn class not found for: $declaration")
            return null
        }
        val typeStub = knownStubs[declarationClass.classId]
        if (typeStub == null) {
            reportError("Unknown builtin class ${declarationClass.classId}")
            return null
        }

        return when (declaration) {
            is DeserializedClassDescriptor -> analyzeUnknownBuiltInClass(typeStub, declaration, `package`, expr)
            is DeserializedPropertyDescriptor -> analyzeUnknownBuiltInProperty(typeStub, declaration, `package`, expr)
//            is DeserializedSimpleFunctionDescriptor -> analyzeUnknownBuiltInFunction(typeStub, declaration, `package`, expr)
            else -> {
                reportError("BUILTIN FUCK: $declaration")
                null
            }
        }
    }
}
