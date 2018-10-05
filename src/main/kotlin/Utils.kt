import org.jetbrains.kotlin.utils.addToStdlib.swap

public inline fun <X, T, R> Iterable<Pair<X, T>>.mapSecond(transform: (T) -> R) =
        this.map { it.first to transform(it.second) }

public inline fun <X, T, R> Iterable<Pair<T, X>>.mapFirst(transform: (T) -> R) =
        this.map { transform(it.first) to it.second }

public inline fun <T, R> Iterable<T>.zipMap(transform: (T) -> R) =
        this.map { it to transform(it) }

public inline fun <T, X> Iterable<Pair<T?, X?>>.pairNotNull() =
        this.filter { it.first != null && it.second != null }
                .map { it.first!! to it.second!! }