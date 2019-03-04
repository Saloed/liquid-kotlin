import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.research.kex.asm.state.PredicateStateBuilder
import org.jetbrains.research.kex.asm.transform.LoopDeroller
import org.jetbrains.research.kfg.analysis.LoopSimplifier
import org.jetbrains.research.kfg.builder.cfg.CfgBuilder
import org.jetbrains.research.kfg.ir.ConcreteClass
import org.jetbrains.research.kfg.util.Flags
import org.jetbrains.research.kfg.util.JarUtils
import org.jetbrains.research.kfg.util.isClass
import java.util.jar.JarFile

object ClassFileElementResolver {
    fun resolve(elements: List<PsiElement>): Map<PsiElement, LiquidType> {

        val concreteClasses = elements
                .asSequence()
                .mapNotNull { it.containingFile }
                .mapNotNull { it.findContainingJarPath() }
                .toSet()
                .map { JarFile(it) }
                .flatMap {
                    JarUtils.parseJarClasses(it, Flags.readAll).values
                }
                .map { ConcreteClass(it).apply { init() } }
                .map { it.canonicalDesc to it }
                .toMap()

        val classMethods = concreteClasses.values.zipMap {
            buildMethods(it)
        }.toMap()


        val result = hashMapOf<PsiElement, LiquidType>()

        for (element in elements) {
            if (element !is PsiMethod) continue
            val elementClass = element.containingClass ?: continue
            val concreteClass = concreteClasses[elementClass.qualifiedName] ?: continue
            val methods = classMethods[concreteClass] ?: continue
            val method = methods[element.name] ?: continue
            LoopSimplifier.visit(method)
            LoopDeroller.visit(method)
            val builder = PredicateStateBuilder(method)
            builder.init()
            println("$builder")

            //todo: ArgumentTerm
            //todo: retval or retval.casted

        }

        return result
    }

    private fun buildMethods(concreteClass: ConcreteClass) = concreteClass.methods
            .filterNot { it.value.isAbstract }
            .filterNot { it.value.isNative }
            .map { CfgBuilder(it.value).build() }
            .map { it.name to it }
            .toMap()

}

fun JarUtils.parseJarClasses(jar: JarFile, flags: Flags) = jar.entries().asSequence()
        .filter { it.isClass }
        .map { readClassNode(jar.getInputStream(it), flags) }
        .map { it.name to it }
        .toMap()
