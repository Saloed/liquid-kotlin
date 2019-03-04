import org.jetbrains.kotlin.name.FqName
import stubs.*

object KotlinBuiltInStub {
    private val functions = hashMapOf<FqName, FunctionStub>()
    private val properties = hashMapOf<FqName, PropertyStub>()

    private fun collectFromStub(stub: Stub) {
        functions.putAll(stub.functions.map { it.name to it })
        properties.putAll(stub.properties.map { it.name to it })
    }

    private val knownStubs = listOf(
            IntStub,
            LongStub
    )

    init {
        for (stub in knownStubs) collectFromStub(stub)
    }

    fun findFunction(name: FqName) = functions[name]
    fun findProperty(name: FqName) = properties[name]
}
