data class Trace<T>(private val trace: List<T> = emptyList()) {
    operator fun plus(element: T) = Trace(trace + listOf(element))
    operator fun contains(element: T) = element in trace
}