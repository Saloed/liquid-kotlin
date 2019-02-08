package stubs

import LiquidType
import CallExpressionLiquidType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.research.kex.state.term.Term

val kotlinPackage = FqName("kotlin")

abstract class BaseStub(
        val classId: ClassId,
        val properties: Map<Name, (LiquidType) -> LiquidType>,
        val functions: Map<Name, (CallExpressionLiquidType) -> CallExpressionLiquidType>
)