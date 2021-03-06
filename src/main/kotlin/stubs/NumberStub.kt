package stubs

import FunctionLiquidType
import LiquidType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.research.kex.state.BasicState
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.TermFactory
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode

abstract class NumberOperationsStub(typeName: FqName) : Stub(typeName) {
    override val properties = listOf<PropertyStub>()
    override val functions = listOf(
            function("unaryMinus") { lqt ->
                val argument = lqt.dispatchArgument!!
                val value = LiquidType.createWithoutExpression(
                        argument.expression,
                        "-${argument.expression.text}",
                        argument.type
                )
                val negation = TermFactory.getNegTerm(argument.variable)
                val predicate = PredicateFactory.getEquality(value.variable, negation)
                setFunctionalLqtValue(lqt, value, predicate)
            },
            function("minus") { makeBinaryOperation(BinaryOpcode.Sub(), it) },
            function("plus") { makeBinaryOperation(BinaryOpcode.Add(), it) },
            function("times") { makeBinaryOperation(BinaryOpcode.Mul(), it) },
            function("div") { makeBinaryOperation(BinaryOpcode.Div(), it) },
            function("rem") { makeBinaryOperation(BinaryOpcode.Rem(), it) },
            function("compareTo") { lqt -> lqt } //fixme: AAAAAA
    )


    protected fun makeBinaryOperation(opcode: BinaryOpcode, lqt: FunctionLiquidType): FunctionLiquidType {
        val argument = lqt.dispatchArgument!!
        val otherArgument = lqt.arguments["other"]
                ?: throw IllegalArgumentException("No other argument supplied for Number.minus")

        val value = LiquidType.createWithoutExpression(
                argument.expression,
                "${argument.expression.text} ${opcode.name} ${otherArgument.expression.text}",
                argument.type
        )
        val result = TermFactory.getBinary(argument.type, opcode, argument.variable, otherArgument.variable)
        val predicate = PredicateFactory.getEquality(value.variable, result)
        return setFunctionalLqtValue(lqt, value, predicate)
    }

    protected fun setFunctionalLqtValue(lqt: FunctionLiquidType, value: LiquidType, vararg predicates: Predicate): FunctionLiquidType {
        val funTypeInfo = FunctionLiquidType(
                lqt.expression,
                lqt.type,
                lqt.variable,
                lqt.dispatchArgument,
                lqt.extensionArgument,
                lqt.arguments,
                value
        )
        val constraint = PredicateFactory.getEquality(lqt.variable, value.variable)
        val ps = BasicState(predicates.toList() + listOf(constraint))
        funTypeInfo.addPredicate(ps)
        return funTypeInfo
    }
}


object IntStub : NumberOperationsStub(FqName("kotlin.Int"))
object LongStub : NumberOperationsStub(FqName("kotlin.Long"))


//object IntCompanionStub : NamedStub(
//        ClassId(kotlinPackage, Name.identifier("Int.Companion")),
//        mapOf(
//                Name.identifier("MAX_VALUE") to { lqt: LiquidType ->
//                    val maxConst = TermFactory.getConstant(Int.MAX_VALUE)
//                    lqt.predicate = PredicateFactory.getEquality(lqt.variable, maxConst)
//                    lqt
//                },
//                Name.identifier("MIN_VALUE") to { lqt: LiquidType ->
//                    val minConst = TermFactory.getConstant(Int.MIN_VALUE)
//                    lqt.predicate = PredicateFactory.getEquality(lqt.variable, minConst)
//                    lqt
//                }
//        ),
//        emptyMap()
//)


object BooleanStub : NumberOperationsStub(FqName("kotlin.Boolean")) {
    override val properties = listOf<PropertyStub>()
    override val functions = listOf(
            function("not") { lqt ->
                val argument = lqt.dispatchArgument!!
                val value = LiquidType.createWithoutExpression(
                        argument.expression,
                        "-${argument.expression.text}",
                        argument.type
                )
                val negation = TermFactory.getNegTerm(argument.variable)
                val predicate = PredicateFactory.getEquality(value.variable, negation)
                setFunctionalLqtValue(lqt, value, predicate)
            },
            function("and") { makeBinaryOperation(BinaryOpcode.And(), it) },
            function("or") { makeBinaryOperation(BinaryOpcode.Or(), it) },
            function("xor") { makeBinaryOperation(BinaryOpcode.Xor(), it) }
    )
}
