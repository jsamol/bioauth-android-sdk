package pl.edu.agh.bioauth.internal.util.extension

internal operator fun <K, V> Map<K, V>.plus(other: Map<K, V>): Map<K, List<V>> =
    (this.asSequence() + other.asSequence())
        .distinct()
        .groupBy({ it.key }, { it.value })
        .toMap()

internal fun <T, I: Iterable<T>> I.splitBy(predicate: (T) -> Boolean) : Pair<List<T>, List<T>> {
    val positives = filter(predicate)
    val negatives = filterNot(predicate)

    return Pair(positives, negatives)
}

internal fun List<Int>.median(): Int? =
        if (isNotEmpty()) sorted().run { ((get(size / 2)) + get((size - 1) / 2)) / 2 }
        else null