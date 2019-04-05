import com.intellij.lang.jvm.types.*
import com.intellij.psi.PsiTypeParameter
import org.jetbrains.research.kex.ktype.kexType
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.ir.Class
import org.jetbrains.research.kfg.ir.value.*
import org.jetbrains.research.kfg.type.*
import java.lang.IllegalStateException

fun Class.kexType(cm: ClassManager) = cm.type.getRefType(this).kexType

fun List<PredicateState>.chain() = fold(StateBuilder()) { result, state -> result.plus(state) }.apply().simplify()

fun List<Transformer<*>>.apply(ps: PredicateState) = fold(ps) { state, transform -> transform.apply(state) }

fun Type.typeDefaultValue() = when (this) {
    is BoolType -> BoolConstant(false, this)
    is ByteType -> ByteConstant(0, this)
    is ShortType -> ShortConstant(0, this)
    is IntType -> IntConstant(0, this)
    is LongType -> LongConstant(0, this)
    is CharType -> CharConstant(0.toChar(), this)

    is FloatType -> FloatConstant(0.0f, this)
    is DoubleType -> DoubleConstant(0.0, this)

    else -> throw IllegalStateException("Non primary type has no default values")
}


fun JvmPrimitiveType.toKfgType(): Type = when (kind) {
    JvmPrimitiveTypeKind.BOOLEAN -> BoolType
    JvmPrimitiveTypeKind.BYTE -> ByteType
    JvmPrimitiveTypeKind.CHAR -> CharType
    JvmPrimitiveTypeKind.DOUBLE -> DoubleType
    JvmPrimitiveTypeKind.FLOAT -> FloatType
    JvmPrimitiveTypeKind.INT -> IntType
    JvmPrimitiveTypeKind.LONG -> LongType
    JvmPrimitiveTypeKind.SHORT -> ShortType
    JvmPrimitiveTypeKind.VOID -> VoidType
    else -> throw IllegalArgumentException("Not possible")
}

fun JvmType.toKfgType(cm: ClassManager): Type = when (this) {
    is JvmPrimitiveType -> toKfgType()
    is JvmArrayType -> ArrayType(componentType.toKfgType(cm))
    is JvmReferenceType -> resolve()?.let {
        if (it is PsiTypeParameter) {
            val bounds = it.bounds
            if (bounds.isEmpty()) cm.type.getRefType("java/lang/Object")
            else throw NotImplementedError("Reference type with bounds is not implemented ($bounds)")
        } else cm.type.getRefType( name)
    } ?: throw IllegalArgumentException("Unknown JVM Class type")
    else -> throw NotImplementedError("Convertion from $this to KexType is not implemented")
}

