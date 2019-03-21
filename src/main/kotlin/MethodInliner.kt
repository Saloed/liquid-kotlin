import org.jetbrains.research.kex.asm.state.PredicateStateAnalysis
import org.jetbrains.research.kex.ktype.kexType
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.predicate.CallPredicate
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.term.*
import org.jetbrains.research.kex.state.transformer.RecollectingTransformer
import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.ir.Method
import java.util.*

class MethodInliner(val method: Method, val psa: PredicateStateAnalysis) : RecollectingTransformer<MethodInliner> {
    override val builders = ArrayDeque<StateBuilder>()
    private val cm = psa.cm
    private var inlineIndex = 0

    init {
        builders.push(StateBuilder())
    }

    override fun transformCallPredicate(predicate: CallPredicate): Predicate {
        val call = predicate.call as CallTerm
        val calledMethod = call.method

        if (!checkMethod(calledMethod)) return predicate

        val mappings = hashMapOf<Term, Term>()
        if (!call.isStatic) {
            val thisType = if (calledMethod.`class`.isAncestor(method.`class`))
                calledMethod.`class`.kexType(cm)
            else
                call.owner.type
            val `this` = tf.getThis(thisType)
            mappings[`this`] = call.owner
        }
        if (predicate.hasLhv) {
            val retval = tf.getReturn(calledMethod)
            mappings[retval] = predicate.lhv
        }

        for ((index, argType) in calledMethod.argTypes.withIndex()) {
            val argTerm = tf.getArgument(argType.kexType, index)
            val calledArg = call.arguments[index]
            mappings[argTerm] = calledArg
        }

        currentBuilder += prepareInlinedState(calledMethod, mappings) ?: return predicate

        return Transformer.Stub
    }

    private fun checkMethod(calledMethod: Method): Boolean {
        if (calledMethod.isFinal)
            return true
        if (method.name != calledMethod.name)
            return false
        if (calledMethod.`class`.isAncestor(method.`class`))
            return true
        return false
    }

    private fun prepareInlinedState(method: Method, mappings: Map<Term, Term>): PredicateState? {
        val builder = psa.builder(method)
        val lastInstruction = method.flatten().last()
        val endState = builder.getInstructionState(lastInstruction) ?: return null
        val inlinedState = MethodInliner(method, psa).apply(endState)
        return TermRemapper("inlined.${method.`class`.name}.${method.name}.${inlineIndex++}", mappings).apply(inlinedState)
    }
}

class TermRemapper(val suffix: String, val remapping: Map<Term, Term>) : Transformer<TermRemapper> {
    override fun transformTerm(term: Term): Term = remapping[term] ?: when (term) {
        is ValueTerm, is ArgumentTerm, is ReturnValueTerm -> tf.getValue(term.type, "${term.name}.$suffix")
        else -> term
    }
}