package annotation

import org.jetbrains.kotlin.ir.declarations.IrSymbolOwner
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.dump

class ElementConstraint {
    private val constraints = hashMapOf<IrSymbolOwner, List<IrExpression>>()

    fun add(element: IrSymbolOwner, constraint: IrExpression) {
        constraints[element] = get(element) + constraint
    }

    fun get(element: IrSymbolOwner) = constraints[element] ?: emptyList()


    override fun toString() = constraints.entries.joinToString("\n") { (element, constraint) ->
        val constraintStr = constraint.joinToString("\n") { it.dump() }
        "${element.dump()}\n$constraintStr"
    }

}
