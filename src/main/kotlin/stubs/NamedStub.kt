package stubs

import LiquidType
import CallExpressionLiquidType
import FunctionLiquidType
import org.jetbrains.kotlin.name.FqName

abstract class NamedStub<T>(val name: FqName, val constraint: (T) -> T) {
    fun eval(lqt: T): T = constraint(lqt)
}

class FunctionStub(
        name: FqName,
        constraint: (FunctionLiquidType) -> FunctionLiquidType
) : NamedStub<FunctionLiquidType>(name, constraint)

class PropertyStub(
        name: FqName,
        constraint: (LiquidType) -> LiquidType
) : NamedStub<LiquidType>(name, constraint)

abstract class Stub(val stubName: FqName) {
    abstract val properties: List<PropertyStub>
    abstract val functions: List<FunctionStub>

    fun function(
            vararg nameParts: String,
            constraint: (FunctionLiquidType) -> FunctionLiquidType
    ) = FunctionStub(
            FqName.fromSegments(stubName.pathSegments().map { it.asString() } + nameParts.toList()),
            constraint
    )

    fun function(
            name: FqName,
            constraint: (FunctionLiquidType) -> FunctionLiquidType
    ) = FunctionStub(name, constraint)

    fun property(
            vararg nameParts: String,
            constraint: (LiquidType) -> LiquidType
    ) = PropertyStub(
            FqName.fromSegments(stubName.pathSegments().map { it.asString() } + nameParts.toList()),
            constraint
    )

    fun property(
            name: FqName,
            constraint: (LiquidType) -> LiquidType
    ) = PropertyStub(name, constraint)

}
