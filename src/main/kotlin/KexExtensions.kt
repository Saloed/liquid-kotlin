import org.jetbrains.research.kex.ktype.kexType
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.ir.Class

fun Class.kexType(cm: ClassManager) = cm.type.getRefType(this).kexType

fun List<PredicateState>.chain() = fold(StateBuilder()) { result, state -> result.plus(state) }.apply().simplify()
