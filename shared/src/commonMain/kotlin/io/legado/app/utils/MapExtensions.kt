package io.legado.app.utils

inline fun <K, V> MutableMap<K, V>.getOrPutLimit(key: K, maxSize: Int, defaultValue: () -> V): V {
    var value = get(key)
    if (containsKey(key)) {
        @Suppress("UNCHECKED_CAST")
        return value as V
    }
    value = defaultValue()
    if (size < maxSize) {
        put(key, value)
    }
    return value
}
