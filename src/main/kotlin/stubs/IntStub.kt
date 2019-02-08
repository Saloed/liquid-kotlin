package stubs

import LiquidType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.research.kex.ktype.KexClass
import org.jetbrains.research.kex.ktype.KexInt
import org.jetbrains.research.kex.state.predicate.PredicateFactory
import org.jetbrains.research.kex.state.term.TermFactory


object IntStub : BaseStub(
        ClassId(kotlinPackage, Name.identifier("Int")),
        emptyMap(),
        emptyMap()
)


object IntCompanionStub : BaseStub(
        ClassId(kotlinPackage, Name.identifier("Int.Companion")),
        mapOf(
                Name.identifier("MAX_VALUE") to { lqt: LiquidType ->
                    val maxConst = TermFactory.getConstant(Int.MAX_VALUE)
                    lqt.predicate = PredicateFactory.getEquality(lqt.variable, maxConst)
                    lqt
                },
                Name.identifier("MIN_VALUE") to { lqt: LiquidType ->
                    val minConst = TermFactory.getConstant(Int.MIN_VALUE)
                    lqt.predicate = PredicateFactory.getEquality(lqt.variable, minConst)
                    lqt
                }
        ),
        emptyMap()
)
