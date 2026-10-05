package dev.vibris.core

import java.util.Locale

internal object NameSuggestions {
    fun suggest(requested: String, available: Collection<String>): List<String> {
        val query = normalized(requested)
        return available.distinct().sortedWith(compareBy<String>(
            { candidate -> if (normalized(candidate).contains(query) || query.contains(normalized(candidate))) 0 else 1 },
            { candidate -> distance(query, normalized(candidate)) },
            { it },
        )).take(3)
    }

    private fun normalized(value: String): String {
        var result = value.lowercase(Locale.ROOT)
        for (prefix in listOf("iris_custom_image.", "usam_", "uimg_")) result = result.removePrefix(prefix)
        return result
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        a.forEachIndexed { row, left ->
            val current = IntArray(b.length + 1)
            current[0] = row + 1
            b.forEachIndexed { column, right ->
                current[column + 1] = minOf(current[column] + 1, previous[column + 1] + 1,
                    previous[column] + if (left == right) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
