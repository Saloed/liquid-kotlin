import com.intellij.psi.CommonClassNames
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.research.kex.ktype.KexArray
import org.jetbrains.research.kex.ktype.KexClass
import org.jetbrains.research.kex.ktype.KexReference
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.state.term.*
import org.jetbrains.research.kex.state.transformer.Transformer

val tf = TermFactory

object JavaTypeConverter {
    val toKotlinTypesMap: Map<String, String> = mapOf(
            CommonClassNames.JAVA_LANG_OBJECT to KotlinBuiltIns.FQ_NAMES.any.asString(),
            CommonClassNames.JAVA_LANG_BYTE to KotlinBuiltIns.FQ_NAMES._byte.asString(),
            CommonClassNames.JAVA_LANG_CHARACTER to KotlinBuiltIns.FQ_NAMES._char.asString(),
            CommonClassNames.JAVA_LANG_DOUBLE to KotlinBuiltIns.FQ_NAMES._double.asString(),
            CommonClassNames.JAVA_LANG_FLOAT to KotlinBuiltIns.FQ_NAMES._float.asString(),
            CommonClassNames.JAVA_LANG_INTEGER to KotlinBuiltIns.FQ_NAMES._int.asString(),
            CommonClassNames.JAVA_LANG_LONG to KotlinBuiltIns.FQ_NAMES._long.asString(),
            CommonClassNames.JAVA_LANG_SHORT to KotlinBuiltIns.FQ_NAMES._short.asString(),
            CommonClassNames.JAVA_LANG_BOOLEAN to KotlinBuiltIns.FQ_NAMES._boolean.asString(),
            CommonClassNames.JAVA_LANG_STRING to KotlinBuiltIns.FQ_NAMES.string.asString(),
            CommonClassNames.JAVA_LANG_ITERABLE to KotlinBuiltIns.FQ_NAMES.iterable.asString()//,
//            CommonClassNames.JAVA_UTIL_ITERATOR to KotlinBuiltIns.FQ_NAMES.iterator.asString(),
//            CommonClassNames.JAVA_UTIL_LIST to KotlinBuiltIns.FQ_NAMES.list.asString(),
//            CommonClassNames.JAVA_UTIL_COLLECTION to KotlinBuiltIns.FQ_NAMES.collection.asString(),
//            CommonClassNames.JAVA_UTIL_SET to KotlinBuiltIns.FQ_NAMES.set.asString(),
//            CommonClassNames.JAVA_UTIL_MAP to KotlinBuiltIns.FQ_NAMES.map.asString(),
//            CommonClassNames.JAVA_UTIL_MAP_ENTRY to KotlinBuiltIns.FQ_NAMES.mapEntry.asString(),
//            java.util.ListIterator::class.java.canonicalName to KotlinBuiltIns.FQ_NAMES.listIterator.asString()

    ).map { it.key.replace('.', '/') to it.value.replace('.', '/') }
            .toMap()

//    val toKotlinMutableTypesMap: Map<String, String> = mapOf(
//            CommonClassNames.JAVA_UTIL_ITERATOR to KotlinBuiltIns.FQ_NAMES.mutableIterator.asString(),
//            CommonClassNames.JAVA_UTIL_LIST to KotlinBuiltIns.FQ_NAMES.mutableList.asString(),
//            CommonClassNames.JAVA_UTIL_COLLECTION to KotlinBuiltIns.FQ_NAMES.mutableCollection.asString(),
//            CommonClassNames.JAVA_UTIL_SET to KotlinBuiltIns.FQ_NAMES.mutableSet.asString(),
//            CommonClassNames.JAVA_UTIL_MAP to KotlinBuiltIns.FQ_NAMES.mutableMap.asString(),
//            CommonClassNames.JAVA_UTIL_MAP_ENTRY to KotlinBuiltIns.FQ_NAMES.mutableMapEntry.asString(),
//            java.util.ListIterator::class.java.canonicalName to KotlinBuiltIns.FQ_NAMES.mutableListIterator.asString()
//    )

//    val kotlinToJavaTypesMap = toKotlinTypesMap.reverse()
//    val kotlinMutableToJavaTypesMap = toKotlinMutableTypesMap.reverse()

    //    fun kotlinToJava(name: String) = kotlinToJavaTypesMap[name] //?: kotlinMutableToJavaTypesMap[name]
    fun javaToKotlin(name: String) = toKotlinTypesMap[name]

    fun ktTypeIfPossible(type: KexType): KexType? {
        return when (type) {
            is KexClass -> {
                val ktName = javaToKotlin(type.name) ?: return null
                KexClass(ktName, type.memspace)
            }
            is KexArray -> {
                val elementType = ktTypeIfPossible(type.element) ?: return null
                KexArray(elementType, type.memspace)
            }
            is KexReference -> {
                val referenceType = ktTypeIfPossible(type.reference) ?: return null
                KexReference(referenceType, type.memspace)
            }
            else -> null
        }
    }


    fun convertLiquidType(lqt: LiquidType): LiquidType {
        //fixme: may fall into recursion
        return when (lqt) {
            is FunctionLiquidType -> {
                val newPredicate = lqt.getRawPredicate()?.let { JavaTypeTransformer.apply(it) }
                val newVariable = JavaTypeTransformer.transform(lqt.variable)
                val newType = ktTypeIfPossible(lqt.type) ?: lqt.type
                val newParameters = lqt.arguments.map { it.toPair() }.mapSecond { convertLiquidType(it) }.toMap()
                val newDispatch = lqt.dispatchArgument?.let { convertLiquidType(it) }
                val newExtension = lqt.extensionArgument?.let { convertLiquidType(it) }
                val newReturn = lqt.returnValue?.let { convertLiquidType(it) }
                val usedDeps = listOfNotNull(lqt.returnValue, lqt.dispatchArgument, lqt.extensionArgument) + lqt.arguments.values
                val newDeps = lqt.dependsOn.filterNot { it in usedDeps }.map { convertLiquidType(it) }
                FunctionLiquidType(lqt.expression, newType, newVariable, newDispatch, newExtension, newParameters, newReturn).also {
                    it.dependsOn.addAll(newDeps)
                    if (newPredicate != null) it.addPredicate(newPredicate)
                }
            }
            else -> {
                val newDeps = lqt.dependsOn.map { convertLiquidType(it) }
                val newPredicate = lqt.getRawPredicate()?.let { JavaTypeTransformer.apply(it) }
                val newVariable = JavaTypeTransformer.transform(lqt.variable)
                val newType = ktTypeIfPossible(lqt.type) ?: lqt.type
                LiquidType(lqt.expression, newType, newVariable, newDeps.toMutableList()).also {
                    if (newPredicate != null) it.addPredicate(newPredicate)
                }
            }
        }

    }

    object JavaTypeTransformer : Transformer<JavaTypeTransformer> {
        fun ktTypeIfPossible(term: Term) = ktTypeIfPossible(term.type)


        override fun transformTerm(term: Term): Term = super.transformTerm(term).also { it.debugInfo = term.debugInfo }

        override fun transformArgumentTerm(term: ArgumentTerm): Term = ktTypeIfPossible(term)?.let { tf.getArgument(it, term.index) }
                ?: term

        override fun transformArrayLoadTerm(term: ArrayLoadTerm): Term = ktTypeIfPossible(term)?.let { tf.getArrayLoad(it, term.arrayRef) }
                ?: term

        override fun transformBinaryTerm(term: BinaryTerm): Term = ktTypeIfPossible(term)?.let { tf.getBinary(it, term.opcode, term.lhv, term.rhv) }
                ?: term

        override fun transformBoundTerm(term: BoundTerm): Term = ktTypeIfPossible(term)?.let { tf.getBound(it, term.ptr) }
                ?: term

        override fun transformCallTerm(term: CallTerm): Term = ktTypeIfPossible(term)?.let { tf.getCall(it, term.owner, term.method, term.arguments) }
                ?: term

        override fun transformCastTerm(term: CastTerm): Term = ktTypeIfPossible(term)?.let { tf.getCast(it, term.operand) }
                ?: term

        override fun transformCmpTerm(term: CmpTerm): Term = ktTypeIfPossible(term)?.let { tf.getCmp(it, term.opcode, term.lhv, term.rhv) }
                ?: term

        override fun transformConstStringTerm(term: ConstStringTerm): Term = ktTypeIfPossible(term)?.let { tf.getString(it, term.name) }
                ?: term

        override fun transformConstClassTerm(term: ConstClassTerm): Term = ktTypeIfPossible(term)?.let { tf.getClass(it, term.`class`) }
                ?: term

        override fun transformFieldLoadTerm(term: FieldLoadTerm): Term = ktTypeIfPossible(term)?.let { tf.getFieldLoad(it, term.field) }
                ?: term

        override fun transformFieldTerm(term: FieldTerm): Term = ktTypeIfPossible(term)?.let { tf.getField(it, term.owner, term.fieldName) }
                ?: term

        override fun transformNegTerm(term: NegTerm): Term = ktTypeIfPossible(term)?.let { tf.getNegTerm(it, term.operand) }
                ?: term

        override fun transformReturnValueTerm(term: ReturnValueTerm): Term = ktTypeIfPossible(term)?.let { tf.getReturn(it, term.method) }
                ?: term

        override fun transformValueTerm(term: ValueTerm): Term = ktTypeIfPossible(term)?.let { tf.getValue(it, term.valueName) }
                ?: term

        override fun transformUndefTerm(term: UndefTerm): Term = ktTypeIfPossible(term)?.let { tf.getUndef(it) } ?: term
    }


}
