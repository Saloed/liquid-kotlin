import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.research.kex.asm.state.PredicateStateAnalysis
import org.jetbrains.research.kex.asm.state.PredicateStateBuilder
import org.jetbrains.research.kex.asm.transform.LoopDeroller
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.ChainState
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kex.state.transformer.BoolTypeAdapter
import org.jetbrains.research.kex.state.transformer.ConstantPropagator
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.KfgConfigBuilder
import org.jetbrains.research.kfg.analysis.LoopSimplifier
import org.jetbrains.research.kfg.builder.cfg.CfgBuilder
import org.jetbrains.research.kfg.ir.ConcreteClass
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.ir.value.instruction.UnreachableInst
import org.jetbrains.research.kfg.util.Flags
import org.jetbrains.research.kfg.util.*
import org.jetbrains.research.kfg.util.isClass
import java.util.jar.JarFile


object ClassFileElementResolver {
    fun resolve(elements: List<PsiElement>): Map<PsiElement, LiquidType> {


        val jarClasses = elements
                .asSequence()
                .mapNotNull { it.containingFile }
                .mapNotNull { it.findContainingJarPath() }
                .toSet()
                .map { JarFile(it) }
                .flatMap {
                    parseJarClasses(it, Flags.readAll)
                }
                .toMap()
        val config = KfgConfigBuilder().failOnError(false).ignoreIncorrectClasses(true).build()
        val cm = ClassManager(jarClasses, config)

        val result = hashMapOf<PsiElement, LiquidType>()

        for (element in elements) {
            if (element in result) continue
            if(element is PsiClass){
                val className = element.qualifiedName?.replace('.', '/') ?: continue
                val concreteClass = cm.getByName(className)
                val lqt = LiquidType.createWithoutExpression(element, className, concreteClass.kexType(cm))
                result[element] = lqt
                continue
            }
            if (element !is PsiMethod) continue
            val elementClass = element.containingClass ?: continue
            val className = elementClass.qualifiedName?.replace('.', '/') ?: continue
            val concreteClass = cm.getByName(className)

            val paramTypes = element.parameters.map { it.cast<PsiParameter>().type.toKfgType(cm) }
            val methodCandidates = concreteClass.methods.filter {
                (element.isConstructor && it.name == "<init>") || it.name == element.name
            }.filter {
                it.argTypes.toList() == paramTypes
            }

            val method = when (methodCandidates.size) {
                0 -> throw IllegalArgumentException("No candidates for method $element")
                1 -> methodCandidates.first()
                else -> throw IllegalArgumentException("Too many candidates for method $element | $methodCandidates")
            }

            LoopSimplifier(cm).visit(method)
            LoopDeroller(cm).visit(method)

            val lqt = MethodLqtBuilder(cm, element, method).build()
            result[element] = lqt
        }

        return result
    }

    class MethodLqtBuilder(val cm: ClassManager, val expression: PsiMethod, val method: Method) {

        private data class PsiMethodCallInfo(
                val type: KexType,
                val arguments: Map<String, LiquidType>,
                val thisArgument: LiquidType?,
                val returnValue: LiquidType?,
                val predicateState: PredicateState
        )

        private data class Arguments(
                val mapping: Map<Term, Term>,
                val arguments: Map<String, LiquidType>
        ) {
            companion object {
                fun fromList(args: List<Argument>): Arguments {
                    val mapping = args.map { it.mapping }.toMap()
                    val arguments = args.map { it.arguments }.toMap()
                    return Arguments(mapping, arguments)
                }
            }
        }

        private data class Argument(
                val mapping: Pair<Term, Term>,
                val arguments: Pair<String, LiquidType>
        )

        private fun buildConstructor(arguments: Arguments, predicateState: PredicateState): PsiMethodCallInfo {
            val newObject = LiquidType.createWithoutExpression(expression, "kexNew.${method.`class`.name}",
                    cm.type.getRefType(method.`class`).kexType
            ).apply {
                addPredicate(PredicateFactory.getNew(variable))
            }
            val argument = TermFactory.getThis(newObject.type)
            val mappings = arguments.mapping + (argument to newObject.variable)
            val fields = method.`class`.fields.filter { it.defaultValue != null || it.type.isPrimary }
            val initializers = fields.map {
                val fieldName = TermFactory.getString(it.name)
                val fieldTerm = TermFactory.getField(it.type.kexType.makeReference(), newObject.variable, fieldName)
                val value = TermFactory.getConstant(it.defaultValue.safeAs() ?: it.type.typeDefaultValue())
                PredicateFactory.getFieldStore(fieldTerm, value)
            }
            val initializerState = BasicState(initializers)
            val bodyState = TermRemapper("kexCall.init.${method.`class`.name}.${UIDGenerator.id}", mappings).apply(predicateState)

            val ps = ChainState(initializerState, bodyState).simplify()
            return PsiMethodCallInfo(newObject.type, arguments.arguments, null, newObject, ps)
        }

        private fun buildStatic(arguments: Arguments, predicateState: PredicateState): PsiMethodCallInfo {
            val returnLqt = if (!method.returnType.isVoid)
                LiquidType.createWithoutExpression(expression, "kexReturn.${method.name}", method.returnType.kexType)
            else null
            val returnTerm = if (!method.returnType.isVoid) TermFactory.getReturn(method) else null

            val mappings = arguments.mapping + listOfNotNull(returnLqt?.let { returnTerm!! to it.variable })

            val ps = TermRemapper("kexCall.${method.name}.${UIDGenerator.id}", mappings).apply(predicateState)
            val returnType = returnLqt?.type ?: KexVoid()
            return PsiMethodCallInfo(returnType, arguments.arguments, null, returnLqt, ps.simplify())
        }

        private fun buildMethod(arguments: Arguments, predicateState: PredicateState): PsiMethodCallInfo {
            val thisType = cm.type.getRefType(method.`class`).kexType
            val thisLqt = LiquidType.createWithoutExpression(expression, "kexThis.${method.`class`.name}", thisType)
            val thisArgument = TermFactory.getThis(thisType)

            val returnLqt = if (!method.returnType.isVoid)
                LiquidType.createWithoutExpression(expression, "kexReturn.${method.name}", method.returnType.kexType)
            else null
            val returnTerm = if (!method.returnType.isVoid) TermFactory.getReturn(method) else null

            val mappings = arguments.mapping +
                    (thisArgument to thisLqt.variable) +
                    listOfNotNull(returnLqt?.let { returnTerm!! to it.variable })

            val ps = TermRemapper("kexCall.${method.name}.${UIDGenerator.id}", mappings).apply(predicateState)
            val returnType = returnLqt?.type ?: KexVoid()
            return PsiMethodCallInfo(returnType, arguments.arguments, thisLqt, returnLqt, ps.simplify())
        }

        fun build(): FunctionLiquidType {

            val builder = PredicateStateBuilder(method)
            builder.init()

            val lastInstruction = method.flatten().dropLastWhile { it is UnreachableInst }.last()
            val methodPredicateState = builder.getInstructionState(lastInstruction)
                    ?: throw IllegalArgumentException("No state for method $method")

            val transformers = listOf(
                    MethodInliner(method, PredicateStateAnalysis(cm)),
                    ConstantPropagator,
                    BoolTypeAdapter(cm.type)
            )

            val predicateState = transformers.apply(methodPredicateState)

            val parameterNames = expression.parameters.map { it.cast<PsiParameter>().name!! }
            val arguments = method.argTypes.zip(parameterNames).mapIndexed { idx, (type, name) ->
                buildArgument(expression, idx, type.kexType, name)
            }.let { Arguments.fromList(it) }

            val methodInfo = when {
                method.isConstructor -> buildConstructor(arguments, predicateState)
                method.isStatic -> buildStatic(arguments, predicateState)
                else -> buildMethod(arguments, predicateState)
            }



            val lqt = FunctionLiquidType(
                    expression,
                    methodInfo.type,
                    LiquidType.createVariable(methodInfo.type),
                    methodInfo.thisArgument,
                    null,
                    methodInfo.arguments,
                    methodInfo.returnValue
            ).apply {
                var ps = methodInfo.predicateState
                if (returnValue != null) {
                    ps += PredicateFactory.getEquality(variable, returnValue.variable)
                }
                addPredicate(ps)

                val values = ValueTermCollector.invoke(methodInfo.predicateState).toList()
                val variableHolder = LiquidType(expression, KexBool(), values.combineWithAnd(), mutableListOf()).apply {
                    addEmptyPredicate()
                }
                dependsOn.add(variableHolder)
            }

            val kotlinLqt = JavaTypeConverter.convertLiquidType(lqt)
            return kotlinLqt.cast()
        }



        private fun buildArgument(element: PsiElement, index: Int, type: KexType, name: String): Argument {
            val argument = TermFactory.getArgument(type, index)
            val lqt = LiquidType.createWithoutExpression(element, name, type)
            return Argument(argument to lqt.variable, name to lqt)
        }

    }

    private fun buildMethods(cm: ClassManager, concreteClass: ConcreteClass) = concreteClass.methods
            .filterNot { it.isAbstract }
            .filterNot { it.isNative }
            .map { CfgBuilder(cm, it).build() }
            .map { it.name to it }
            .toMap()

}

fun parseJarClasses(jar: JarFile, flags: Flags) = jar.entries().asSequence()
        .filter { it.isClass }
        .map { readClassNode(jar.getInputStream(it), flags) }
        .map { it.name to it }
        .toList()
