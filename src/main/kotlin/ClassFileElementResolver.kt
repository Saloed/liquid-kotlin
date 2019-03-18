import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.research.kex.asm.state.PredicateStateBuilder
import org.jetbrains.research.kex.asm.transform.LoopDeroller
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.ktype.kexType
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.ArgumentTerm
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.Package
import org.jetbrains.research.kfg.analysis.LoopSimplifier
import org.jetbrains.research.kfg.builder.cfg.CfgBuilder
import org.jetbrains.research.kfg.ir.ConcreteClass
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.util.Flags
import org.jetbrains.research.kfg.util.JarUtils
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
                    JarUtils.parseJarClasses(it, Flags.readAll)
                }
                .toMap()

        val cm = ClassManager(jarClasses, Package("*"))

        val result = hashMapOf<PsiElement, LiquidType>()

        for (element in elements) {
            if (element in result) continue
            if (element !is PsiMethod) continue
            val elementClass = element.containingClass ?: continue
            val className = elementClass.qualifiedName?.replace('.', '/') ?: continue
            val concreteClass = cm.getByNameOrNull(className) ?: continue

            val paramTypes = element.parameters.map { it.type.toKfgType(cm) }
            val methodCandidates = concreteClass.methods.values.filter {
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
            val builder = PredicateStateBuilder(method)
            builder.init()

            val lqt = buildLiquidType(cm, element, method)
            result[element] = lqt
        }

        return result
    }

    private fun buildLiquidType(cm: ClassManager, expression: PsiMethod, method: Method): FunctionLiquidType {
        val parameterNames = expression.parameters.map { it.name!! }
        val arguments = method.argTypes.zip(parameterNames).mapIndexed { idx, (type, name) ->
            buildArgument(expression, idx, type.kexType, name)
        }
//
//        val dispatch = if (!method.isConstructor && !method.isStatic) {
//            val type = cm.type.getRefType(method.`class`).kexType
//            val argument = TermFactory.getThis(type)
//            val lqt = LiquidType.createWithoutExpression(expression, "<this>", type)
//            val eqTerm = TermFactory.equalityTerm(argument, lqt.variable)
//            Triple(argument, lqt, eqTerm)
//        } else null


        val functionArguments = arguments.map { it.second }
        val argumentsLqt = functionArguments.map { it.second }
        val callArguments = arguments.map { it.first } //+ emptyListIfNull(dispatch?.first)
        val callTerm = TermFactory.getCall(method, callArguments)
        val returnValue = LiquidType.createWithoutExpression(expression, "callValue", method.returnType.kexType)
        val terms = arguments.map { it.third } + listOf(TermFactory.equalityTerm(returnValue.variable, callTerm))
        returnValue.predicate = PredicateFactory.getBool(terms.combineWithAnd())
        returnValue.dependsOn.addAll(argumentsLqt)

        val variable = LiquidType.createVariable(method.returnType.kexType).apply {
            additionalInfo = " : $expression"
        }

        val lqt = FunctionLiquidType(
                expression,
                method.returnType.kexType,
                variable,
                null,
                null,
                functionArguments.toMap(),
                returnValue
        )
        lqt.predicate = PredicateFactory.getEquality(lqt.variable, returnValue.variable)
        return lqt
    }

    private fun buildArgument(element: PsiElement, index: Int, type: KexType, name: String): Triple<ArgumentTerm, Pair<String, LiquidType>, Term> {
        val argument = TermFactory.getArgument(type, index)
        val lqt = LiquidType.createWithoutExpression(element, name, type)
        val eqTerm = TermFactory.equalityTerm(argument, lqt.variable)
        return Triple(argument, name to lqt, eqTerm)
    }

    private fun buildMethods(cm: ClassManager, concreteClass: ConcreteClass) = concreteClass.methods
            .filterNot { it.value.isAbstract }
            .filterNot { it.value.isNative }
            .map { CfgBuilder(cm, it.value).build() }
            .map { it.name to it }
            .toMap()

}

fun JarUtils.parseJarClasses(jar: JarFile, flags: Flags) = jar.entries().asSequence()
        .filter { it.isClass }
        .map { readClassNode(jar.getInputStream(it), flags) }
        .map { it.name to it }
        .toList()